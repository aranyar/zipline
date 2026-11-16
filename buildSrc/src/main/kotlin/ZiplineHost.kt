/** Which native targets the machine running Gradle can actually compile. */
object ZiplineHost {
  private val osName: String = System.getProperty("os.name").lowercase()

  val isWindows: Boolean = "windows" in osName
  val isMac: Boolean = "mac" in osName
  val isLinux: Boolean = "linux" in osName

  /** macOS, iOS, and tvOS Kotlin/Native targets require a macOS host. */
  val canBuildApple: Boolean = isMac

  /** linuxX64 can be produced on Linux and on macOS. */
  val canBuildLinuxNative: Boolean = isLinux || isMac
}
