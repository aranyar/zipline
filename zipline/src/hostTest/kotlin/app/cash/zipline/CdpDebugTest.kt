@file:OptIn(DelicateCoroutinesApi::class, ExperimentalEncodingApi::class, ExperimentalAtomicApi::class)

package app.cash.zipline

import app.cash.zipline.internal.cdp.DebugServerSocket
import app.cash.zipline.internal.cdp.DebugSocket
import app.cash.zipline.internal.cdp.WebSocketProtocol
import app.cash.zipline.internal.cdp.closeQuietly
import app.cash.zipline.internal.cdp.connectDebugSocket
import app.cash.zipline.internal.cdp.httpGet
import app.cash.zipline.internal.cdp.startDebugThread
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.EOFException
import okio.IOException

/**
 * End-to-end test of CDP (Chrome DevTools Protocol) debugging: starts the debug server via
 * the CDP port config (system property on JNI, ZIPLINE_CDP_PORT env var on Kotlin/Native),
 * connects over HTTP + WebSocket like Chrome DevTools would, and drives the Debugger/Runtime
 * domains.
 *
 * The debugged script is precompiled to Hermes bytecode (with debug info and an embedded source
 * map) and embedded below, because lean engine builds cannot compile JS at runtime.
 */
class CdpDebugTest {
  private val dispatcher = newSingleThreadContext("CdpDebugTest")
  private var zipline: Zipline? = null

  @BeforeTest
  fun setUp() {
    setCdpPortEnv(PORT)
  }

  @AfterTest
  fun tearDown() {
    setCdpPortEnv(null)
    zipline?.close()
    zipline = null
    (dispatcher as? CloseableCoroutineDispatcher)?.close()
  }

