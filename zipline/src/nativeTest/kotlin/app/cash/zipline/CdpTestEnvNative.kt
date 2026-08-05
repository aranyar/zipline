@file:OptIn(ExperimentalForeignApi::class)

package app.cash.zipline

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.setenv
import platform.posix.unsetenv

internal actual fun setCdpPortEnv(port: Int?) {
  if (port != null) {
    setenv("ZIPLINE_CDP_PORT", port.toString(), 1)
  } else {
    unsetenv("ZIPLINE_CDP_PORT")
  }
}
