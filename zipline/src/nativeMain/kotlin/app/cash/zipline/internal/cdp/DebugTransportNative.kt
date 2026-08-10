@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package app.cash.zipline.internal.cdp

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import okio.IOException
import platform.posix.*

/**
 * POSIX transport for the CDP debug server (Kotlin/Native: iOS, macOS, Linux).
 * Blocking sockets + pthreads; the debug server is a debug-only tool, so the
 * implementation favors simplicity over performance (byte-at-a-time reads,
 * leaked pthread mutexes for long-lived locks).
 */

private val sigPipeIgnored = AtomicBoolean(false)

/** Sends to a closed peer raise SIGPIPE, which would kill the host app. Ignore
 * it process-wide (standard practice for socket servers) on first use. */
private fun ignoreSigPipe() {
  if (sigPipeIgnored.compareAndSet(false, true)) {
    signal(SIGPIPE, SIG_IGN)
  }
}

private fun checkSocket(result: Int, what: String) {
  if (result < 0) throw IOException("$what failed")
}

internal actual class DebugLock actual constructor() {
  private val mutex = nativeHeap.alloc<pthread_mutex_t>().apply {
    pthread_mutex_init(ptr, null)
  }

  actual fun <T> withLock(block: () -> T): T {
    pthread_mutex_lock(mutex.ptr)
    try {
      return block()
    } finally {
      pthread_mutex_unlock(mutex.ptr)
    }
  }
}

internal actual class DebugSemaphore actual constructor(permits: Int) {
  private val mutex = nativeHeap.alloc<pthread_mutex_t>().apply {
    pthread_mutex_init(ptr, null)
  }
  private val cond = nativeHeap.alloc<pthread_cond_t>().apply {
    pthread_cond_init(ptr, null)
  }
  private var count = permits

  actual fun acquire() {
    pthread_mutex_lock(mutex.ptr)
    try {
      while (count == 0) {
        pthread_cond_wait(cond.ptr, mutex.ptr)
      }
      count--
    } finally {
      pthread_mutex_unlock(mutex.ptr)
    }
  }

  actual fun release() {
    pthread_mutex_lock(mutex.ptr)
    try {
      count++
      pthread_cond_signal(cond.ptr)
    } finally {
      pthread_mutex_unlock(mutex.ptr)
    }
  }
}

private val HTTP_URL = Regex("""^http://([^/:]+)(?::(\d+))?(/.*)?$""")

/** Blocking connect to [host]:[port]; throws [IOException] on failure. */
private fun connectTcp(host: String, port: String): Int {
  ignoreSigPipe()
  return memScoped {
    val hints = alloc<addrinfo>()
    memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
    hints.ai_family = AF_UNSPEC
    hints.ai_socktype = SOCK_STREAM
    val result = alloc<CPointerVar<addrinfo>>()
    if (getaddrinfo(host, port, hints.ptr, result.ptr) != 0) {
      throw IOException("cannot resolve $host")
    }
    val info = result.value!!
    try {
      // Try each resolved address: the server binds IPv4 loopback, and
      // "localhost" may resolve to IPv6 ::1 first.
      var current = info
      while (true) {
        val fd = socket(
          current.pointed.ai_family,
          current.pointed.ai_socktype,
          current.pointed.ai_protocol,
        )
        if (fd >= 0) {
          if (connect(fd, current.pointed.ai_addr, current.pointed.ai_addrlen) == 0) {
            return@memScoped fd
          }
          close(fd)
        }
        val next = current.pointed.ai_next
        if (next == null) break
        current = next
      }
      throw IOException("cannot connect to $host:$port")
    } finally {
      freeaddrinfo(info)
    }
  }
}

/**
 * Minimal HTTP/1.0 GET over a blocking POSIX socket. Enough for fetching
 * script sources and source maps from the Zipline dev server. Raw sockets
 * (not NSURLSession) also mean App Transport Security does not apply.
 */
internal actual fun httpGet(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String? {
  val match = HTTP_URL.find(url) ?: throw IOException("unsupported URL: $url")
  val host = match.groupValues[1]
  val port = match.groupValues[2].ifEmpty { "80" }
  val path = match.groupValues[3].ifEmpty { "/" }

  val fd = connectTcp(host, port)

  try {
    memScoped {
      // SO_RCVTIMEO as raw bytes: timeval's tv_usec is 32-bit on Darwin and
      // 64-bit on Linux, so the typed struct can't be used from nativeMain.
      // On little-endian 64-bit targets both layouts are {8-byte sec, usec at
      // offset 8}, and the kernel only reads the fields it knows.
      val tv = allocArray<ByteVar>(16)
      memset(tv, 0, 16u)
      tv.reinterpret<LongVar>()[0] = (readTimeoutMs / 1000).toLong()
      (tv + 8)!!.reinterpret<LongVar>()[0] = ((readTimeoutMs % 1000) * 1000).toLong()
      setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv, 16u)
    }

    val request = "GET $path HTTP/1.0\r\nHost: $host\r\nConnection: close\r\n\r\n"
    val requestBytes = request.encodeToByteArray()
    requestBytes.usePinned { pinned ->
      var offset = 0
      while (offset < requestBytes.size) {
        val n = send(fd, pinned.addressOf(offset), (requestBytes.size - offset).convert(), 0)
        if (n <= 0) throw IOException("HTTP write failed")
        offset += n.toInt()
      }
    }

    // Accumulate raw bytes and decode once at the end: a multi-byte UTF-8
    // character may be split across read() chunk boundaries.
    val chunks = mutableListOf<ByteArray>()
    var total = 0
    val chunk = ByteArray(8192)
    while (true) {
      val n = chunk.usePinned { pinned -> read(fd, pinned.addressOf(0), chunk.size.convert()) }
      when {
        n > 0 -> {
          chunks += chunk.copyOf(n.toInt())
          total += n.toInt()
        }
        n == 0L -> break
        else -> throw IOException("HTTP read failed")
      }
    }

    val allBytes = ByteArray(total)
    var offset = 0
    for (c in chunks) {
      c.copyInto(allBytes, offset)
      offset += c.size
    }
    val text = allBytes.decodeToString()
    val statusLine = text.substringBefore("\r\n")
    if (!statusLine.contains(" 200")) return null
    val bodyStart = text.indexOf("\r\n\r\n")
    if (bodyStart < 0) return null
    return text.substring(bodyStart + 4)
  } finally {
    close(fd)
  }
}

internal actual fun cdpDebugPort(): Int? =
  getenv("ZIPLINE_CDP_PORT")?.toKString()?.toIntOrNull()

internal actual fun extraFetchCandidates(url: String): List<String> = emptyList()
