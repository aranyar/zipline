package app.cash.zipline.internal.cdp

import app.cash.zipline.CdpListener
import app.cash.zipline.JsEngine
import app.cash.zipline.internal.log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Exposes a running [JsEngine] to Chrome DevTools via the Chrome DevTools Protocol (CDP).
 *
 * The server speaks enough HTTP for Chrome's target discovery (`/json`, `/json/list`,
 * `/json/version`) and upgrades `/devtools/page/<id>` to a WebSocket that carries CDP messages
 * into the Hermes CDP agent. Typical usage on Android:
 *
 * ```
 * System.setProperty("app.cash.zipline.cdp.port", "9222") // before Zipline starts
 * // on the host machine:
 * adb forward tcp:9222 tcp:9222
 * // then open chrome://inspect, or Chrome with the devtoolsFrontendUrl from /json/list
 * ```
 */
internal class CdpDebugServer(
  private val port: Int,
) {
  private val sessions = CopyOnWriteArrayList<DebugSession>()
  private var serverSocket: ServerSocket? = null

  fun start() {
    val socket = ServerSocket(port)
    serverSocket = socket
    val thread = Thread(
      {
        log("info", "Zipline CDP debug server listening on port $port", null)
        while (true) {
          val client = try {
            socket.accept()
          } catch (e: IOException) {
            break // Server socket closed.
          }
          Thread({ handleConnection(client) }, "ZiplineCdp-client").apply { isDaemon = true }.start()
        }
      },
      "ZiplineCdp-accept",
    )
    thread.isDaemon = true
    thread.start()
  }

  fun attach(jsEngine: JsEngine, scope: CoroutineScope) {
    // Assign the smallest free id so the usual single-engine flow keeps a
    // stable "1" across hot-reloads (DevTools URLs stay valid).
    val id = generateSequence(1L) { it + 1 }
      .map { it.toString() }
      .first { candidate -> sessions.none { it.id == candidate } }
    val session = DebugSession(id, jsEngine, scope)
    if (!jsEngine.cdpAttach(session.listener)) {
      log("warn", "Zipline CDP: engine does not support debugging (HERMES_ENABLE_DEBUGGER off?)", null)
      return
    }
    sessions.add(session)
    log("info", "Zipline CDP: debug session ${session.id} attached (port $port)", null)
  }

  fun detach(jsEngine: JsEngine) {
    val session = sessions.firstOrNull { it.jsEngine === jsEngine } ?: return
    sessions.remove(session)
    session.close()
  }

  private fun handleConnection(socket: Socket) {
    try {
      socket.tcpNoDelay = true
      val input = socket.getInputStream()
      val output = socket.getOutputStream()
      val request = WebSocketProtocol.readHttpRequest(input) ?: return socket.closeQuietly()
      val path = request.path.substringBefore('?')
      val host = request.headers["host"] ?: "localhost:$port"

      when {
        path == "/json/version" -> WebSocketProtocol.writeHttpResponse(
          output, 200, "OK",
          """{"Browser":"Zipline/Hermes","Protocol-Version":"1.3",""" +
            """"webSocketDebuggerUrl":"ws://$host/devtools/page/${sessions.firstOrNull()?.id ?: ""}"}""",
        ).also { socket.closeQuietly() }

        path == "/json" || path == "/json/list" -> WebSocketProtocol.writeHttpResponse(
          output, 200, "OK", targetsJson(host),
        ).also { socket.closeQuietly() }

        path.startsWith("/devtools/page/") -> {
          val id = path.removePrefix("/devtools/page/")
          val session = sessions.firstOrNull { it.id == id }
          val key = request.headers["sec-websocket-key"]
          val upgrade = request.headers["upgrade"]?.lowercase()
          if (session == null || key == null || upgrade != "websocket") {
            WebSocketProtocol.writeHttpResponse(output, 404, "Not Found", "[]")
            socket.closeQuietly()
            return
          }
          WebSocketProtocol.writeWebSocketUpgrade(output, key)
          session.addClient(output, socket)
          try {
            WebSocketProtocol.readFrames(input, output) { text ->
              session.onCdpMessage(text)
            }
          } finally {
            session.removeClient(output)
            socket.closeQuietly()
          }
        }

        else -> {
          WebSocketProtocol.writeHttpResponse(output, 404, "Not Found", "[]")
          socket.closeQuietly()
        }
      }
    } catch (_: IOException) {
      socket.closeQuietly()
    } catch (t: Throwable) {
      log("warn", "Zipline CDP: connection failed: ${t.message}", t)
      socket.closeQuietly()
    }
  }

  private fun targetsJson(host: String): String {
    // Point at Chrome's bundled DevTools frontend (chrome://inspect opens this
    // for discovered targets). The RN fusebox frontend remains available from
    // the dev server at /debugger-frontend/rn_fusebox.html?ws=<host>/...
    val entries = sessions.map { session ->
      val wsUrl = "ws://$host/devtools/page/${session.id}"
      val frontendUrl = "devtools://devtools/bundled/inspector.html" +
        "?ws=$host/devtools/page/${session.id}"
      """{"description":"Hermes JS engine",""" +
        """"devtoolsFrontendUrl":"$frontendUrl",""" +
        """"id":"${session.id}","title":"Zipline (Hermes)","type":"node",""" +
        """"vm":"Hermes","webSocketDebuggerUrl":"$wsUrl"}"""
    }
    return entries.joinToString(prefix = "[", postfix = "]")
  }

  internal inner class DebugSession(
    val id: String,
    val jsEngine: JsEngine,
    private val scope: CoroutineScope,
  ) {
    private val clients = CopyOnWriteArrayList<java.io.OutputStream>()
    private val clientSockets = java.util.concurrent.ConcurrentHashMap<java.io.OutputStream, Socket>()
    private val drainScheduled = AtomicBoolean(false)

    /** scriptId -> script URL, observed from Debugger.scriptParsed events. */
    private val scriptUrls = ConcurrentHashMap<String, String>()

    /** Open IO streams for the Network.loadNetworkResource + IO.read protocol. */
    private val ioStreams = ConcurrentHashMap<String, IoStream>()
    private val nextStreamId = java.util.concurrent.atomic.AtomicLong(1)
    private val json = Json { ignoreUnknownKeys = true }

    /** Ids of Debugger.enable requests whose responses need a debuggerId injected. */
    private val pendingDebuggerEnableIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /**
     * Ids of Runtime.enable requests; the execution context must be (re-)announced
     * after their responses, because frontends discard executionContextCreated
     * events received while the Runtime domain is disabled.
     */
    private val pendingRuntimeEnableIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    val listener = object : CdpListener {
      override fun onMessage(json: String) {
        // DevTools persists breakpoints per URL across windows and engine reloads,
        // so it may try to remove ids the current agent doesn't know. The Hermes
        // agent answers those with an "Unknown breakpoint ID" error and the
        // frontend then keeps showing the breakpoint forever. Make removal
        // idempotent: swallow that specific error so the frontend forgets it.
        var out = if (json.contains("Unknown breakpoint ID")) {
          val id = this@DebugSession.json.parseToJsonElement(json).asObject()?.get("id").asString()
          buildJsonObject {
            put("id", id?.toLongOrNull() ?: 0L)
            putJsonObject("result") {}
          }.toString().also {
            log("info", "Zipline CDP: forgiving removal of unknown breakpoint (id=$id)", null)
          }
        } else {
          json
        }
        // The Hermes agent answers Debugger.enable with an empty result, but V8
        // returns a unique debuggerId and stock Chrome DevTools' breakpoint
        // model depends on it (without it, gutter toggles are no-ops).
        val responseId = RUNTIME_RESPONSE_ID.find(out)?.groupValues?.get(1)?.toLongOrNull()
        if (responseId != null && pendingDebuggerEnableIds.remove(responseId) &&
          !out.contains("debuggerId")
        ) {
          out = """{"id":$responseId,"result":{"debuggerId":"$DEBUGGER_ID"}}"""
        }
        log("info", "Zipline CDP: => ${out.take(MAX_LOG_CHARS)}", null)
        recordScriptParsed(out)
        val reannounceContext = responseId != null && pendingRuntimeEnableIds.remove(responseId)
        for (client in clients) {
          try {
            WebSocketProtocol.sendText(client, out)
            if (reannounceContext) {
              WebSocketProtocol.sendText(client, EXECUTION_CONTEXT_CREATED)
            }
          } catch (t: Throwable) {
            // A dead client must not starve the others.
            log("warn", "Zipline CDP: dropping client: ${t.message}", null)
            clients.remove(client)
          }
        }
      }

      override fun onTasksEnqueued() {
        scheduleDrain()
      }
    }

    /**
     * DevTools loads script content exclusively via Debugger.getScriptSource, which the Hermes
     * CDP agent does not implement. Answer it here by fetching the script's URL from the
     * Zipline development server (like React Native's metro inspector proxy does).
     */
    fun onCdpMessage(jsonText: String) {
      log("info", "Zipline CDP: <= ${jsonText.take(MAX_LOG_CHARS)}", null)
      val message = try {
        json.parseToJsonElement(jsonText).asObject()
      } catch (t: Throwable) {
        // A malformed message must not kill the connection.
        log("warn", "Zipline CDP: unparseable message: ${t.message}", null)
        null
      }
      val method = message?.get("method").asString()
      if (method == "Debugger.enable") {
        message?.get("id").asString()?.toLongOrNull()?.let(pendingDebuggerEnableIds::add)
      }
      if (method == "Runtime.enable") {
        message?.get("id").asString()?.toLongOrNull()?.let(pendingRuntimeEnableIds::add)
      }
      if (method == "Debugger.getScriptSource") {
        val requestId = message?.get("id").asString()
        val scriptId = message?.get("params").asObject()?.get("scriptId").asString()
        val url = scriptId?.let(scriptUrls::get)
        if (requestId != null && url != null) {
          serveScriptSource(requestId, url)
          return
        }
      }
      // Current DevTools uses getPossibleBreakpoints for inline (column-level)
      // breakpoints, which the Hermes CDP agent does not implement. Compute the
      // locations from the engine's debug line table on the JS thread.
      if (method == "Debugger.getPossibleBreakpoints") {
        val requestId = message?.get("id").asString()
        val params = message?.get("params").asObject()
        val start = params?.get("start").asObject()
        val end = params?.get("end").asObject()
        val scriptId = start?.get("scriptId").asString()?.toIntOrNull()
        if (requestId != null && scriptId != null) {
          servePossibleBreakpoints(
            requestId,
            scriptId,
            start?.get("lineNumber").asString()?.toIntOrNull() ?: 0,
            start?.get("columnNumber").asString()?.toIntOrNull() ?: 0,
            end?.get("lineNumber").asString()?.toIntOrNull() ?: -1,
            end?.get("columnNumber").asString()?.toIntOrNull() ?: -1,
          )
          return
        }
      }
      // Modern DevTools loads source maps (and other resources) through the target
      // via Network.loadNetworkResource + IO.read, which the Hermes CDP agent does
      // not implement. Answer it here, again fetching from the Zipline dev server.
      if (method == "Network.loadNetworkResource") {
        val requestId = message?.get("id").asString()
        val url = message?.get("params").asObject()?.get("url").asString()
        if (requestId != null && url != null) {
          serveNetworkResource(requestId, url)
          return
        }
      }
      if (method == "IO.read") {
        val requestId = message?.get("id").asString()
        val params = message?.get("params").asObject()
        val handle = params?.get("handle").asString()
        if (requestId != null && handle != null) {
          serveIoRead(requestId, handle, params?.get("offset").asString()?.toIntOrNull(), params?.get("size").asString()?.toIntOrNull())
          return
        }
      }
      if (method == "IO.close") {
        val requestId = message?.get("id").asString()
        val handle = message?.get("params").asObject()?.get("handle").asString()
        if (requestId != null && handle != null) {
          ioStreams.remove(handle)
          sendToClients(buildJsonObject {
            put("id", requestId.toLongOrNull() ?: 0L)
            putJsonObject("result") {}
          }.toString())
          return
        }
      }
      jsEngine.cdpHandleCommand(jsonText)
      // handleCommand may enqueue runtime tasks synchronously; make sure they run even if the
      // onTasksEnqueued notification raced with a drain already in flight.
      scheduleDrain()
    }

    private fun recordScriptParsed(jsonText: String) {
      val params = json.parseToJsonElement(jsonText).asObject()
        ?.takeIf { it["method"].asString() == "Debugger.scriptParsed" }
        ?.get("params").asObject() ?: return
      val scriptId = params["scriptId"].asString() ?: return
      val url = params["url"].asString() ?: return
      if (url.isNotEmpty()) {
        scriptUrls[scriptId] = url
      }
    }

    private fun serveScriptSource(requestId: String, url: String) {
      // Fetch off the WebSocket reader thread; reply directly to the clients.
      Thread {
        val source = fetchScriptSource(url)
        val response = if (source != null) {
          buildJsonObject {
            put("id", requestId.toLongOrNull() ?: 0L)
            putJsonObject("result") { put("scriptSource", source) }
          }.toString()
        } else {
          buildJsonObject {
            put("id", requestId.toLongOrNull() ?: 0L)
            putJsonObject("error") {
              put("code", -32000)
              put("message", "Could not fetch $url from the Zipline dev server")
            }
          }.toString()
        }
        sendToClients(response)
      }.apply {
        isDaemon = true
        name = "ZiplineCdp-source"
        start()
      }
    }

    private fun servePossibleBreakpoints(
      requestId: String,
      scriptId: Int,
      startLine: Int,
      startCol: Int,
      endLine: Int,
      endCol: Int,
    ) {
      Thread {
        val locations = possibleBreakpoints(scriptId, startLine, startCol, endLine, endCol)
        sendToClients(buildJsonObject {
          put("id", requestId.toLongOrNull() ?: 0L)
          putJsonObject("result") {
            put("locations", kotlinx.serialization.json.JsonArray(locations))
          }
        }.toString())
      }.apply {
        isDaemon = true
        name = "ZiplineCdp-possiblebp"
        start()
      }
    }

    /**
     * Possible breakpoint positions for inline (column-level) breakpoints, computed from the
     * script's served source map (each generated segment is a statement position candidate).
     * The frontend re-resolves candidates via setBreakpointByUrl anyway, so map segments are
     * a good approximation of the engine's debug line table.
     */
    private fun possibleBreakpoints(
      scriptId: Int,
      startLine: Int,
      startCol: Int,
      endLine: Int,
      endCol: Int,
    ): List<kotlinx.serialization.json.JsonElement> {
      val url = scriptUrls[scriptId.toString()] ?: return emptyList()
      val positions = sourceMapPositions(url)
      val inRange = positions.filter { (line, col) ->
        (line > startLine || (line == startLine && col >= startCol)) &&
          (endLine < 0 || line < endLine || (line == endLine && col < endCol))
      }
      return inRange.map { (line, col) ->
        buildJsonObject {
          put("scriptId", scriptId.toString())
          put("lineNumber", line)
          put("columnNumber", col)
        }
      }
    }

    private val sourceMapCache = ConcurrentHashMap<String, List<Pair<Int, Int>>>()

    private fun sourceMapPositions(scriptUrl: String): List<Pair<Int, Int>> {
      return sourceMapCache.getOrPut(scriptUrl) {
        val mapText = fetchScriptSource("$scriptUrl.map") ?: return@getOrPut emptyList()
        val mapJson = try {
          json.parseToJsonElement(mapText).asObject()
        } catch (_: Exception) {
          null
        } ?: return@getOrPut emptyList()
        val mappings = mapJson["mappings"].asString() ?: return@getOrPut emptyList()
        val result = ArrayList<Pair<Int, Int>>()
        var line = 0
        for (lineMappings in mappings.split(';')) {
          if (lineMappings.isNotEmpty()) {
            var column = 0
            for (segment in lineMappings.split(',')) {
              if (segment.isEmpty()) continue
              val fields = decodeVlq(segment)
              if (fields.isNotEmpty()) {
                column += fields[0]
                result.add(line to column)
              }
            }
          }
          line += 1
        }
        result
      }
    }

    /** Decodes one base64-VLQ segment into signed delta values. */
    private fun decodeVlq(segment: String): IntArray {
      val values = IntArray(5)
      var count = 0
      var shift = 0
      var value = 0
      for (c in segment) {
        val digit = if (c.code < VLQ_DIGITS.size) VLQ_DIGITS[c.code] else -1
        if (digit < 0) return values.copyOf(count)
        value = value or ((digit and 0x1F) shl shift)
        if (digit and 0x20 == 0) {
          values[count++] = if (value and 1 == 1) -(value ushr 1) else value ushr 1
          shift = 0
          value = 0
          if (count == values.size) return values
        } else {
          shift += 5
        }
      }
      return values.copyOf(count)
    }

    private fun serveNetworkResource(requestId: String, url: String) {
      Thread {
        val content = fetchScriptSource(url)
        val response = buildJsonObject {
          put("id", requestId.toLongOrNull() ?: 0L)
          putJsonObject("result") {
            putJsonObject("resource") {
              put("url", url)
              if (content != null) {
                val handle = "zipline-stream-${nextStreamId.getAndIncrement()}"
                ioStreams[handle] = IoStream(content)
                put("success", true)
                put("httpStatusCode", 200)
                put("stream", handle)
              } else {
                put("success", false)
                put("httpStatusCode", 0)
                put("netError", -2)
                put("netErrorName", "net::ERR_FAILED")
              }
            }
          }
        }.toString()
        sendToClients(response)
      }.apply {
        isDaemon = true
        name = "ZiplineCdp-resource"
        start()
      }
    }

    private fun serveIoRead(requestId: String, handle: String, offset: Int?, size: Int?) {
      val stream = ioStreams[handle]
      val response = buildJsonObject {
        put("id", requestId.toLongOrNull() ?: 0L)
        if (stream == null) {
          putJsonObject("error") {
            put("code", -32000)
            put("message", "Invalid stream handle")
          }
        } else {
          val from = offset ?: stream.position
          val limit = size ?: Int.MAX_VALUE
          val available = (stream.content.length - from).coerceAtLeast(0)
          val end = from + minOf(available, limit)
          val chunk = stream.content.substring(from, end)
          // Random-access reads (explicit offset) don't advance the stream.
          if (offset == null) stream.position = end
          putJsonObject("result") {
            put("data", chunk)
            // DevTools discards data when eof is true, so only report eof on an
            // empty read after all content has been delivered.
            put("eof", chunk.isEmpty())
          }
        }
      }.toString()
      sendToClients(response)
    }

    private fun sendToClients(json: String) {
      for (client in clients) {
        try {
          WebSocketProtocol.sendText(client, json)
        } catch (_: IOException) {
          clients.remove(client)
        }
      }
    }

    /** Bounds concurrent fetches to the dev server (bursts exhaust the HTTP keep-alive pool). */
    private val fetchPermits = java.util.concurrent.Semaphore(4)

    private fun fetchScriptSource(url: String): String? {
      // The URL points at the dev server on the host machine. Best path is the
      // adb reverse tunnel (dev server sets it up): the device's 127.0.0.1 then
      // reaches the host. Note we must use the IP literal — some emulators can't
      // even resolve "localhost" (UnknownHostException). Fallback: the emulator
      // NAT alias 10.0.2.2. DevTools does not retry failed loads, so each
      // candidate gets two attempts.
      val candidates = buildList {
        add(url.replace("://localhost:", "://127.0.0.1:"))
        add(url)
        add(url.replace("://localhost:", "://10.0.2.2:").replace("://127.0.0.1:", "://10.0.2.2:"))
      }.distinct()
      fetchPermits.acquire()
      try {
        for (candidate in candidates) {
          repeat(2) {
            try {
              val connection = URL(candidate).openConnection() as HttpURLConnection
              connection.connectTimeout = 5000
              connection.readTimeout = 15000
              connection.setRequestProperty("Connection", "close")
              try {
                if (connection.responseCode == 200) {
                  return connection.inputStream.bufferedReader().use { it.readText() }
                }
              } finally {
                connection.disconnect()
              }
            } catch (t: IOException) {
              // Try again, then fall through to the next candidate.
              log("warn", "Zipline CDP: fetch attempt failed for $candidate: ${t.javaClass.simpleName}: ${t.message}", null)
            }
          }
        }
      } finally {
        fetchPermits.release()
      }
      log("warn", "Zipline CDP: unable to fetch script source: $url", null)
      return null
    }

    private fun scheduleDrain() {
      if (drainScheduled.compareAndSet(false, true)) {
        scope.launch {
          drainScheduled.set(false)
          try {
            jsEngine.cdpDrainTasks()
          } catch (t: Throwable) {
            log("warn", "Zipline CDP: task drain failed: ${t.message}", t)
          }
        }
      }
    }

    fun addClient(output: java.io.OutputStream, socket: Socket) {
      // Single-debugger semantics: a new DevTools client replaces any previous
      // one. Sharing one agent across clients cross-talks responses and events
      // (each frontend sees the other's traffic), which breaks client-side
      // state like the breakpoint model.
      if (clients.isNotEmpty()) {
        for (old in clients) {
          try {
            WebSocketProtocol.sendClose(old)
          } catch (_: IOException) {
          }
          clientSockets.remove(old)?.closeQuietly()
        }
        clients.clear()
        // Fresh agent so the new client re-receives scriptParsed events when
        // it enables the Debugger domain (breakpoint state is preserved).
        jsEngine.cdpResetAgent()
      }
      clients.add(output)
      clientSockets[output] = socket
      // The Hermes CDP agent never reports its execution context, and without
      // one the DevTools console has nowhere to evaluate (input silently
      // vanishes). Announce the default context to the new client. Stock Chrome
      // DevTools discards it (Runtime domain not yet enabled) and gets a second
      // announcement after its Runtime.enable instead.
      sendSafe(output, EXECUTION_CONTEXT_CREATED)
    }

    fun removeClient(output: java.io.OutputStream) {
      clients.remove(output)
      clientSockets.remove(output)
      if (clients.isEmpty()) {
        // The next client gets a fresh agent so it re-receives scriptParsed
        // events when it enables the Debugger domain (breakpoint state is
        // preserved across the reset).
        jsEngine.cdpResetAgent()
      }
    }

    private fun sendSafe(output: java.io.OutputStream, json: String) {
      try {
        WebSocketProtocol.sendText(output, json)
      } catch (_: IOException) {
        clients.remove(output)
      }
    }

    fun close() {
      for (client in clients) {
        try {
          WebSocketProtocol.sendClose(client)
        } catch (_: IOException) {
        }
        clientSockets.remove(client)?.closeQuietly()
      }
      clients.clear()
    }
  }

  companion object {
    /** Cap CDP traffic logging so big payloads (script sources) don't flood logcat. */
    private const val MAX_LOG_CHARS = 400

    /** Matches the "id" of an agent response, for response post-processing. */
    private val RUNTIME_RESPONSE_ID = Regex(""""id":(\d+)""")

    /**
     * Stable debuggerId reported in Debugger.enable responses, mimicking V8.
     * Stock Chrome DevTools' breakpoint model misbehaves without one.
     */
    private const val DEBUGGER_ID = "zipline-hermes-debugger"

    /**
     * The Hermes agent never reports an execution context; without one the
     * DevTools console has nowhere to evaluate (input silently vanishes).
     */
    private const val EXECUTION_CONTEXT_CREATED =
      """{"method":"Runtime.executionContextCreated","params":{"context":{"id":1,"origin":"","name":"Hermes","auxData":{"isDefault":true}}}}"""

    private val VLQ_DIGITS: IntArray = IntArray(128) { -1 }.also { table ->
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c ->
        table[c.code] = i
      }
    }
  }

  private fun kotlinx.serialization.json.JsonElement?.asObject(): JsonObject? =
    this as? JsonObject

  private fun kotlinx.serialization.json.JsonElement?.asString(): String? =
    (this as? JsonPrimitive)?.contentOrNull
}

private data class IoStream(val content: String, var position: Int = 0)
