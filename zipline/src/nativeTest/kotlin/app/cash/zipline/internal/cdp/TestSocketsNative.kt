@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package app.cash.zipline.internal.cdp

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.*
import okio.IOException
import platform.posix.*

/** POSIX blocking sockets for tests (the production server uses ktor-network). */

private val sigPipeIgnored = AtomicBoolean(false)

private fun ignoreSigPipe() {
  if (sigPipeIgnored.compareAndSet(false, true)) {
    signal(SIGPIPE, SIG_IGN)
  }
}

private fun checkSocket(result: Int, what: String) {
  if (result < 0) throw IOException("$what failed")
}

internal actual class DebugServerSocket actual constructor(port: Int) {
  private val fd: Int

  init {
    ignoreSigPipe()
    fd = memScoped {
      val hints = alloc<addrinfo>()
      memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
      hints.ai_family = AF_INET
      hints.ai_socktype = SOCK_STREAM
      hints.ai_flags = AI_NUMERICHOST
      val result = alloc<CPointerVar<addrinfo>>()
      if (getaddrinfo("127.0.0.1", port.toString(), hints.ptr, result.ptr) != 0) {
        throw IOException("cannot resolve 127.0.0.1")
      }
      val info = result.value!!
      try {
        val fd = socket(info.pointed.ai_family, info.pointed.ai_socktype, info.pointed.ai_protocol)
        checkSocket(fd, "socket")
        val one = alloc<IntVar>()
        one.value = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
        checkSocket(
          bind(fd, info.pointed.ai_addr, info.pointed.ai_addrlen),
          "bind port $port",
        )
        checkSocket(listen(fd, 50), "listen")
        fd
      } finally {
        freeaddrinfo(info)
      }
    }
  }

  actual fun accept(): DebugSocket {
    val clientFd = accept(fd, null, null)
    checkSocket(clientFd, "accept")
    return DebugSocket(clientFd)
  }

  actual fun close() {
    close(fd)
  }
}

internal actual class DebugSocket(
  private val fd: Int,
) {
  private val writeMutex = nativeHeap.alloc<pthread_mutex_t>().apply {
    pthread_mutex_init(ptr, null)
  }

  actual fun setTcpNoDelay() {
    memScoped {
      val one = alloc<IntVar>()
      one.value = 1
      setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, one.ptr, sizeOf<IntVar>().convert())
    }
  }

  actual fun read(): Int = memScoped {
    val b = alloc<ByteVar>()
    val n = read(fd, b.ptr, 1u)
    when {
      n > 0 -> b.value.toInt() and 0xFF
      n == 0L -> -1
      else -> throw IOException("socket read failed")
    }
  }

  actual fun readInto(buffer: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) return 0
    val n = buffer.usePinned { pinned ->
      read(fd, pinned.addressOf(offset), length.convert())
    }
    return when {
      n > 0 -> n.toInt()
      n == 0L -> -1
      else -> throw IOException("socket read failed")
    }
  }

  actual fun write(bytes: ByteArray) {
    pthread_mutex_lock(writeMutex.ptr)
    try {
      bytes.usePinned { pinned ->
        var offset = 0
        while (offset < bytes.size) {
          val n = send(fd, pinned.addressOf(offset), (bytes.size - offset).convert(), 0)
          if (n <= 0) throw IOException("socket write failed")
          offset += n.toInt()
        }
      }
    } finally {
      pthread_mutex_unlock(writeMutex.ptr)
    }
  }

  actual fun close() {
    close(fd)
  }
}

internal actual fun connectDebugSocket(host: String, port: Int): DebugSocket {
  ignoreSigPipe()
  val fd = memScoped {
    val hints = alloc<addrinfo>()
    memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
    hints.ai_family = AF_UNSPEC
    hints.ai_socktype = SOCK_STREAM
    val result = alloc<CPointerVar<addrinfo>>()
    if (getaddrinfo(host, port.toString(), hints.ptr, result.ptr) != 0) {
      throw IOException("cannot resolve $host")
    }
    val info = result.value!!
    try {
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
  return DebugSocket(fd)
}
