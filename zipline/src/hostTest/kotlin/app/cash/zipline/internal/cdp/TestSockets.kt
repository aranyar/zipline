package app.cash.zipline.internal.cdp

/**
 * Blocking sockets for tests (the CDP test client and the mini dev server).
 * The production transports are Ktor-based; these exist only for tests.
 * All methods throw [okio.IOException] on IO errors.
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

/** Closes quietly. */
internal fun DebugSocket.closeQuietly() {
  try {
    close()
  } catch (_: okio.IOException) {
  }
}
