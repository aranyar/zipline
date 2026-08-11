package app.cash.zipline.internal.cdp


internal actual fun cdpDebugPort(): Int? =
  System.getProperty("app.cash.zipline.cdp.port")?.toIntOrNull()

internal actual fun extraFetchCandidates(url: String): List<String> {
  // The Android emulator NAT alias for the host machine.
  val nat = url.replace("://localhost:", "://10.0.2.2:").replace("://127.0.0.1:", "://10.0.2.2:")
  return if (nat != url) listOf(nat) else emptyList()
}
