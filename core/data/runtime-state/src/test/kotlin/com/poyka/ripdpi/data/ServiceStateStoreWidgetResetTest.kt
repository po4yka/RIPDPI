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
    fun `constructing the store reconciles a stale active widget snapshot back to Halted`() {
        // Model a process that was killed (LMK / crash) mid-session: the DataStore
        // still holds an active snapshot from the dead process.
        val widget = RecordingWidgetStateRepository(seed = WidgetSnapshot(status = AppStatus.Running, mode = Mode.VPN))

        DefaultServiceStateStore(widget, NoopWidgetNotifier, unconfinedScope())

        // The init combine emits its initial (Halted, idle) pair the instant it is
        // collected, so the fresh process's first write overwrites the stale Running
        // value — the widget can never come back up showing the dead session as active.
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

    private class RecordingWidgetStateRepository(
        seed: WidgetSnapshot = WidgetSnapshot(),
    ) : WidgetStateRepository {
        val writes = mutableListOf<WidgetSnapshot>()
        private val state = MutableStateFlow(seed)

        override suspend fun write(snapshot: WidgetSnapshot) {
            writes += snapshot
            state.value = snapshot
        }

        override fun observe(): Flow<WidgetSnapshot> = state.asStateFlow()

        override suspend fun snapshot(): WidgetSnapshot = state.value
    }
}
