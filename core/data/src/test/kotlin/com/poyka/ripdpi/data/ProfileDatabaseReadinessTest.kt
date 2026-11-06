package com.poyka.ripdpi.data

import com.google.protobuf.InvalidProtocolBufferException
import com.poyka.ripdpi.data.persistence.SettingsRows
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ProfileDatabaseReadinessTest : RoomProfileTest() {
    @Test
    fun `observers wait through failed recovery and the same subscriptions resume after explicit retry`() =
        runTest {
            val fixture = fixture()
            val before = fixture.snapshot()
            val writeAttempts = AtomicInteger()
            fixture.room.beforeSeal = { _, _ ->
                writeAttempts.incrementAndGet()
                error("Key is unavailable")
            }
            val settings = firstOnIo(fixture.settings.settings)
            val changes = firstOnIo(fixture.room.profileDatabase.changes(SettingsRows.Namespace))
            assertTrue(settings.isActive)
            assertTrue(changes.isActive)
            assertEquals(0, writeAttempts.get())
            assertEquals(before, fixture.snapshot())

            val recoveryFailure = runCatching { fixture.initialize() }.exceptionOrNull()
            assertTrue(recoveryFailure is IllegalStateException)
            assertEquals("Key is unavailable", recoveryFailure?.message)
            assertTrue(settings.isActive)
            assertTrue(changes.isActive)
            val readFailure = runCatching { fixture.settings.snapshot() }.exceptionOrNull()
            assertTrue(readFailure is IllegalStateException)
            assertEquals("Key is unavailable", readFailure?.message)
            assertEquals(2, writeAttempts.get())
            assertEquals(before, fixture.snapshot())
            assertTrue(settings.isActive)
            assertTrue(changes.isActive)

            fixture.clearFaults()
            fixture.initialize()
            assertEquals(AppSettingsSerializer.defaultValue, settings.await())
            assertEquals(Unit, changes.await())
            assertEquals(AppSettingsSerializer.defaultValue, fixture.settings.snapshot())
        }

    @Test
    fun `corrupt migrated settings keep observers waiting until explicit reset`() =
        runTest {
            for (invalidCiphertext in listOf(false, true)) {
                val fixture = fixture()
                fixture.initialize()
                if (invalidCiphertext) {
                    fixture.corruptCredential(SettingsRows.Namespace, SettingsRows.Current)
                } else {
                    fixture.room.profileDatabase.write {
                        it.records.write(SettingsRows.Namespace, SettingsRows.Current, byteArrayOf(0x0f))
                    }
                }
                val before =
                    withContext(Dispatchers.IO) {
                        fixture.room.database
                            .profileRecordDao()
                            .list(SettingsRows.Namespace)
                            .single()
                            .payload
                    }
                fixture.room.reopen().use { reopened ->
                    val settings = firstOnIo(reopened.settings.settings)
                    val changes = firstOnIo(reopened.profileDatabase.changes(SettingsRows.Namespace))
                    assertTrue(settings.isActive)
                    assertTrue(changes.isActive)
                    val expectedFailure =
                        if (invalidCiphertext) {
                            IllegalArgumentException::class.java
                        } else {
                            InvalidProtocolBufferException::class.java
                        }
                    val initializationFailure = runCatching { reopened.initialize() }.exceptionOrNull()
                    assertTrue(expectedFailure.isInstance(initializationFailure))
                    val snapshotFailure = runCatching { reopened.settings.snapshot() }.exceptionOrNull()
                    assertTrue(expectedFailure.isInstance(snapshotFailure))
                    assertTrue(settings.isActive)
                    assertTrue(changes.isActive)
                    // Reads of the failed state have not changed the persisted record bytes.
                    val stored =
                        withContext(Dispatchers.IO) {
                            reopened.database
                                .profileRecordDao()
                                .list(SettingsRows.Namespace)
                                .single()
                        }
                    assertArrayEquals(before, stored.payload)

                    reopened.coordinator.runReset { }
                    assertEquals(AppSettingsSerializer.defaultValue, settings.await())
                    assertEquals(Unit, changes.await())
                    assertEquals(AppSettingsSerializer.defaultValue, reopened.settings.snapshot())
                }
            }
        }

    private suspend fun <T> TestScope.firstOnIo(flow: Flow<T>): Deferred<T> =
        withContext(Dispatchers.IO) {
            // Start upstream on IO before returning, so the no-initialization assertions are deterministic.
            backgroundScope.async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { flow.first() }
        }
}
