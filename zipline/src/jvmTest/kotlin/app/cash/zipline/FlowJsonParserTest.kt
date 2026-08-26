/*
 * Copyright (C) 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.zipline

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test

/**
 * Tests the incremental (flow) JSON parser in Hermes internals
 * (FlowJSONParser) via [JsEngine.flowJsonParseForTest], which feeds the input
 * in fixed-size chunks and returns a verification string produced natively:
 * the flow-parsed result and the same input parsed with the runtime
 * JSON.parse, both re-stringified with JSON.stringify.
 */
class FlowJsonParserTest {
  private val engine = JsEngine.create()

  @After fun tearDown() {
    engine.close()
  }

  /**
   * Asserts the chunked flow parse completes and equals the reference parse.
   * Format: "OK|Done|<root>|<expected>" — compared via the midpoint so that
   * '|' inside string values cannot confuse the check.
   */
  private fun assertRoundTrip(json: String) {
    for (chunk in listOf(0, 1, 2, 3, 7, 64, 1024)) {
      val res = engine.flowJsonParseForTest(json, chunk, flow = false)
      assertTrue(res.startsWith("OK|Done|"), "chunk=$chunk json=$json -> $res")
      val rest = res.removePrefix("OK|Done|")
      // rest = root + "|" + expected, and root must equal expected.
      assertTrue(rest.length % 2 == 1, "chunk=$chunk json=$json -> $res")
      val mid = rest.length / 2
      assertEquals('|', rest[mid], "chunk=$chunk json=$json -> $res")
      assertEquals(
        rest.substring(0, mid),
        rest.substring(mid + 1),
        "chunk=$chunk json=$json -> $res",
      )
    }
  }

  private fun assertFlow(json: String, elements: Int, first: String, last: String) {
    for (chunk in listOf(0, 1, 3, 64)) {
      val res = engine.flowJsonParseForTest(json, chunk, flow = true)
      // OK|Done|elements=N|<first>|<last>|<expected>
      assertTrue(
        res.startsWith("OK|Done|elements=$elements|$first|$last|"),
        "chunk=$chunk json=$json -> $res",
      )
    }
  }

  private fun assertError(json: String) {
    // Whole-input and chunked feeding must both fail.
    for (chunk in listOf(0, 1, 7)) {
      val res = engine.flowJsonParseForTest(json, chunk, flow = false)
      assertTrue(res.startsWith("ERR|"), "chunk=$chunk json=$json -> $res")
    }
  }