  @Test
  fun debugSessionOverCdp() = runBlocking<Unit> {
    val zipline = Zipline.create(dispatcher)
    this@CdpDebugTest.zipline = zipline

    // Load a script carrying debug info (line tables + source map), so the CDP
    // Debugger domain can report it and resolve breakpoints.
    withContext(dispatcher) {
      zipline.jsEngine.execute(testScriptBytecode, SCRIPT_URL)
    }

    // Discovery: /json/list advertises the debug target like chrome://inspect expects.
    val sessionId = discoverSessionId()

    CdpClient(sessionId).use { cdp ->
      cdp.send(1, "Runtime.enable")
      cdp.send(2, "Debugger.enable")
      cdp.awaitResponse(2)
      val scriptParsed = cdp.awaitEvent("Debugger.scriptParsed")
      // CDP JSON escapes '/' as '\/', so just check the file name.
      assertTrue(
        scriptParsed.contains("test.js"),
        "scriptParsed should reference test.js: $scriptParsed",
      )

      // Breakpoint on `return x + 1;` (0-based line 2; line 1 is constant-folded
      // away by the optimizer).
      cdp.send(
        3, "Debugger.setBreakpointByUrl",
        """"params":{"url":"$SCRIPT_URL","lineNumber":2}""",
      )
      val setBpResponse = cdp.awaitResponse(3)
      assertTrue(setBpResponse.contains("breakpointId"), setBpResponse)
      val breakpointId = Regex(""""breakpointId":"(\d+)"""").find(setBpResponse)!!.groupValues[1]

      // Trigger the breakpoint from the JS thread; it blocks until we resume.
      val resultDeferred = async(dispatcher) {
        zipline.jsEngine.callGlobalFunctionWithStringArg("hitMe", "")
      }
      val paused = cdp.awaitEvent("Debugger.paused")

      // Console evaluation while paused: Runtime.evaluate only goes to the
      // integrator queue, which is drained via the debugger interrupt path
      // when the JS thread is blocked in the pause loop.
      if (evaluateSupported(zipline.jsEngine)) {
        cdp.send(40, "Runtime.evaluate", """"params":{"expression":"40 + 2"}""")
        val evalResponse = cdp.awaitResponse(40)
        assertTrue(evalResponse.contains("42"), "Runtime.evaluate while paused: $evalResponse")
      }

      cdp.send(4, "Debugger.resume")
      cdp.awaitResponse(4)
      // hitMe() returns a number, which this API surfaces as null; the point is
      // that the call completes, proving the resume unblocked the JS thread.
      assertEquals(null, resultDeferred.await())
      assertTrue(paused.contains("hitMe"), paused)

      // Remove the breakpoint, then evaluate while the engine is idle. This exercises the
      // drain path: the runtime task is enqueued natively and pumped by the Zipline scope.
      // Runtime.evaluate compiles JS, which lean builds (Android) cannot do.
      if (evaluateSupported(zipline.jsEngine)) {
        cdp.send(5, "Debugger.removeBreakpoint", """"params":{"breakpointId":"$breakpointId"}""")
        cdp.send(6, "Runtime.evaluate", """"params":{"expression":"hitMe()"}""")
        val evalResponse = cdp.awaitResponse(6)
        assertTrue(evalResponse.contains("42"), evalResponse)
      }
    }

    // A second client (e.g. a reopened DevTools window) must receive scriptParsed
    // events again even though the Debugger domain was already enabled before.
    CdpClient(sessionId).use { cdp ->
      cdp.send(1, "Runtime.enable")
      cdp.send(2, "Debugger.enable")
      cdp.awaitResponse(2)
      val scriptParsed = cdp.awaitEvent("Debugger.scriptParsed")
      assertTrue(
        scriptParsed.contains("test.js"),
        "scriptParsed after reconnect should reference test.js: $scriptParsed",
      )

      // DevTools loads script content only via Debugger.getScriptSource (no HTTP
      // fallback), which the Hermes agent doesn't implement; our debug server
      // answers it by fetching the script URL from the dev server. Serve it
      // ourselves here on the URL baked into servedScriptBytecode.
      val scriptSource = "function hitMe() { return 42; }"
      val sourceMap = """{"version":3,"file":"test.js","sources":["test.kt"],"names":[],"mappings":"AAAA,EAAE"}"""
      val httpd = MiniHttpServer(SOURCE_SERVER_PORT, mapOf(
        "/test.js" to scriptSource,
        "/test.js.map" to sourceMap,
      ))
      try {
        httpd.start()
        withContext(dispatcher) {
          zipline.jsEngine.execute(servedScriptBytecode, SERVED_SCRIPT_URL)
        }
        val parsed = cdp.awaitEventContaining("18080")
        val scriptId = Regex(""""scriptId":"(\d+)"""").find(parsed)!!.groupValues[1]
        cdp.send(3, "Debugger.getScriptSource", """"params":{"scriptId":"$scriptId"}""")
        val sourceResponse = cdp.awaitResponse(3)
        assertTrue(
          sourceResponse.contains(scriptSource),
          "getScriptSource should return the served source: $sourceResponse",
        )

        cdp.send(
          30, "Debugger.getPossibleBreakpoints",
          """"params":{"start":{"scriptId":"$scriptId","lineNumber":0,"columnNumber":0},"end":{"scriptId":"$scriptId","lineNumber":10,"columnNumber":0}}""",
        )
        val possible = cdp.awaitResponse(30)
        assertTrue(
          possible.contains("\"scriptId\":\"$scriptId\""),
          "getPossibleBreakpoints should return locations: $possible",
        )

        // Modern DevTools loads source maps through the target via
        // Network.loadNetworkResource + IO.read/IO.close.
        cdp.send(4, "Network.loadNetworkResource", """"params":{"url":"$SERVED_SCRIPT_URL.map"}""")
        val resourceResponse = cdp.awaitResponse(4)
        assertTrue(
          resourceResponse.contains("\"stream\""),
          "loadNetworkResource should return a stream: $resourceResponse",
        )
        val handle = Regex(""""stream":"([^"]+)"""").find(resourceResponse)!!.groupValues[1]
        cdp.send(5, "IO.read", """"params":{"handle":"$handle","size":5}""")
        val read1 = cdp.awaitResponse(5)
        assertTrue(read1.contains("ver"), "first chunk: $read1")
        cdp.send(6, "IO.read", """"params":{"handle":"$handle"}""")
        val read2 = cdp.awaitResponse(6)
        assertTrue(read2.contains("sion"), "rest of stream: $read2")
        cdp.send(7, "IO.read", """"params":{"handle":"$handle"}""")
        val read3 = cdp.awaitResponse(7)
        assertTrue(read3.contains("\"eof\":true"), "eof expected: $read3")
        cdp.send(8, "IO.close", """"params":{"handle":"$handle"}""")
        cdp.awaitResponse(8)
      } finally {
        httpd.stop()
      }
    }
  }

  /** A one-shot HTTP server answering GETs from [paths] (path -> body). */
  private class MiniHttpServer(port: Int, private val paths: Map<String, String>) {
    private val serverSocket = DebugServerSocket(port)
    private val running = AtomicBoolean(true)

    fun start() {
      startDebugThread("CdpTest-httpd") {
        while (running.load()) {
          val client = try {
            serverSocket.accept()
          } catch (_: IOException) {
            break
          }
          try {
            val request = WebSocketProtocol.readHttpRequest(client)
            val path = request?.path?.substringBefore('?') ?: "/"
            val body = paths[path]
            if (body == null) {
              client.write("HTTP/1.1 404 NF\r\nContent-Length: 0\r\n\r\n".encodeToByteArray())
            } else {
              val bodyBytes = body.encodeToByteArray()
              client.write(
                ("HTTP/1.1 200 OK\r\nContent-Length: ${bodyBytes.size}\r\n\r\n".encodeToByteArray() + bodyBytes),
              )
            }
          } catch (_: IOException) {
          } finally {
            client.closeQuietly()
          }
        }
      }
    }

    fun stop() {
      running.store(false)
      try {
        serverSocket.close()
      } catch (_: Throwable) {
      }
    }
  }

  private fun evaluateSupported(jsEngine: JsEngine): Boolean {
    return try {
      jsEngine.evaluate("1", "probe.js")
      true
    } catch (_: UnsupportedOperationException) {
      false
    }
  }

  private fun discoverSessionId(): String {
    val body = httpGet("http://localhost:$PORT/json/list", 5000, 5000)
    assertNotNull(body, "no response from /json/list")
    val match = Regex(""""id":"(\d+)"""").find(body)
    assertNotNull(match, "no debug target in /json/list: $body")
    return match.groupValues[1]
  }

  /** A minimal CDP WebSocket client: unmasked client frames (accepted by our server). */
  private class CdpClient(sessionId: String) : AutoCloseable {
    private val socket = connectDebugSocket("127.0.0.1", PORT)
    private val messages = Channel<String>(Channel.UNLIMITED)

    init {
      val request = buildString {
        append("GET /devtools/page/$sessionId HTTP/1.1\r\n")
        append("Host: localhost:$PORT\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
        append("\r\n")
      }
      socket.write(request.encodeToByteArray())
      val statusLine = readHttpLine()
      assertTrue(statusLine.contains("101"), "WebSocket upgrade failed: $statusLine")
      while (readHttpLine().isNotEmpty()) Unit // Skip headers.

      startDebugThread("CdpTest-reader") {
        try {
          while (true) {
            messages.trySend(readFrame(socket))
          }
        } catch (_: Throwable) {
          // Closed.
        }
      }
    }

    fun send(id: Int, method: String, extra: String = "") {
      val json = buildString {
        append("""{"id":""").append(id).append(""","method":""").append('"').append(method).append('"')
        if (extra.isNotEmpty()) append(',').append(extra)
        append('}')
      }
      WebSocketProtocol.sendText(socket, json)
    }

    suspend fun awaitResponse(id: Int): String = awaitMessage(""""id":$id""")

    suspend fun awaitEvent(method: String): String = awaitMessage(""""method":"$method"""")

    suspend fun awaitEventContaining(marker: String): String = awaitMessage(marker)

    private val stash = mutableListOf<String>()

    private suspend fun awaitMessage(marker: String): String {
      stash.indexOfFirst { it.contains(marker) }.let { index ->
        if (index != -1) return stash.removeAt(index)
      }
      val deadline = TimeSource.Monotonic.markNow() + kotlin.time.Duration.parse("30s")
      while (deadline.hasNotPassedNow()) {
        val message = messages.tryReceive().getOrNull()
        if (message == null) {
          delay(5)
          continue
        }
        if (message.contains(marker)) return message
        stash.add(message)
      }
      throw AssertionError("timed out waiting for $marker")
    }

    private fun readHttpLine(): String {
      val line = StringBuilder()
      while (true) {
        val b = socket.read()
        if (b == -1) throw EOFException()
        if (b == '\n'.code) return line.toString().trimEnd('\r')
        line.append(b.toChar())
      }
    }

    override fun close() {
      socket.closeQuietly()
    }
  }

  private companion object {
    const val PORT = 9229
    const val SCRIPT_URL = "http://localhost:8080/test.js"
    const val SOURCE_SERVER_PORT = 18080
    const val SERVED_SCRIPT_URL = "http://localhost:18080/test.js"

    /**
     * Hermes bytecode for the following script, compiled with debug info and an
     * embedded source map ("sources":["test.kt"]):
     * ```
     * function hitMe() {
     *   var x = 41;
     *   return x + 1;
     * }
     * ```
     * (Produced by JsEngine.compile on the JVM; regenerate if the bytecode format
     * version changes.)
     */
    val testScriptBytecode: ByteArray = Base64.decode(
      "xh+8A8EDGR9iAAAA7ZXVKt96I1Xh+uQG9lBsQ3SUO+LoAQAAAAAAAAIAAAACAAAAAQAAAAIAAAAA" +
        "AAAACwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWAEAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAACAsAQAAAAAAAAAAACABAAAAAQAAgKudwlUAAAAGDAAA" +
        "BWdsb2JhbGhpdE1lAEECAAAAAEMBAAAAhAECAQA9A0oDAQABAJMDEAEDfnYBNAIAQgICAQAAAJMB" +
        "OQIAAYsBKTkCAAE7AQIAiwMBHgEBA352AQAAALgAAAABAAAAAAAAACAAAAAAAAAAAAAAAAAAAAAE" +
        "AAAAAAEAABIAAAAAAAAA2AAAAAEAAAAAAAAAJQAAAAEAAAAAAAAAAAAAAAQAAAAAAAAAEgAAAB0A" +
        "AAABAAAAHQAAAAEAAAA7AAAAAAAAAB0AAABodHRwOi8vbG9jYWxob3N0OjgwODAvdGVzdC5qcwAA" +
        "AAAAAAAAAAAAAAABAQEABQB/BgUAAQUBAAcBAAYBAAUDAAEBGQB/AQEBAQAFAH8DAQAJBQABBwsI" +
        "AwQLAQEHAQAFAXl/n+gULpM08EWmmsNelOoeDu5dnxE=",
    )

    /** Same script as [testScriptBytecode] but with [SERVED_SCRIPT_URL] baked in. */
    val servedScriptBytecode: ByteArray = Base64.decode(
      "xh+8A8EDGR9iAAAA7ZXVKt96I1Xh+uQG9lBsQ3SUO+LpAQAAAAAAAAIAAAACAAAAAQAAAAIAAAAA" +
        "AAAACwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWAEAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAACAsAQAAAAAAAAAAACABAAAAAQAAgKudwlUAAAAGDAAA" +
        "BWdsb2JhbGhpdE1lAEECAAAAAEMBAAAAhAECAQA9A0oDAQABAJMDEAEDfnYBNAIAQgICAQAAAJMB" +
        "OQIAAYsBKTkCAAE7AQIAiwMBHgEBA352AQAAALgAAAABAAAAAAAAACAAAAAAAAAAAAAAAAAAAAAE" +
        "AAAAAAEAABIAAAAAAAAA2AAAAAEAAAAAAAAAJQAAAAEAAAAAAAAAAAAAAAQAAAAAAAAAEgAAAB0A" +
        "AAABAAAAHgAAAAEAAAA7AAAAAAAAAB4AAABodHRwOi8vbG9jYWxob3N0OjE4MDgwL3Rlc3QuanMA" +
        "AAAAAAAAAAAAAAAAAQEBAAUAfwYFAAEFAQAHAQAGAQAFAwABARkAfwEBAQEABQB/AwEACQUAAQcL" +
        "CAMECwEBBwEABQF5f2L2vItC2AqOU9zbjfS6m9ONKbEU",
    )

    /** Reads one server-to-client (unmasked) WebSocket text frame. */
    fun readFrame(socket: DebugSocket): String {
      val b0 = socket.read()
      if (b0 == -1) throw EOFException()
      val b1 = socket.read()
      if (b1 == -1) throw EOFException()
      var length = (b1 and 0x7F).toLong()
      if (length == 126L) {
        length = (socket.read().toLong() shl 8) or socket.read().toLong()
      } else if (length == 127L) {
        length = 0
        for (i in 0 until 8) length = (length shl 8) or socket.read().toLong()
      }
      val payload = ByteArray(length.toInt())
      var offset = 0
      while (offset < payload.size) {
        val read = socket.readInto(payload, offset, payload.size - offset)
        if (read == -1) throw EOFException()
        offset += read
      }
      return payload.decodeToString()
    }
  }
}
