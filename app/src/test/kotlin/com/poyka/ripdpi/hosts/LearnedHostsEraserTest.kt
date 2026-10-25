package com.poyka.ripdpi.hosts

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.services.HostAutolearnStoreController
import com.poyka.ripdpi.testsupport.FakeServiceStateStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LearnedHostsEraserTest {
    @Test
    fun `erase while the engine runs deletes the file the engine rewrites at shutdown`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FileLikeStoreController(present = true)
            val status = FakeServiceStateStore().apply { setStatus(AppStatus.Running, Mode.VPN) }
            val eraser = LearnedHostsEraser(store, status, backgroundScope)

            assertEquals(LearnedHostsEraseResult.ClearedAfterStop, eraser.erase())
            assertFalse(store.hasStore())

            // The engine's shutdown flush recreates the file from its in-memory hosts.
            store.present = true
            status.setStatus(AppStatus.Halted, Mode.VPN)

            assertFalse(store.hasStore())
        }

    @Test
    fun `erase while halted clears once and schedules nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FileLikeStoreController(present = true)
            val status = FakeServiceStateStore()
            val eraser = LearnedHostsEraser(store, status, backgroundScope)

            assertEquals(LearnedHostsEraseResult.Cleared, eraser.erase())
            store.present = true
            status.setStatus(AppStatus.Running, Mode.VPN)
            status.setStatus(AppStatus.Halted, Mode.VPN)

            assertTrue(store.hasStore())
        }

    @Test
    fun `failed delete is reported`() =
        runTest {
            val store = FileLikeStoreController(present = true, deletable = false)
            val eraser = LearnedHostsEraser(store, FakeServiceStateStore(), backgroundScope)

            assertEquals(LearnedHostsEraseResult.Failed, eraser.erase())
        }

    private class FileLikeStoreController(
        var present: Boolean,
        private val deletable: Boolean = true,
    ) : HostAutolearnStoreController {
        override fun hasStore(): Boolean = present

        override fun clearStore(): Boolean {
            if (deletable) present = false
            return !present
        }
    }
}
