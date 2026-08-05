package app.cash.zipline.internal.cdp

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import okio.IOException

/** Loopback only: the server grants unauthenticated Runtime.evaluate access to the app's
 * JS context, so it must not be reachable from the network. Devices reach it through
 * adb forward/reverse, which work with a loopback bind. */
internal actual class DebugServerSocket actual constructor(port: Int) {
  private val server = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))

  actual fun accept(): DebugSocket = DebugSocket(server.accept())

  actual fun close() = server.close()
}

internal actual class DebugSocket(
  private val socket: Socket,
) {
  private val input = socket.getInputStream()
  private val output = socket.getOutputStream()

  actual fun setTcpNoDelay() {
    socket.tcpNoDelay = true
  }

  actual fun read(): Int = input.read()

  actual fun readInto(buffer: ByteArray, offset: Int, length: Int): Int =
    input.read(buffer, offset, length)

  actual fun write(bytes: ByteArray) {
    synchronized(socket) {
      output.write(bytes)
      output.flush()
    }
  }

  actual fun close() = socket.close()
}

internal actual fun connectDebugSocket(host: String, port: Int): DebugSocket =
  DebugSocket(Socket(host, port))

internal actual class DebugLock actual constructor() {
  actual fun <T> withLock(block: () -> T): T = synchronized(this, block)
}

internal actual class DebugSemaphore actual constructor(permits: Int) {
  private val semaphore = java.util.concurrent.Semaphore(permits)

  actual fun acquire() = semaphore.acquire()

  actual fun release() = semaphore.release()
}

internal actual fun startDebugThread(name: String, block: () -> Unit) {
  Thread(block, name).apply { isDaemon = true }.start()
}

internal actual fun httpGet(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String? {
  val connection = java.net.URL(url).openConnection() as HttpURLConnection
  connection.connectTimeout = connectTimeoutMs
  connection.readTimeout = readTimeoutMs
  connection.setRequestProperty("Connection", "close")
  try {
    if (connection.responseCode == 200) {
      return connection.inputStream.bufferedReader().use { it.readText() }
    }
  } finally {
    connection.disconnect()
  }
  return null
}

internal actual fun cdpDebugPort(): Int? =
  System.getProperty("app.cash.zipline.cdp.port")?.toIntOrNull()

internal actual fun extraFetchCandidates(url: String): List<String> {
  // The Android emulator NAT alias for the host machine.
  val nat = url.replace("://localhost:", "://10.0.2.2:").replace("://127.0.0.1:", "://10.0.2.2:")
  return if (nat != url) listOf(nat) else emptyList()
}
