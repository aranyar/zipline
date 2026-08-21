package app.cash.zipline.bytecode

import okio.Buffer
import org.junit.Test
import java.io.File

class ZiplineFileRoundTripTest {
  @Test fun wbRecommendationsRoundTrip() {
    val path = "/Users/efremov.andrey65/wb/compose-live/wb/live/build/zipline/Production/wb-recommendations.zipline"
    val file = File(path)
    if (!file.exists()) {
      println("Skipping test - file not found: $path")
      return
    }
    val ziplineBytes = file.readBytes()
    println("Zipline file size: ${ziplineBytes.size}")

    // Skip the zipline header manually since we may have a newer/different version.
    // Format: "ZIPLINE\0" (8 bytes) + version (4 bytes) + sections:
    //   each section: 4-byte header, 4-byte length, length bytes content
    val source = Buffer().write(ziplineBytes)
    val magic = source.readByteArray(8)
    check(magic.toString(Charsets.UTF_8).startsWith("ZIPLINE")) { "Not a zipline file" }
    val ziplineVersion = source.readInt()
    println("Zipline version: $ziplineVersion")

    // Find the QUICKJS_BYTECODE section.
    var bytes: ByteArray? = null
    while (!source.exhausted()) {
      val sectionHeader = source.readInt()
      val sectionLength = source.readInt()
      if (sectionHeader == 1) { // SECTION_HEADER_QUICKJS_BYTECODE
        bytes = source.readByteArray(sectionLength.toLong())
      } else {
        source.skip(sectionLength.toLong())
      }
    }
    checkNotNull(bytes) { "QuickJS bytecode section not found" }
    println("QuickJS bytecode size: ${bytes.size}")

    val reader = JsObjectReader(bytes)
    val decoded = reader.use { it.readJsObject() }

    val buffer = Buffer()
    JsObjectWriter(reader.atoms, buffer).use { it.writeJsObject(decoded) }
    val rewritten = buffer.readByteArray()

    val matches = bytes.contentEquals(rewritten)
    println("Bytes match: $matches")
    println("Original size: ${bytes.size}, Rewritten size: ${rewritten.size}")
    if (!matches) {
      var diffIdx = -1
      for (idx in bytes.indices) {
        if (idx >= rewritten.size || bytes[idx] != rewritten[idx].toByte()) {
          diffIdx = idx
          break
        }
      }
      if (diffIdx >= 0) {
        val origByte = bytes[diffIdx].toInt() and 0xFF
        val newByte = if (diffIdx < rewritten.size) rewritten[diffIdx].toInt() and 0xFF else -1
        println("First diff at $diffIdx: original=$origByte rewritten=$newByte")
        val from = maxOf(0, diffIdx - 5)
        val to = minOf(bytes.size, diffIdx + 20)
        println("Original: ${bytes.slice(from..to).joinToString(" ") { (it.toInt() and 0xFF).toString(16) }}")
        if (diffIdx < rewritten.size) {
          val from2 = maxOf(0, diffIdx - 5)
          val to2 = minOf(rewritten.size, diffIdx + 20)
          println("Rewritten: ${rewritten.slice(from2..to2).joinToString(" ") { (it.toInt() and 0xFF).toString(16) }}")
        }
      }
    }
    check(matches) { "Bytes don't match" }
  }
}
