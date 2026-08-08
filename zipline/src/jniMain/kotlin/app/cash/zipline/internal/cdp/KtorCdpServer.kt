package app.cash.zipline.internal.cdp

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The CDP debug server for JNI platforms (Android, JVM): Ktor CIO engine on
 * loopback with HTTP target discovery and a WebSocket per debugger client.
 * Kotlin/Native uses the raw-socket server instead (Ktor server is JVM-only).
 */
private class KtorCdpServer(
  private val port: Int,
  private val core: CdpDebugServer,
) : CdpServerHandle {
  private val engine: io.ktor.server.engine.EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
    embeddedServer(CIO, port = port, host = "127.0.0.1") {
    install(WebSockets)
    routing {
      get("/json/version") {
        call.respondText(core.versionJson(call.request.headers["Host"]), ContentType.Application.Json)
      }
      get("/json") {
        call.respondText(core.targetsJson(call.request.headers["Host"]), ContentType.Application.Json)
      }
      get("/json/list") {
        call.respondText(core.targetsJson(call.request.headers["Host"]), ContentType.Application.Json)
      }
      webSocket("/devtools/page/{id}") {
        val session = call.parameters["id"]?.let(core::session)
        if (session == null) {
          close()
          return@webSocket
        }
        val conn = KtorCdpClientConnection(this, this)
        session.addClient(conn)
        try {
          for (frame in incoming) {
            if (frame is Frame.Text) {
              session.onCdpMessage(frame.readText())
            }
          }
        } finally {
          session.removeClient(conn)
          conn.closeQuietly()
        }
      }
    }
  }

  override fun start() {
    engine.start(wait = false)
    app.cash.zipline.internal.log(
      "info",
      "Zipline CDP debug server listening on port $port (Ktor CIO)",
      null,
    )
  }
}

@OptIn(ExperimentalAtomicApi::class)
private class KtorCdpClientConnection(
  private val ws: WebSocketServerSession,
  private val scope: CoroutineScope,
) : CdpClientConnection {
  private val open = AtomicBoolean(true)

  override fun sendText(text: String) {
    if (!open.load()) return
    scope.launch {
      try {
        ws.send(Frame.Text(text))
      } catch (_: Throwable) {
        open.store(false)
      }
    }
  }

  override fun isOpen(): Boolean = open.load()

  override fun closeQuietly() {
    if (open.compareAndSet(true, false)) {
      scope.launch {
        try {
          ws.close()
        } catch (_: Throwable) {
        }
      }
    }
  }
}

internal actual fun startCdpServer(port: Int, core: CdpDebugServer): CdpServerHandle =
  KtorCdpServer(port, core)
