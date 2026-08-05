package app.cash.zipline

import java.io.EOFException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking

class SourceMapUrlProbeTest {
  private val executor = Executors.newSingleThreadExecutor()
  private val dispatcher = executor.asCoroutineDispatcher()
  private var zipline: Zipline? = null

  @BeforeTest
  fun setUp() {
    System.setProperty("app.cash.zipline.cdp.port", PORT.toString())
  }

  @AfterTest
  fun tearDown() {
    System.clearProperty("app.cash.zipline.cdp.port")
    zipline?.close()
    zipline = null
  }

  @Test
  fun scriptParsedCarriesSourceMapUrl() = runBlocking<Unit> {
    val zipline = Zipline.create(dispatcher)
    this@SourceMapUrlProbeTest.zipline = zipline

    val sessionId = discoverSessionId()
    CdpClient(sessionId).use { cdp ->
      cdp.send(1, "Runtime.enable")
      cdp.send(2, "Debugger.enable")
      cdp.awaitResponse(2)

      zipline.loadJsModule(TEST_JS, SCRIPT_URL)

      val scriptParsed = cdp.await("""scriptParsed"""", """probe.js""")
      println("scriptParsed: $scriptParsed")
      assertTrue(scriptParsed.contains("probe.js"), scriptParsed)
      assertTrue(
        scriptParsed.contains("sourceMapURL"),
        "scriptParsed should announce sourceMapURL: $scriptParsed",
      )

      // Breakpoint on `return x + 1;` (0-based line 2), after x is assigned.
      cdp.send(
        3, "Debugger.setBreakpointByUrl",
        """"params":{"url":"$SCRIPT_URL","lineNumber":2}""",
      )
      val setBp = cdp.awaitResponse(3)
      assertTrue(setBp.contains("\"locations\":[{"), "breakpoint should bind: $setBp")

      // Trigger the function; execution pauses at the breakpoint.
      cdp.send(4, "Runtime.evaluate", """"params":{"expression":"probeMe()"}""")
      val paused = cdp.await("""paused"""")
      assertTrue(paused.contains("probeMe"), paused)

      // Frame eval requires the in-memory scoping table; constant folding
      // aside, `x` must resolve and `1+2` must compute.
      cdp.send(
        5, "Debugger.evaluateOnCallFrame",
        """"params":{"callFrameId":"0","expression":"x"}""",
      )
      val evalX = cdp.awaitResponse(5)
      assertTrue(evalX.contains("\"value\":41"), "frame eval of x: $evalX")

      cdp.send(
        6, "Debugger.evaluateOnCallFrame",
        """"params":{"callFrameId":"0","expression":"1+2"}""",
      )
      val evalConst = cdp.awaitResponse(6)
      assertTrue(evalConst.contains("\"value\":3"), "frame eval of 1+2: $evalConst")

      cdp.send(7, "Debugger.resume")
      cdp.awaitResponse(7)
      cdp.awaitResponse(4)
    }
  }

  private fun discoverSessionId(): String {
    val url = URL("http://127.0.0.1:$PORT/json/list")
    val deadline = System.currentTimeMillis() + 10_000
    while (true) {
      try {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 1000
        conn.readTimeout = 1000
        val body = conn.inputStream.bufferedReader().readText()
        val match = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(body)
        if (match != null) return match.groupValues[1]
      } catch (e: Exception) {
        if (System.currentTimeMillis() > deadline) throw e
        Thread.sleep(200)
      }
    }
  }

  private class CdpClient(sessionId: String) : AutoCloseable {
    private val socket = Socket("127.0.0.1", PORT)
    private val out = socket.getOutputStream()
    private val debugSocket = app.cash.zipline.internal.cdp.DebugSocket(socket)
    private val incoming = ArrayBlockingQueue<String>(100)

    init {
      val key = java.util.Base64.getEncoder().encodeToString(ByteArray(16) { 1 })
      val request = buildString {
        append("GET /devtools/page/$sessionId HTTP/1.1\r\n")
        append("Host: 127.0.0.1:$PORT\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Key: $key\r\n")
        append("Sec-WebSocket-Version: 13\r\n")
        append("\r\n")
      }
      out.write(request.toByteArray())
      out.flush()
      val headers = readHttpHeaders(socket.getInputStream())
      check(headers.first().contains("101")) { "WebSocket upgrade failed: ${headers.first()}" }

      Thread {
        try {
          while (true) {
            incoming.put(readFrame(socket.getInputStream()))
          }
        } catch (e: EOFException) {
        } catch (e: Exception) {
        }
      }.apply { isDaemon = true }.start()
    }

    fun send(id: Int, method: String, extra: String = "") {
      val json = if (extra.isEmpty()) {
        """{"id":$id,"method":"$method"}"""
      } else {
        """{"id":$id,"method":"$method",$extra}"""
      }
      synchronized(out) {
        app.cash.zipline.internal.cdp.WebSocketProtocol.sendText(debugSocket, json)
      }
    }

    fun awaitResponse(id: Int): String = await("\"id\":$id")

    fun awaitEvent(method: String): String = await("\"method\":\"$method\"")

    fun await(marker: String, andAlso: String? = null): String {
      val deadline = System.currentTimeMillis() + 15_000
      val stash = mutableListOf<String>()
      try {
        while (System.currentTimeMillis() < deadline) {
          val msg = incoming.poll(500, TimeUnit.MILLISECONDS) ?: continue
          stash += msg
          if (msg.contains(marker) && (andAlso == null || msg.contains(andAlso))) return msg
        }
      } finally {
        incoming.addAll(stash)
      }
      throw IllegalStateException("timeout waiting for $marker; saw: $stash")
    }

    override fun close() {
      socket.close()
    }

    private fun readHttpHeaders(input: InputStream): List<String> {
      val lines = mutableListOf<String>()
      val current = StringBuilder()
      while (true) {
        val b = input.read()
        if (b == -1) throw EOFException()
        if (b == '\n'.code) {
          val line = current.toString().trimEnd('\r')
          current.clear()
          if (line.isEmpty()) return lines
          lines += line
        } else {
          current.append(b.toChar())
        }
      }
    }

    private fun readFrame(input: InputStream): String {
      val b0 = input.read()
      if (b0 == -1) throw EOFException()
      val b1 = input.read()
      if (b1 == -1) throw EOFException()
      var length = (b1 and 0x7F).toLong()
      if (length == 126L) {
        length = (input.read().toLong() shl 8) or input.read().toLong()
      } else if (length == 127L) {
        length = 0
        for (i in 0 until 8) length = (length shl 8) or input.read().toLong()
      }
      val payload = ByteArray(length.toInt())
      var offset = 0
      while (offset < payload.size) {
        val read = input.read(payload, offset, payload.size - offset)
        if (read == -1) throw EOFException()
        offset += read
      }
      return String(payload, Charsets.UTF_8)
    }
  }

  companion object {
    // Must match CdpDebugTest.PORT: CdpDebugSupport keeps one process-wide
    // server singleton, so all tests in a shared JVM must use the same port.
    private const val PORT = 9229
    private const val SCRIPT_URL = "http://localhost:8080/probe.js"
    private val TEST_JS = """
      function probeMe() {
        var x = 41;
        return x + 1;
      }
      probeMe();
      //# sourceMappingURL=probe.js.map
      //# sourceURL=http://localhost:8080/probe.js
    """.trimIndent()
  }
}
