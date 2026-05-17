package com.poyka.ripdpi.data

/** Interface implemented in :app so :core:data:runtime-state stays UI-agnostic. */
interface WidgetNotifier {
    suspend fun pushUpdate()
}

/** No-op for tests / non-app callers. */
object NoopWidgetNotifier : WidgetNotifier {
    override suspend fun pushUpdate() = Unit
}
