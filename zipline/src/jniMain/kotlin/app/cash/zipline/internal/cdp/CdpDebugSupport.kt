package app.cash.zipline.internal.cdp

import app.cash.zipline.JsEngine
import kotlinx.coroutines.CoroutineScope

/**
 * Entry point for CDP debugging of a [JsEngine]. Debugging is enabled by setting the
 * `app.cash.zipline.cdp.port` system property to a TCP port (e.g. "9222") before the engine's
 * Zipline instance is created; [Zipline.create] then attaches each new engine to a shared
 * debug server on that port.
 */
internal object CdpDebugSupport {
  @Volatile
  private var server: CdpDebugServer? = null

  fun attachIfEnabled(jsEngine: JsEngine, scope: CoroutineScope) {
    val port = System.getProperty("app.cash.zipline.cdp.port")?.toIntOrNull() ?: return
    val server = server ?: synchronized(this) {
      server ?: try {
        CdpDebugServer(port).also {
          it.start()
          server = it
        }
      } catch (t: Throwable) {
        // E.g. another process (or an earlier Zipline) already bound the port.
        // Debugging is best-effort; never take down Zipline creation.
        app.cash.zipline.internal.log(
          "warn",
          "Zipline CDP: cannot bind port $port (${t.message}); debugging disabled",
          null,
        )
        return
      }
    }
    server.attach(jsEngine, scope)
  }

  fun detach(jsEngine: JsEngine) {
    server?.detach(jsEngine)
  }
}
