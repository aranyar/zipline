package app.cash.zipline

/** Enables/disables CDP debugging for the current process in tests (a null port disables). */
internal expect fun setCdpPortEnv(port: Int?)
