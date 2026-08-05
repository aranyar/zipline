package app.cash.zipline.internal.cdp

/**
 * Platform transport for the CDP debug server: blocking sockets, threads,
 * locks, and HTTP fetches. Actuals exist for JNI (java.net/Thread) and
 * Kotlin/Native (POSIX sockets/pthreads) platforms.
 *
 * All socket methods throw [okio.IOException] on IO errors.
 */

/** Blocking server socket. Accepts loopback connections only. */
internal expect class DebugServerSocket(port: Int) {
  /** Blocks until a client connects. Throws [okio.IOException] when closed. */
  fun accept(): DebugSocket

  fun close()
}

/**
 * Blocking connected socket. [write] must be safe for concurrent use from
 * multiple threads (implementations serialize writes internally); each call
 * delivers [bytes] atomically with respect to other writers.
 */
internal expect class DebugSocket {
  fun setTcpNoDelay()

  /** Reads a single byte, or -1 on EOF. */
  fun read(): Int

  /** Reads up to [length] bytes into [buffer] at [offset]; returns the count, or -1 on EOF. */
  fun readInto(buffer: ByteArray, offset: Int, length: Int): Int

  /** Writes all of [bytes]. */
  fun write(bytes: ByteArray)

  fun close()
}

/** Connects a blocking client socket to [host]:[port]. */
internal expect fun connectDebugSocket(host: String, port: Int): DebugSocket

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
 * Extra URL variants to try when fetching script sources from the dev server,
 * after the loopback-literal variant and the URL itself (e.g. the Android
 * emulator NAT alias 10.0.2.2 for localhost URLs).
 */
internal expect fun extraFetchCandidates(url: String): List<String>

/** Closes quietly. */
internal fun DebugSocket.closeQuietly() {
  try {
    close()
  } catch (_: okio.IOException) {
  }
}
