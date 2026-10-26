package com.poyka.ripdpi.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMutationEditIsolationTest {
    @Test
    fun `catalog observer reads stores off the collector thread`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val coordinator = fixture.coordinator()
            val io = AppCoroutineDispatchers(Dispatchers.IO, Dispatchers.IO, Dispatchers.IO)
            val reader = ProfileUtilityCatalogReader(coordinator, fixture.stores, fixture.pauseAuthority, io)
            val collectorThread = Thread.currentThread()

            reader.observe().first()

            assertTrue(fixture.xrayMetadata.listThreads.isNotEmpty())
            assertTrue(fixture.xrayMetadata.listThreads.none { it == collectorThread })
        }
}
