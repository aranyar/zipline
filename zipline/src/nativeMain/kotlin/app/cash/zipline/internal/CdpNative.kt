package app.cash.zipline.internal

import app.cash.zipline.JsEngine
import app.cash.zipline.internal.cdp.CdpDebugSupport
import kotlinx.coroutines.CoroutineScope

internal actual fun cdpAttachIfEnabled(jsEngine: JsEngine, scope: CoroutineScope) {
  CdpDebugSupport.attachIfEnabled(jsEngine, scope)
}
