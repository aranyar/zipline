package app.cash.zipline.internal.cdp

/**
 * SHA-1 digest in pure Kotlin, so the WebSocket handshake works on platforms
 * without java.security.MessageDigest (Kotlin/Native) or CommonCrypto
 * (Linux). Used only for the RFC 6455 Sec-WebSocket-Accept hash, which is
 * not security-sensitive.
 */
internal fun sha1(data: ByteArray): ByteArray {
  var h0 = 0x67452301
  var h1 = 0xEFCDAB89.toInt()
  var h2 = 0x98BADCFE.toInt()
  var h3 = 0x10325476
  var h4 = 0xC3D2E1F0.toInt()

  // Padded message: data || 0x80 || zeros || 64-bit big-endian bit length.
  val bitLength = data.size.toLong() * 8
  val paddedSize = (data.size + 9 + 63) / 64 * 64
  val padded = ByteArray(paddedSize)
  data.copyInto(padded)
  padded[data.size] = 0x80.toByte()
  for (i in 0 until 8) {
    padded[paddedSize - 1 - i] = (bitLength ushr (i * 8)).toByte()
  }

  val w = IntArray(80)
  for (chunkStart in padded.indices step 64) {
    for (i in 0 until 16) {
      val j = chunkStart + i * 4
      w[i] = (padded[j].toInt() and 0xFF shl 24) or
        (padded[j + 1].toInt() and 0xFF shl 16) or
        (padded[j + 2].toInt() and 0xFF shl 8) or
        (padded[j + 3].toInt() and 0xFF)
    }
    for (i in 16 until 80) {
      w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
    }

    var a = h0
    var b = h1
    var c = h2
    var d = h3
    var e = h4
    for (i in 0 until 80) {
      val (f, k) = when (i) {
        in 0..19 -> ((b and c) or (b.inv() and d)) to 0x5A827999
        in 20..39 -> (b xor c xor d) to 0x6ED9EBA1
        in 40..59 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBDC5.toInt()
        else -> (b xor c xor d) to 0xCA62C1D6.toInt()
      }
      val temp = a.rotateLeft(5) + f + e + k + w[i]
      e = d
      d = c
      c = b.rotateLeft(30)
      b = a
      a = temp
    }
    h0 += a
    h1 += b
    h2 += c
    h3 += d
    h4 += e
  }

  val out = ByteArray(20)
  for ((i, h) in listOf(h0, h1, h2, h3, h4).withIndex()) {
    out[i * 4] = (h ushr 24).toByte()
    out[i * 4 + 1] = (h ushr 16).toByte()
    out[i * 4 + 2] = (h ushr 8).toByte()
    out[i * 4 + 3] = h.toByte()
  }
  return out
}
