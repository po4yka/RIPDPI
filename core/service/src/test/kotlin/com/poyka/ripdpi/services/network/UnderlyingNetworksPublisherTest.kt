package com.poyka.ripdpi.services.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnderlyingNetworksPublisherTest {
    @Test
    fun `publishes network when available and validated`() {
        val recorder = FakeUnderlyingNetworksRecorder()
        val publisher = UnderlyingNetworksPublisher()
        val network = FakeNetwork(id = 1)

        val published =
            publisher.publishIfSafe(
                recorder = recorder,
                network = network,
                validated = true,
                captivePortal = false,
            )

        assertTrue(published)
        assertEquals(network, recorder.lastPublished)
    }

    @Test
    fun `skips publishing when network is null`() {
        val recorder = FakeUnderlyingNetworksRecorder()
        val publisher = UnderlyingNetworksPublisher()

        val published =
            publisher.publishIfSafe(
                recorder = recorder,
                network = null,
                validated = true,
                captivePortal = false,
            )

        assertFalse(published)
        assertNull(recorder.lastPublished)
    }

    @Test
    fun `skips publishing when network is not validated`() {
        val recorder = FakeUnderlyingNetworksRecorder()
        val publisher = UnderlyingNetworksPublisher()
        val network = FakeNetwork(id = 2)

        val published =
            publisher.publishIfSafe(
                recorder = recorder,
                network = network,
                validated = false,
                captivePortal = false,
            )

        assertFalse(published)
        assertNull(recorder.lastPublished)
    }

    @Test
    fun `skips publishing when captive portal is active`() {
        val recorder = FakeUnderlyingNetworksRecorder()
        val publisher = UnderlyingNetworksPublisher()
        val network = FakeNetwork(id = 3)

        val published =
            publisher.publishIfSafe(
                recorder = recorder,
                network = network,
                validated = true,
                captivePortal = true,
            )

        assertFalse(published)
        assertNull(recorder.lastPublished)
    }

    @Test
    fun `clears underlying networks when network is lost`() {
        val recorder = FakeUnderlyingNetworksRecorder()
        val publisher = UnderlyingNetworksPublisher()

        publisher.clearNetworks(recorder)

        assertTrue(recorder.cleared)
    }
}

// ---------------------------------------------------------------------------
// Test doubles
// ---------------------------------------------------------------------------

data class FakeNetwork(
    val id: Int,
)

class FakeUnderlyingNetworksRecorder : UnderlyingNetworksRecorder<FakeNetwork> {
    var lastPublished: FakeNetwork? = null
    var cleared: Boolean = false

    override fun setNetworks(network: FakeNetwork?) {
        lastPublished = network
    }

    override fun clear() {
        cleared = true
    }
}
