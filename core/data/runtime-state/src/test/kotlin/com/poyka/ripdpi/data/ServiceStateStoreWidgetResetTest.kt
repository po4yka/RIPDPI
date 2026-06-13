package com.poyka.ripdpi.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Widget-surface half of the SIGKILL -> Halted invariant.
 *
 * The persisted widget snapshot (DataStore) can outlive the process that wrote
 * it, so a process killed while [AppStatus.Running] / [AppStatus.Reconnecting]
 * leaves an "active" snapshot on disk. These tests pin the guarantee that a fresh
 * process reconciles that snapshot back to [AppStatus.Halted] the moment the
 * canonical store is constructed — so the widget can never come back up showing a
 * stale active status (the inherent while-process-dead window aside, which no
 * app-side code can close). See `.claude/rules/android-vpn-lifecycle.md`.
 */
class ServiceStateStoreWidgetResetTest {
    @Test
    fun `constructing the store projects a Halted widget snapshot for a fresh process`() {
        val widget = RecordingWidgetStateRepository()

        DefaultServiceStateStore(widget, NoopWidgetNotifier, unconfinedScope())

        // The init combine emits its initial (Halted, idle) pair the instant it is
        // collected, overwriting whatever a prior (killed) process persisted.
        assertTrue("expected the store to write an initial widget snapshot", widget.writes.isNotEmpty())
        assertEquals(AppStatus.Halted, widget.writes.first().status)
    }

    @Test
    fun `the widget tracks the live status once a session reports in`() {
        val widget = RecordingWidgetStateRepository()
        val store = DefaultServiceStateStore(widget, NoopWidgetNotifier, unconfinedScope())

        store.setStatus(AppStatus.Reconnecting, Mode.VPN)
        store.setStatus(AppStatus.Running, Mode.VPN)

        // Latest projection reflects the live status, not the stale disk value.
        assertEquals(AppStatus.Running, widget.writes.last().status)
    }

    private fun unconfinedScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private class RecordingWidgetStateRepository : WidgetStateRepository {
        val writes = mutableListOf<WidgetSnapshot>()
        private val state = MutableStateFlow(WidgetSnapshot())

        override suspend fun write(snapshot: WidgetSnapshot) {
            writes += snapshot
            state.value = snapshot
        }

        override fun observe(): Flow<WidgetSnapshot> = state.asStateFlow()

        override suspend fun snapshot(): WidgetSnapshot = state.value
    }
}
