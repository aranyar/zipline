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

import app.cash.zipline.testing.loadTestingJsModulesOnlyOnEngine
import org.junit.After
import org.junit.Test

/**
 * Measures JSON decode strategies inside the real Hermes engine, as a
 * baseline for the future flow (streaming) JSON parsing in Hermes internals:
 *
 *  - decodeFromStringFast: Hermes native JSON.parse + decodeFromDynamic
 *    (today's zipline path — needs the whole body as a string first);
 *  - decodeFromString: pure-Kotlin kotlinx-serialization lexer;
 *  - decodeFromSource (kotlinx-serialization-json-io): streaming decoder over
 *    a fully buffered source;
 *  - downloadOnly: simulated slow download (chunked, per-chunk latency), no
 *    decode — the pure network cost of the body;
 *  - download+decodeFromStringFast: today's full flow — simulated slow
 *    download to a complete string, then fast decode (strictly sequential).
 *
 * Results are printed to stdout; run with:
 *   ./gradlew :zipline:jvmTest --tests "app.cash.zipline.JsonFlowBenchmarkTest"
 */
class JsonFlowBenchmarkTest {
  private val engine = JsEngine.create()

  @After fun tearDown() {
    engine.close()
  }

  @Test fun jsonDecodeBenchmark() {
    engine.installModuleLoader()
    // Registers the whole testing bundle (stdlib, serialization incl. the
    // json-io/json-okio streaming variants, okio, kotlinx-io, zipline, ...).
    // The module loader runs UMD factories eagerly at define() time, but the
    // shared list in loadTestingJsModulesOnlyOnEngine is topologically sorted.
    engine.loadTestingJsModulesOnlyOnEngine()
    engine.evaluate("""globalThis.bench = require('./zipline-root-zipline-testing.js');""")

    val pkg = "bench.app.cash.zipline.testing"

    // Pure parse comparison across payload sizes (item count).
    for (itemCount in listOf(10, 100, 1_000, 10_000)) {
      engine.evaluate("globalThis.benchInput = $pkg.generateBenchJson($itemCount);")
      val chars = engine.evaluate("globalThis.benchInput.length")
      println("=== items=$itemCount jsonChars=$chars")
      println(engine.evaluate("$pkg.benchDecodeFromStringFast(globalThis.benchInput, 3)"))
      println(engine.evaluate("$pkg.benchDecodeFromStringKotlinx(globalThis.benchInput, 3)"))
      println(engine.evaluate("$pkg.benchDecodeFromSourceIo(globalThis.benchInput, 3)"))
    }

    // Simulated slow network: 16 KB chunks, 5 ms of latency per chunk,
    // scaled by item count.
    for (itemCount in listOf(100, 1_000, 10_000)) {
      engine.evaluate("globalThis.benchInput = $pkg.generateBenchJson($itemCount);")
      val chars = engine.evaluate("globalThis.benchInput.length")
      println("=== slow network: items=$itemCount jsonChars=$chars chunk=16384 latency=5ms")
      println(engine.evaluate("$pkg.benchDownloadOnly(globalThis.benchInput, 16384, 5, 3)"))
      println(engine.evaluate("$pkg.benchDownloadThenDecodeFast(globalThis.benchInput, 16384, 5, 3)"))
    }

    engine.evaluate("delete globalThis.benchInput; delete globalThis.bench;")
  }
}
