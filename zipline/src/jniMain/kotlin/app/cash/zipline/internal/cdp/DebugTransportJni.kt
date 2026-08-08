package app.cash.zipline.internal.cdp

import java.net.HttpURLConnection

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
