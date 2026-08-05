package app.cash.zipline

internal actual fun setCdpPortEnv(port: Int?) {
  if (port != null) {
    System.setProperty("app.cash.zipline.cdp.port", port.toString())
  } else {
    System.clearProperty("app.cash.zipline.cdp.port")
  }
}
