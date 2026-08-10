package app.cash.zipline.internal.cdp

import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.close

/**
 * The CDP debug server for JNI platforms (Android, JVM): Ktor CIO engine on
 * loopback with HTTP target discovery; debugger clients are driven by the
 * shared [serveWebSocket] (see hostMain).
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
          val session = call.parameters["id"]?.let { core.session(it) }
          if (session == null) {
            close()
          } else {
            session.serveWebSocket(this, this)
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

internal actual fun initCdpServer(port: Int, core: CdpDebugServer): CdpServerHandle =
  KtorCdpServer(port, core)