  @Test fun scalarsAndContainers() {
    assertRoundTrip("""[1, 2.5, -3e2, "text", true, false, null]""")
    assertRoundTrip("""{"a": {"b": [1, {"c": "d"}]}, "e": null}""")
    assertRoundTrip("""{"a": 1}""")
    assertRoundTrip("""[[] , {}, "", 0]""")
    assertRoundTrip("""  [ 1 , { "k" : [true] } ]  """)
    assertRoundTrip("""5""")
    assertRoundTrip(""""just a string"""")
    assertRoundTrip("""[-0.5, 1e10, 0.1, 123456789]""")
  }

  @Test fun unicodeAndEscapes() {
    // Raw non-ASCII (multi-byte UTF-8 split across 1-byte chunks), \u escapes,
    // and a surrogate pair escape split across chunks.
    assertRoundTrip("""["π ≈ 3.14", "emoji 😀 inside", "raw é char", "😀"]""")
    assertRoundTrip("""["line\nbreak", "quote\"q", "back\\slash"]""")
  }

  @Test fun flowMode() {
    assertFlow("""[{"id":1},{"id":2},{"id":3}]""", 3, """{"id":1}""", """{"id":3}""")
    assertFlow("""[1,2,3]""", 3, "1", "3")
    assertFlow("""[]""", 0, "", "")
    assertFlow(
      """[[1,2],"x",{"k":null}]""",
      3,
      "[1,2]",
      """{"k":null}""",
    )
  }

  @Test fun errors() {
    assertError("") // empty input
    assertError("[1, 2") // unterminated array
    assertError("""{"a": }""") // missing value
    assertError("""{"a" 1}""") // missing colon
    assertError("""{"a": 1,}""") // trailing comma in object
    assertError("[1, 2,]") // trailing comma in array (rejected by JSON.parse)
    assertError("tru") // truncated keyword at final input
    assertError("[1] x") // trailing garbage
    assertError("-") // truncated number at final input
    assertError("""["abc""") // unterminated string
    assertError("""["ab\u12""") // truncated unicode escape
  }

  @Test fun flowModeRejectsNonArrayRoot() {
    val res = engine.flowJsonParseForTest("""{"a":1}""", 0, flow = true)
    assertTrue(res.startsWith("ERR|"), res)
    val res2 = engine.flowJsonParseForTest("""5""", 0, flow = true)
    assertTrue(res2.startsWith("ERR|"), res2)
  }

  @Test fun largerDocument() {
    // A bigger doc, generated host-side, round-tripped with tiny chunks.
    val sb = StringBuilder("[")
    for (i in 0 until 500) {
      if (i > 0) sb.append(',')
      sb.append("""{"id":$i,"title":"item $i","price":${199.99 + i},"tags":["t$i","sale"],"seller":{"id":${1000 + i},"rating":4.5,"verified":${i % 2 == 0}}}""")
    }
    sb.append("]")
    assertRoundTrip(sb.toString())
  }

  // -------------------------------------------------------------------
  // The %FlowJSON global (JSLib builtin, JSLibFlags.enableFlowJsonParser).
  // -------------------------------------------------------------------

  /** Whole-document mode: chunked feed from JS, root() matches JSON.parse. */
  @Test fun jsBuiltinWholeDocument() {
    val result = engine.evaluate(
      """
      (function() {
        var doc = '[{"id":1,"title":"item 1","tags":["t1","sale"]},[1,2.5,-3e2],"text",true,null]';
        var p = FlowJSON.createParser();
        var statuses = [];
        for (var i = 0; i < doc.length; i += 7) {
          statuses.push(p.feed(doc.substring(i, i + 7), i + 7 >= doc.length));
        }
        var ref = JSON.stringify(JSON.parse(doc));
        var got = JSON.stringify(p.root());
        return statuses.join(',') + ' => ' + (got === ref ? 'MATCH' : 'MISMATCH ' + got + ' vs ' + ref);
      })()
      """,
      "flowJsonTest.js",
    )
    assertTrue(
      result.toString().endsWith("=> MATCH"),
      "unexpected: $result",
    )
    assertTrue(result.toString().contains("done"), "final status must be done: $result")
  }

  /** Flow mode: elements arrive via the callback as chunks are fed. */
  @Test fun jsBuiltinFlowMode() {
    val result = engine.evaluate(
      """
      (function() {
        var elements = [];
        var p = FlowJSON.createParser(function(el) { elements.push(el); });
        var doc = '[{"id":1},[1,2],"x",{"k":null},4]';
        for (var i = 0; i < doc.length; i += 3) {
          p.feed(doc.substring(i, i + 3), i + 3 >= doc.length);
        }
        return JSON.stringify(elements);
      })()
      """,
      "flowJsonTest.js",
    )
    assertEquals("""[{"id":1},[1,2],"x",{"k":null},4]""", result)
  }

  /** A multi-byte UTF-8 (well, UTF-16 in JS) char split across chunk feeds. */
  @Test fun jsBuiltinUnicodeAcrossChunks() {
    val result = engine.evaluate(
      """
      (function() {
        var p = FlowJSON.createParser();
        var doc = '["π ≈ 3.14", "emoji 😀!"]';
        for (var i = 0; i < doc.length; i += 1) {
          p.feed(doc.substring(i, i + 1), i + 1 >= doc.length);
        }
        var ref = JSON.stringify(JSON.parse(doc));
        return JSON.stringify(p.root()) === ref ? 'MATCH' : 'MISMATCH';
      })()
      """,
      "flowJsonTest.js",
    )
    assertEquals("MATCH", result)
  }

  /** Malformed input at a final feed throws a SyntaxError. */
  @Test fun jsBuiltinSyntaxError() {
    val result = engine.evaluate(
      """
      (function() {
        var p = FlowJSON.createParser();
        try {
          p.feed('[1, 2', true);
          return 'NO THROW';
        } catch (e) {
          return (e instanceof SyntaxError) + ':' + e.message.substring(0, 16);
        }
      })()
      """,
      "flowJsonTest.js",
    )
    assertTrue(result.toString().startsWith("true:"), "unexpected: $result")
  }

  /** root() before completion throws a TypeError. */
  @Test fun jsBuiltinRootBeforeDone() {
    val result = engine.evaluate(
      """
      (function() {
        var p = FlowJSON.createParser();
        p.feed('[1, 2', false);
        try {
          p.root();
          return 'NO THROW';
        } catch (e) {
          return (e instanceof TypeError).toString();
        }
      })()
      """,
      "flowJsonTest.js",
    )
    assertEquals("true", result)
  }

  /** A JS exception thrown by the element callback propagates out of feed(). */
  @Test fun jsBuiltinCallbackExceptionPropagates() {
    val result = engine.evaluate(
      """
      (function() {
        var p = FlowJSON.createParser(function(el) {
          if (el === 2) throw new Error('boom');
        });
        try {
          p.feed('[1, 2, 3]', true);
          return 'NO THROW';
        } catch (e) {
          return e.message;
        }
      })()
      """,
      "flowJsonTest.js",
    )
    assertEquals("boom", result)
  }
}
