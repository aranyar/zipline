package app.cash.zipline.internal.cdp

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** Loopback-only blocking server socket for tests. */
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
