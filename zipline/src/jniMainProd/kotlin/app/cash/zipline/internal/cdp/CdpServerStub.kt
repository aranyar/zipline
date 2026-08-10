package app.cash.zipline.internal.cdp

/** Prod builds ship without the Ktor CDP debug server (and its dependencies). */
internal actual fun initCdpServer(port: Int, core: CdpDebugServer): CdpServerHandle {
  throw UnsupportedOperationException(
    "CDP debugging requires the full engine (build with -PhermesProd=false)",
  )
}
