package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileDescriptor
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VpnUnderlyingNetworkSocketBindingTest {
    @Test
    fun `callback registration publishes epoch before synchronous and concurrent delivery`() {
        val lock = Any()
        var published: String? = null
        val callbackAttempted = CountDownLatch(1)
        val callbackCompleted = CountDownLatch(1)
        lateinit var callbackThread: Thread

        assertTrue(
            registerPublishedCallback(
                lock = lock,
                prepare = {
                    published = "epoch-1"
                    "callback"
                },
                register = { callback ->
                    assertEquals("callback", callback)
                    assertEquals("epoch-1", published)
                    callbackThread =
                        thread(start = true) {
                            callbackAttempted.countDown()
                            synchronized(lock) {
                                assertEquals("epoch-1", published)
                                callbackCompleted.countDown()
                            }
                        }
                    assertTrue(callbackAttempted.await(1, TimeUnit.SECONDS))
                    assertFalse(callbackCompleted.await(50, TimeUnit.MILLISECONDS))
                },
                rollback = { error("registration must not roll back") },
            ),
        )
        assertTrue(callbackCompleted.await(1, TimeUnit.SECONDS))
        callbackThread.join()
    }

    @Test
    fun `callback registration runtime failure rolls back published epoch`() {
        var published: String? = null
        var rolledBack: String? = null

        val failure =
            runCatching {
                registerPublishedCallback(
                    lock = Any(),
                    prepare = {
                        published = "epoch-1"
                        "callback"
                    },
                    register = { error("callback limit") },
                    rollback = { callback ->
                        rolledBack = callback
                        published = null
                    },
                )
            }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("callback", rolledBack)
        assertNull(published)
    }

    @Test
    fun `publish false or throw clears the exact prepared lease and publishes empty on loss`() {
        val resolver = InetAddress.getByName("1.1.1.1")
        val state = DirectDnsUnderlayLeaseState<String>()
        state.capture("wifi", snapshotGeneration = 1L, eligible = true, dnsServers = setOf(resolver))
        val firstToken = state.preparePolicy(setOf(resolver), complete = true, networkGeneration = 1L)
        val first = checkNotNull(state.preparedLease(firstToken))

        assertFalse(
            applyPreparedDirectDnsLease(
                current = first,
                publish = { false },
                clearIfSame = { snapshot ->
                    if (state.preparedLease(firstToken) === snapshot) state.abortPrepared(firstToken)
                },
            ),
        )
        assertNull(state.preparedLease(firstToken))

        val secondToken = state.preparePolicy(setOf(resolver), complete = true, networkGeneration = 1L)
        val second = checkNotNull(state.preparedLease(secondToken))
        assertFalse(
            applyPreparedDirectDnsLease(
                current = second,
                publish = { error("publisher failed") },
                clearIfSame = { snapshot ->
                    if (state.preparedLease(secondToken) === snapshot) state.abortPrepared(secondToken)
                },
            ),
        )
        assertNull(state.preparedLease(secondToken))

        var published: List<String>? = null
        assertFalse(
            applyPreparedDirectDnsLease(
                current = null,
                directUnderlayRequired = true,
                publish = { networks ->
                    published = networks
                    true
                },
                clearIfSame = {},
            ),
        )
        assertEquals(emptyList<String>(), published)

        val staleToken = state.preparePolicy(setOf(resolver), complete = true, networkGeneration = 1L)
        val stale = checkNotNull(state.preparedLease(staleToken))
        val publications = mutableListOf<List<String>?>()
        assertFalse(
            applyPreparedDirectDnsLease(
                current = stale,
                publish = { networks ->
                    publications += networks
                    true
                },
                clearIfSame = { snapshot ->
                    if (state.preparedLease(staleToken) === snapshot) state.abortPrepared(staleToken)
                },
                stillCurrent = { false },
            ),
        )
        assertEquals(listOf(listOf("wifi"), emptyList()), publications)
        assertNull(state.preparedLease(staleToken))
        assertEquals(0L, leaseGenerationOrZero(state.preparedLease(staleToken)))
    }

    @Test
    fun `socket bind follows protect duplicate bind close and generation recheck order`() {
        val events = mutableListOf<String>()
        val descriptor = FileDescriptor()
        val lease = DirectDnsUnderlayLease("wifi", 1, 1, 7)
        val ops = recordingOps(events, descriptor)

        assertTrue(
            bindDirectDnsSocketLease(
                fd = 42,
                generation = 7,
                lease = {
                    events += "snapshot"
                    lease
                },
                isCurrent = {
                    events += "recheck"
                    true
                },
                ops = ops,
            ),
        )
        assertEquals(listOf("snapshot", "protect:42", "duplicate:42", "bind:wifi", "close", "recheck"), events)
    }

    @Test
    fun `socket bind fails closed before and after every fallible boundary`() {
        val lease = DirectDnsUnderlayLease("wifi", 1, 1, 7)

        assertFalse(
            bindDirectDnsSocketLease(42, 8, { lease }, { true }, recordingOps(mutableListOf(), FileDescriptor())),
        )

        val protectEvents = mutableListOf<String>()
        assertFalse(
            bindDirectDnsSocketLease(
                42,
                7,
                { lease },
                { true },
                recordingOps(protectEvents, FileDescriptor(), protectResult = false),
            ),
        )
        assertEquals(listOf("protect:42"), protectEvents)

        val protectThrowEvents = mutableListOf<String>()
        assertFalse(
            bindDirectDnsSocketLease(
                42,
                7,
                { lease },
                { true },
                recordingOps(protectThrowEvents, FileDescriptor(), protectFailure = true),
            ),
        )
        assertEquals(listOf("protect:42"), protectThrowEvents)

        val duplicateEvents = mutableListOf<String>()
        assertFalse(
            bindDirectDnsSocketLease(
                42,
                7,
                { lease },
                { true },
                recordingOps(duplicateEvents, FileDescriptor(), duplicateFailure = true),
            ),
        )
        assertEquals(listOf("protect:42", "duplicate:42"), duplicateEvents)

        val bindEvents = mutableListOf<String>()
        assertFalse(
            bindDirectDnsSocketLease(
                42,
                7,
                { lease },
                { true },
                recordingOps(bindEvents, FileDescriptor(), bindFailure = true),
            ),
        )
        assertEquals(listOf("protect:42", "duplicate:42", "bind:wifi", "close"), bindEvents)

        val staleEvents = mutableListOf<String>()
        assertFalse(
            bindDirectDnsSocketLease(
                42,
                7,
                { lease },
                {
                    staleEvents += "recheck"
                    false
                },
                recordingOps(staleEvents, FileDescriptor()),
            ),
        )
        assertEquals(listOf("protect:42", "duplicate:42", "bind:wifi", "close", "recheck"), staleEvents)
    }

    private fun recordingOps(
        events: MutableList<String>,
        descriptor: FileDescriptor,
        protectResult: Boolean = true,
        protectFailure: Boolean = false,
        duplicateFailure: Boolean = false,
        bindFailure: Boolean = false,
    ): DirectDnsSocketBindingOps<String> =
        object : DirectDnsSocketBindingOps<String> {
            override fun protect(fd: Int): Boolean {
                events += "protect:$fd"
                if (protectFailure) error("protect failed")
                return protectResult
            }

            override fun duplicate(fd: Int): DirectDnsFileDescriptor {
                events += "duplicate:$fd"
                if (duplicateFailure) error("duplicate failed")
                return object : DirectDnsFileDescriptor {
                    override val fileDescriptor: FileDescriptor = descriptor

                    override fun close() {
                        events += "close"
                    }
                }
            }

            override fun bind(
                network: String,
                fileDescriptor: FileDescriptor,
            ) {
                assertEquals(descriptor, fileDescriptor)
                events += "bind:$network"
                if (bindFailure) error("bind failed")
            }
        }
}
