package app.cash.zipline.internal.cdp

import kotlinx.coroutines.launch

/**
 * Platform plumbing for the CDP debug server: threads, locks, HTTP fetches
 * and port configuration. Actuals exist for JNI (java.net/Thread) and
 * Kotlin/Native (POSIX/coroutines) platforms.
 */

/**
 * Starts a background thread named [name] running [block], swallowing any
 * failure. Implemented with coroutines on every platform (a fresh
 * single-thread context per call, i.e. a real thread on JVM and a worker on
 * Kotlin/Native). Note these threads are not daemon-marked on JVM; the debug
 * server is a debug-time feature whose host processes exit via System.exit.
 */
@kotlin.OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
internal fun startDebugThread(name: String, block: () -> Unit) {
  kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.newSingleThreadContext(name), block = {
    try {
      block()
    } catch (_: Throwable) {
      // A crashing debug-server thread must not take down the host app.
    }
  })
}

/**
 * Performs an HTTP GET and returns the response body on HTTP 200, null on
 * other statuses. Throws [okio.IOException] on connection/IO errors.
 */
internal expect fun httpGet(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String?

/** The TCP port the CDP debug server should listen on, or null when debugging is disabled. */
internal expect fun cdpDebugPort(): Int?

/**
 * Starts the platform CDP debug server for [core]: HTTP target discovery
 * (`/json`, `/json/list`, `/json/version`) and the `/devtools/page/<id>`
 * WebSocket. Ktor CIO on JNI platforms, raw sockets on Kotlin/Native.
 * Throws when the port cannot be bound.
 */
internal expect fun initCdpServer(port: Int, core: CdpDebugServer): CdpServerHandle

internal interface CdpServerHandle {
  /** Starts accepting connections (returns immediately). */
  fun start()
}

/**
 * Extra URL variants to try when fetching script sources from the dev server,
 * after the loopback-literal variant and the URL itself (e.g. the Android
 * emulator NAT alias 10.0.2.2 for localhost URLs).
 */
internal expect fun extraFetchCandidates(url: String): List<String>
