package com.poyka.ripdpi.hosts

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.services.HostAutolearnStoreController
import com.poyka.ripdpi.testsupport.FakeServiceStateStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LearnedHostsEraserTest {
    @Test
    fun `erase records a pending erase for the next engine start`() =
        runTest {
            val store = RecordingStoreController(present = true)
            val status = FakeServiceStateStore().apply { setStatus(AppStatus.Running, Mode.VPN) }

            assertEquals(LearnedHostsEraseResult.ClearedAfterStop, eraser(store, status).erase())
            assertFalse(store.present)
            assertTrue(store.erasePending)
        }

    @Test
    fun `erase while halted still records the pending erase`() =
        runTest {
            // A runtime that is starting still reports Halted and may already hold the hosts.
            val store = RecordingStoreController(present = true)

            assertEquals(LearnedHostsEraseResult.Cleared, eraser(store, FakeServiceStateStore()).erase())
            assertFalse(store.present)
            assertTrue(store.erasePending)
        }

    @Test
    fun `failed delete is reported and stays pending`() =
        runTest {
            val store = RecordingStoreController(present = true, deletable = false)

            assertEquals(LearnedHostsEraseResult.Failed, eraser(store, FakeServiceStateStore()).erase())
            assertTrue(store.erasePending)
        }

    @Test
    fun `marker that cannot be written is reported as failed`() =
        runTest {
            val store = RecordingStoreController(present = true, markable = false)

            assertEquals(LearnedHostsEraseResult.Failed, eraser(store, FakeServiceStateStore()).erase())
        }

    private fun TestScope.eraser(
        store: HostAutolearnStoreController,
        status: FakeServiceStateStore,
    ): LearnedHostsEraser {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return LearnedHostsEraser(store, status, AppCoroutineDispatchers(dispatcher, dispatcher, dispatcher))
    }

    private class RecordingStoreController(
        var present: Boolean,
        private val deletable: Boolean = true,
        private val markable: Boolean = true,
    ) : HostAutolearnStoreController {
        var erasePending = false

        override fun hasStore(): Boolean = present

        override fun clearStore(): Boolean {
            if (deletable) present = false
            return !present
        }

        override fun markErasePending(): Boolean {
            erasePending = markable
            return markable
        }

        override fun applyPendingErase(): Boolean = clearStore().also { if (it) erasePending = false }
    }
}
