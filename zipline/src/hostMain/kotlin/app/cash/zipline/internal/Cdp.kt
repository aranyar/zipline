package app.cash.zipline.internal

import app.cash.zipline.JsEngine
import kotlinx.coroutines.CoroutineScope

/**
 * Attaches [jsEngine] to the CDP debug server when debugging is enabled on this platform
 * (no-op otherwise). Called from [app.cash.zipline.Zipline.create]; [scope] is confined to
 * the JS thread and is used to run debugger tasks between JavaScript executions.
 */
internal expect fun cdpAttachIfEnabled(jsEngine: JsEngine, scope: CoroutineScope)
