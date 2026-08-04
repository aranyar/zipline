package app.cash.zipline.internal.cdp

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest

/**
 * Minimal HTTP/1.1 + WebSocket (RFC 6455) plumbing for the CDP debug server. Implemented on raw
 * sockets so the engine library has no server dependencies. Only what Chrome DevTools needs:
 * HTTP GET requests, and text WebSocket messages of any size (with fragmentation).
 */
internal object WebSocketProtocol {
  private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

  class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
  )

  /** Reads an HTTP request (request line + headers, no body). Returns null on EOF. */
  fun readHttpRequest(input: InputStream): HttpRequest? {
    val requestLine = readHttpLine(input) ?: return null
    if (requestLine.isEmpty()) return null
    val parts = requestLine.split(' ')
    if (parts.size < 2) throw IOException("Malformed request line: $requestLine")
    val headers = LinkedHashMap<String, String>()
    while (true) {
      val line = readHttpLine(input) ?: throw EOFException("EOF in HTTP headers")
      if (line.isEmpty()) break
      val colon = line.indexOf(':')
      if (colon > 0) {
        headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
      }
    }
    return HttpRequest(parts[0], parts[1], headers)
  }

  /** Reads a single CRLF-terminated line byte by byte (payload framing happens after headers). */
  private fun readHttpLine(input: InputStream): String? {
    val line = StringBuilder()
    while (true) {
      val b = input.read()
      if (b == -1) {
        if (line.isEmpty()) return null
        throw EOFException("EOF in HTTP line")
      }
      if (b == '\r'.code) continue
      if (b == '\n'.code) return line.toString()
      line.append(b.toChar())
      if (line.length > 16384) throw IOException("HTTP line too long")
    }
  }

  fun writeHttpResponse(output: OutputStream, status: Int, statusText: String, body: String) {
    val bodyBytes = body.toByteArray(Charsets.UTF_8)
    val head = buildString {
      append("HTTP/1.1 ").append(status).append(' ').append(statusText).append("\r\n")
      append("Content-Type: application/json; charset=utf-8\r\n")
      append("Content-Length: ").append(bodyBytes.size).append("\r\n")
      append("Connection: close\r\n")
      append("\r\n")
    }
    synchronized(output) {
      output.write(head.toByteArray(Charsets.UTF_8))
      output.write(bodyBytes)
      output.flush()
    }
  }

  fun writeWebSocketUpgrade(output: OutputStream, secWebSocketKey: String) {
    val accept = base64(MessageDigest.getInstance("SHA-1").digest((secWebSocketKey + GUID).toByteArray(Charsets.UTF_8)))
    val head = buildString {
      append("HTTP/1.1 101 Switching Protocols\r\n")
      append("Upgrade: websocket\r\n")
      append("Connection: Upgrade\r\n")
      append("Sec-WebSocket-Accept: ").append(accept).append("\r\n")
      append("\r\n")
    }
    synchronized(output) {
      output.write(head.toByteArray(Charsets.UTF_8))
      output.flush()
    }
  }

  /** Sends an unfragmented text frame. Server frames are never masked. */
  fun sendText(output: OutputStream, text: String) {
    sendFrame(output, OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))
  }

  fun sendPong(output: OutputStream, payload: ByteArray) {
    sendFrame(output, OPCODE_PONG, payload)
  }

  fun sendClose(output: OutputStream) {
    sendFrame(output, OPCODE_CLOSE, ByteArray(0))
  }

  private fun sendFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
    synchronized(output) {
      output.write(0x80 or opcode)
      when {
        payload.size < 126 -> output.write(payload.size)
        payload.size <= 0xFFFF -> {
          output.write(126)
          output.write((payload.size ushr 8) and 0xFF)
          output.write(payload.size and 0xFF)
        }
        else -> {
          output.write(127)
          for (shift in 56 downTo 0 step 8) {
            output.write(((payload.size.toLong() ushr shift) and 0xFF).toInt())
          }
        }
      }
      output.write(payload)
      output.flush()
    }
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
  fun readFrames(input: InputStream, output: OutputStream, onText: (String) -> Unit) {
    // Bytes are accumulated across fragments and decoded only at FIN: a
    // multi-byte UTF-8 character may be split across fragment boundaries.
    val message = java.io.ByteArrayOutputStream()
    while (true) {
      val b0 = input.read()
      if (b0 == -1) return
      val b1 = input.read()
      if (b1 == -1) return
      val fin = (b0 and 0x80) != 0
      val opcode = b0 and 0x0F
      val masked = (b1 and 0x80) != 0
      var length = (b1 and 0x7F).toLong()
      if (length == 126L) {
        length = (readByteOrEof(input).toLong() shl 8) or readByteOrEof(input).toLong()
      } else if (length == 127L) {
        length = 0
        for (i in 0 until 8) {
          length = (length shl 8) or readByteOrEof(input).toLong()
        }
      }
      if (length > 64L * 1024L * 1024L) throw IOException("WebSocket frame too large")
      val maskKey = if (masked) readNBytes(input, 4) else null
      var payload = readNBytes(input, length.toInt())
      if (maskKey != null) {
        for (i in payload.indices) {
          payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        }
      }
      when (opcode) {
        OPCODE_TEXT -> {
          message.reset()
          message.write(payload)
          if (fin) onText(String(message.toByteArray(), Charsets.UTF_8))
        }
        OPCODE_CONTINUATION -> {
          message.write(payload)
          if (fin) onText(String(message.toByteArray(), Charsets.UTF_8))
        }
        OPCODE_PING -> sendPong(output, payload)
        OPCODE_PONG -> Unit
        OPCODE_CLOSE -> {
          sendClose(output)
          return
        }
        else -> throw IOException("Unsupported WebSocket opcode: $opcode")
      }
    }
  }

  private fun readByteOrEof(input: InputStream): Int {
    val b = input.read()
    if (b == -1) throw EOFException("EOF in WebSocket frame")
    return b
  }

  private fun readNBytes(input: InputStream, n: Int): ByteArray {
    val result = ByteArray(n)
    var offset = 0
    while (offset < n) {
      val read = input.read(result, offset, n - offset)
      if (read == -1) throw EOFException("EOF in WebSocket frame payload")
      offset += read
    }
    return result
  }

  private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

  /** java.util.Base64 requires Android API 26+; this encoder keeps us minSdk 21 compatible. */
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

/** Closes quietly. */
internal fun Socket.closeQuietly() {
  try {
    close()
  } catch (_: IOException) {
  }
}
