package app.cash.zipline.internal.cdp

import okio.IOException

/**
 * Minimal HTTP/1.1 + WebSocket (RFC 6455) plumbing for the CDP debug server, on top of the
 * platform [DebugSocket] transport. Only what Chrome DevTools needs: HTTP GET requests, and
 * text WebSocket messages of any size (with fragmentation).
 */
internal object WebSocketProtocol {
  private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

  class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
  )

  /** Reads an HTTP request (request line + headers, no body). Returns null on EOF. */
  fun readHttpRequest(socket: DebugSocket): HttpRequest? {
    val requestLine = readHttpLine(socket) ?: return null
    if (requestLine.isEmpty()) return null
    val parts = requestLine.split(' ')
    if (parts.size < 2) throw IOException("Malformed request line: $requestLine")
    val headers = LinkedHashMap<String, String>()
    while (true) {
      val line = readHttpLine(socket) ?: throw okio.EOFException("EOF in HTTP headers")
      if (line.isEmpty()) break
      val colon = line.indexOf(':')
      if (colon > 0) {
        headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
      }
    }
    return HttpRequest(parts[0], parts[1], headers)
  }

  /** Reads a single CRLF-terminated line byte by byte (payload framing happens after headers). */
  private fun readHttpLine(socket: DebugSocket): String? {
    val line = StringBuilder()
    while (true) {
      val b = socket.read()
      if (b == -1) {
        if (line.isEmpty()) return null
        throw okio.EOFException("EOF in HTTP line")
      }
      if (b == '\r'.code) continue
      if (b == '\n'.code) return line.toString()
      line.append(b.toChar())
      if (line.length > 16384) throw IOException("HTTP line too long")
    }
  }

  fun writeHttpResponse(socket: DebugSocket, status: Int, statusText: String, body: String) {
    val bodyBytes = body.encodeToByteArray()
    val head = buildString {
      append("HTTP/1.1 ").append(status).append(' ').append(statusText).append("\r\n")
      append("Content-Type: application/json; charset=utf-8\r\n")
      append("Content-Length: ").append(bodyBytes.size).append("\r\n")
      append("Connection: close\r\n")
      append("\r\n")
    }
    socket.write(head.encodeToByteArray() + bodyBytes)
  }

  fun writeWebSocketUpgrade(socket: DebugSocket, secWebSocketKey: String) {
    val accept = base64(sha1((secWebSocketKey + GUID).encodeToByteArray()))
    val head = buildString {
      append("HTTP/1.1 101 Switching Protocols\r\n")
      append("Upgrade: websocket\r\n")
      append("Connection: Upgrade\r\n")
      append("Sec-WebSocket-Accept: ").append(accept).append("\r\n")
      append("\r\n")
    }
    socket.write(head.encodeToByteArray())
  }

  /** Sends an unfragmented text frame. Server frames are never masked. */
  fun sendText(socket: DebugSocket, text: String) {
    sendFrame(socket, OPCODE_TEXT, text.encodeToByteArray())
  }

  fun sendPong(socket: DebugSocket, payload: ByteArray) {
    sendFrame(socket, OPCODE_PONG, payload)
  }

  fun sendClose(socket: DebugSocket) {
    sendFrame(socket, OPCODE_CLOSE, ByteArray(0))
  }

  private fun sendFrame(socket: DebugSocket, opcode: Int, payload: ByteArray) {
    val headerSize = when {
      payload.size < 126 -> 2
      payload.size <= 0xFFFF -> 4
      else -> 10
    }
    // One write call per frame: DebugSocket.write serializes concurrent
    // writers, so frames from different threads never interleave.
    val frame = ByteArray(headerSize + payload.size)
    frame[0] = (0x80 or opcode).toByte()
    when {
      payload.size < 126 -> frame[1] = payload.size.toByte()
      payload.size <= 0xFFFF -> {
        frame[1] = 126
        frame[2] = (payload.size ushr 8).toByte()
        frame[3] = payload.size.toByte()
      }
      else -> {
        frame[1] = 127
        for (i in 0 until 8) {
          frame[2 + i] = ((payload.size.toLong() ushr (56 - i * 8)) and 0xFF).toByte()
        }
      }
    }
    payload.copyInto(frame, headerSize)
    socket.write(frame)
  }

  private const val OPCODE_CONTINUATION = 0x0
  private const val OPCODE_TEXT = 0x1
  private const val OPCODE_CLOSE = 0x8
  private const val OPCODE_PING = 0x9
  private const val OPCODE_PONG = 0xA

  /**
   * Reads WebSocket frames until the connection closes, delivering complete text messages to
   * [onText]. Returns when the peer closes or on IO error.
   */
  fun readFrames(socket: DebugSocket, onText: (String) -> Unit) {
    // Bytes are accumulated across fragments and decoded only at FIN: a
    // multi-byte UTF-8 character may be split across fragment boundaries.
    var message = ByteArray(0)
    while (true) {
      val b0 = socket.read()
      if (b0 == -1) return
      val b1 = socket.read()
      if (b1 == -1) return
      val fin = (b0 and 0x80) != 0
      val opcode = b0 and 0x0F
      val masked = (b1 and 0x80) != 0
      var length = (b1 and 0x7F).toLong()
      if (length == 126L) {
        length = (readByteOrEof(socket).toLong() shl 8) or readByteOrEof(socket).toLong()
      } else if (length == 127L) {
        length = 0
        for (i in 0 until 8) {
          length = (length shl 8) or readByteOrEof(socket).toLong()
        }
      }
      if (length > 64L * 1024L * 1024L) throw IOException("WebSocket frame too large")
      val maskKey = if (masked) readNBytes(socket, 4) else null
      val payload = readNBytes(socket, length.toInt())
      if (maskKey != null) {
        for (i in payload.indices) {
          payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        }
      }
      when (opcode) {
        OPCODE_TEXT -> {
          message = payload
          if (fin) onText(message.decodeToString())
        }
        OPCODE_CONTINUATION -> {
          message += payload
          if (fin) onText(message.decodeToString())
        }
        OPCODE_PING -> sendPong(socket, payload)
        OPCODE_PONG -> Unit
        OPCODE_CLOSE -> {
          sendClose(socket)
          return
        }
        else -> throw IOException("Unsupported WebSocket opcode: $opcode")
      }
    }
  }

  private fun readByteOrEof(socket: DebugSocket): Int {
    val b = socket.read()
    if (b == -1) throw okio.EOFException("EOF in WebSocket frame")
    return b
  }

  private fun readNBytes(socket: DebugSocket, n: Int): ByteArray {
    val result = ByteArray(n)
    var offset = 0
    while (offset < n) {
      val read = socket.readInto(result, offset, n - offset)
      if (read == -1) throw okio.EOFException("EOF in WebSocket frame payload")
      offset += read
    }
    return result
  }

  private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

  /** Hand-rolled so the handshake works without java.util.Base64 (minSdk 21, Kotlin/Native). */
  internal fun base64(data: ByteArray): String {
    val out = StringBuilder((data.size + 2) / 3 * 4)
    var i = 0
    while (i < data.size) {
      val b0 = data[i].toInt() and 0xFF
      val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
      val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
      out.append(BASE64_ALPHABET[b0 ushr 2])
      out.append(BASE64_ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
      out.append(if (i + 1 < data.size) BASE64_ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)] else '=')
      out.append(if (i + 2 < data.size) BASE64_ALPHABET[b2 and 0x3F] else '=')
      i += 3
    }
    return out.toString()
  }
}
