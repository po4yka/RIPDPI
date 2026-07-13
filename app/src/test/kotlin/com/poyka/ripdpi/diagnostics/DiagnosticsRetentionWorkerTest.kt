package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRetentionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsRetentionWorkerTest {
    @Test
    fun `retention runs without monitor or VPN lifecycle`() =
        runTest {
            val store = RecordingRetentionStore()

            trimDiagnosticsHistory(retentionDays = 17, retentionStore = store)

            assertEquals(listOf(17), store.calls)
        }

    private class RecordingRetentionStore : DiagnosticsHistoryRetentionStore {
        val calls = mutableListOf<Int>()

        override suspend fun trimOldData(retentionDays: Int) {
            calls += retentionDays
        }
    }
}
