package app.cash.zipline.internal

import app.cash.zipline.JsEngine
import kotlinx.coroutines.CoroutineScope

internal actual fun cdpAttachIfEnabled(jsEngine: JsEngine, scope: CoroutineScope) {
  // CDP debugging is not wired up on Kotlin/Native yet.
}
