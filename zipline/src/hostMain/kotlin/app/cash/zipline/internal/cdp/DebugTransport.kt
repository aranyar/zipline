package app.cash.zipline.internal.cdp

/**
 * Platform plumbing for the CDP debug server: threads, locks, HTTP fetches
 * and port configuration. Actuals exist for JNI (java.net/Thread) and
 * Kotlin/Native (POSIX/coroutines) platforms.
 */

/** A mutual exclusion lock for non-coroutine code. */
internal expect class DebugLock() {
  fun <T> withLock(block: () -> T): T
}

/** A counting semaphore for blocking (non-coroutine) code. */
internal expect class DebugSemaphore(permits: Int) {
  fun acquire()
  fun release()
}

/** Starts a daemon background thread. [block] must not throw. */
internal expect fun startDebugThread(name: String, block: () -> Unit)

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
internal expect fun startCdpServer(port: Int, core: CdpDebugServer): CdpServerHandle

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
