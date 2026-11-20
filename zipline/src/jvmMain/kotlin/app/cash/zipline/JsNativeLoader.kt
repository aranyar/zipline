package app.cash.zipline

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Locale.US

@Suppress("UnsafeDynamicallyLoadedCode") // Only loading from our own JAR contents.
internal actual fun loadNativeLibrary() {
  val osName = System.getProperty("os.name").lowercase(US)
  val rawArch = System.getProperty("os.arch").lowercase(US)
  // HotSpot on Windows x64 reports "amd64" (sometimes "x86_64"); Windows ARM
  // reports "aarch64" (sometimes "arm64"). macOS keeps its own resource dirs.
  val osArch = when {
    osName.contains("windows") && (rawArch == "x86_64" || rawArch == "amd64") -> "amd64"
    osName.contains("windows") && (rawArch == "aarch64" || rawArch == "arm64") -> "aarch64"
    else -> rawArch
  }
  // Combined Hermes + JNI glue library. On Android, Hermes's CMake produces
  // libhermesvm.so with our JNI glue merged in (via target_sources in
  // hermes-jni-build/CMakeLists.txt). On macOS/Linux host, the Gradle
  // buildHermesHost* tasks produce libhermesvm.dylib/so the same way.
  // The JVM loads this single library; no separate libzipline_hermes_jni.so needed.
  val libName = when {
    osName.contains("linux") -> "libhermesvm.so"
    osName.contains("mac") -> "libhermesvm.dylib"
    osName.contains("windows") -> "hermesvm.dll"
    else -> throw IllegalStateException("Unsupported OS: $osName")
  }
  val resourcePath = "/jni/$osArch/$libName"
  val url = JsEngine::class.java.getResource(resourcePath)
      ?: throw IllegalStateException("Unable to read $resourcePath from JAR")
  val tempFile: Path
  try {
    // LoadLibrary matches a full path, but a sibling import of "hermesvm.dll"
    // looks up that exact file name. A .tmp suffix would miss it.
    tempFile = if (osName.contains("windows")) {
      // Windows cannot delete a DLL that is still loaded, so deleteOnExit
      // leaves a zipline-hermes* directory behind on every run. Reuse one
      // directory per library contents and drop caches from older builds.
      windowsLibraryCache(url.readBytes(), libName)
    } else {
      Files.createTempFile(libName.substringBeforeLast('.'), null)
    }
    if (!osName.contains("windows")) {
      tempFile.toFile().deleteOnExit()
      url.openStream().use { Files.copy(it, tempFile, REPLACE_EXISTING) }
    }
    System.load(tempFile.toAbsolutePath().toString())
  } catch (e: IOException) {
    throw RuntimeException("Unable to extract native library from JAR", e)
  } catch (e: UnsatisfiedLinkError) {
    throw RuntimeException("Unable to load JsEngine native library", e)
  }
}

/**
 * Stable cache for hermesvm.dll. A loaded DLL cannot be deleted, so a fresh
 * temp directory on every launch would accumulate under java.io.tmpdir.
 */
private fun windowsLibraryCache(bytes: ByteArray, libName: String): Path {
  val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }
  val root = Path.of(System.getProperty("java.io.tmpdir"), "zipline-hermes")
  val dir = root.resolve(hash)
  val dll = dir.resolve(libName)
  Files.createDirectories(dir)
  if (Files.notExists(dll)) {
    val partial = Files.createTempFile(dir, "hermesvm-", ".partial")
    Files.write(partial, bytes)
    try {
      Files.move(partial, dll, ATOMIC_MOVE)
    } catch (e: FileAlreadyExistsException) {
      Files.deleteIfExists(partial)
    }
  }
  try {
    val temp = root.parent
    Files.list(temp).use { children ->
      children.filter { child ->
        val name = child.fileName.toString()
        name.startsWith("zipline-hermes") && child != root
      }.forEach { stale ->
        stale.toFile().walkBottomUp().forEach { it.delete() }
      }
    }
    Files.list(root).use { children ->
      children.filter { it != dir }.forEach { stale ->
        stale.toFile().walkBottomUp().forEach { it.delete() }
      }
    }
  } catch (_: IOException) {
    // A DLL still loaded by another JVM cannot be deleted. Keep it.
  }
  return dll
}
