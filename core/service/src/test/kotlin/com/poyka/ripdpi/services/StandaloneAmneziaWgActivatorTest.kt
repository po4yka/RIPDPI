package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.awg.AwgActivationObfuscation
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.boot.BootSessionPointer
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [DefaultStandaloneAmneziaWgActivator]: the `:app`-callable hop that
 * selects an [AwgActivationRequest] and starts the owned VPN/protect lifecycle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StandaloneAmneziaWgActivatorTest {
    @Test
    fun `accepted dispatch alone does not complete activation`() =
        runTest {
            val activator =
                newActivator(
                    serviceController = RecordingServiceController(autoApply = false),
                    bootSessionStateStore = RecordingBootSessionStateStore(),
                    loadProfile = { null },
                )
            val activation = launch { activator.activate(sampleRequest("awg-wait")) }
            runCurrent()
            try {
                assertFalse(activation.isCompleted)
            } finally {
                activation.cancel()
            }
        }

    @Test
    fun `explicit standalone selection precedes simple flavor selection`() {
        val activator =
            newActivator(
                serviceController = RecordingServiceController(),
                bootSessionStateStore = RecordingBootSessionStateStore(),
                loadProfile = { null },
            )

        assertEquals(-10, activator.selectionPriority)
    }

    @Test
    fun `activate selects request and starts vpn service`() =
        runTest {
            val serviceController = RecordingServiceController()
            val store = RecordingBootSessionStateStore()
            val activator =
                newActivator(
                    serviceController = serviceController,
                    bootSessionStateStore = store,
                    loadProfile = { null },
                )
            val request = sampleRequest("awg-uuid-A")

            activator.activate(request)

            assertEquals(listOf(Mode.VPN), serviceController.startCalls)
            assertEquals(request, activator.selectedAwgEgress())
            assertEquals(request.profileId, store.activeAwgProfileId())
        }

    @Test
    fun `re-activating replaces selected request and restarts vpn`() =
        runTest {
            val serviceController = RecordingServiceController()
            val store = RecordingBootSessionStateStore()
            val activator =
                newActivator(
                    serviceController = serviceController,
                    bootSessionStateStore = store,
                    loadProfile = { null },
                )

            activator.activate(sampleRequest("awg-uuid-A"))
            val second = sampleRequest("awg-uuid-B")
            activator.activate(second)

            assertEquals(listOf(Mode.VPN, Mode.VPN), serviceController.startCalls)
            assertEquals(second, activator.selectedAwgEgress())
        }

    @Test
    fun `deactivate clears selection and stops owned vpn session`() =
        runTest {
            val serviceController = RecordingServiceController()
            val store = RecordingBootSessionStateStore()
            val activator =
                newActivator(
                    serviceController = serviceController,
                    bootSessionStateStore = store,
                    loadProfile = { null },
                )

            activator.activate(sampleRequest("awg-uuid-A"))
            activator.deactivate()

            assertNull(activator.selectedAwgEgress())
            assertNull(store.activeAwgProfileId())
            assertEquals(1, serviceController.stopCalls)
        }

    @Test
    fun `deactivate without selected request does not stop vpn service`() =
        runTest {
            val serviceController = RecordingServiceController()
            val activator =
                newActivator(
                    serviceController = serviceController,
                    bootSessionStateStore = RecordingBootSessionStateStore(),
                    loadProfile = { null },
                )

            activator.deactivate()

            assertNull(activator.selectedAwgEgress())
            assertEquals(0, serviceController.stopCalls)
        }

    @Test
    fun `rejected vpn start clears selection and fails activation`() =
        runTest {
            val serviceController =
                RecordingServiceController(
                    startResult = ServiceStartResult.Rejected(Mode.VPN, ServiceStartRejectionReason.VpnConsentMissing),
                )
            val activator =
                newActivator(
                    serviceController = serviceController,
                    bootSessionStateStore = RecordingBootSessionStateStore(),
                    loadProfile = { null },
                )

            try {
                activator.activate(sampleRequest("awg-uuid-A"))
                fail("Expected rejected VPN start to fail activation")
            } catch (_: IllegalStateException) {
            }

            assertNull(activator.selectedAwgEgress())
            assertEquals(listOf(Mode.VPN), serviceController.startCalls)
        }

    @Test
    fun `selection rehydrates after process recreation`() =
        runTest {
            val request = sampleRequest("awg-uuid-A")
            val store = RecordingBootSessionStateStore(activeAwgId = request.profileId)
            val activator =
                newActivator(
                    serviceController = RecordingServiceController(),
                    bootSessionStateStore = store,
                    loadProfile = { profileId -> request.takeIf { it.profileId == profileId } },
                )

            assertEquals(request, activator.selectedAwgEgress())
        }

    @Test
    fun `missing persisted selection fails closed`() =
        runTest {
            val activator =
                newActivator(
                    serviceController = RecordingServiceController(),
                    bootSessionStateStore = RecordingBootSessionStateStore(activeAwgId = "missing"),
                    loadProfile = { null },
                )

            try {
                activator.selectedAwgEgress()
                fail("Expected missing AWG profile to fail closed")
            } catch (_: IllegalStateException) {
            }
        }

    @Test
    fun `selection stays readable while exact target acknowledgement is pending`() =
        runTest {
            val controller = RecordingServiceController(autoApply = false)
            val store = RecordingBootSessionStateStore()
            val provider =
                FakeSelectionStore().apply {
                    set(
                        com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord.of(
                            com.poyka.ripdpi.data.xray.VpnProviderKind.Xray,
                            "xray-old",
                        ),
                    )
                }
            val activator = newActivator(controller, store, { null }, provider)
            val request = sampleRequest("awg-new")
            val activation = launch { activator.activate(request) }
            runCurrent()
            assertFalse(activation.isCompleted)
            assertEquals(request, activator.selectedAwgEgress())
            assertEquals(listOf(TransportFailoverTarget(TransportKindAmneziaWg, request.profileId)), controller.targets)
            assertFalse(controller.tracker.recordApplied(controller.requestId + 1))
            check(controller.tracker.claimApplying(controller.requestId))
            check(controller.tracker.recordApplied(controller.requestId))
            controller.tracker.releaseRuntimeOwnership(controller.requestId)
            activation.join()
            assertEquals(com.poyka.ripdpi.data.xray.VpnProviderKind.Native, provider.current().kind)
        }

    @Test
    fun `failed replacement restores previous durable provider and selection`() =
        runTest {
            val controller =
                RecordingServiceController(
                    ServiceStartResult.Rejected(Mode.VPN, ServiceStartRejectionReason.VpnConsentMissing),
                )
            val previous = sampleRequest("awg-old")
            val store = RecordingBootSessionStateStore(previous.profileId)
            val provider = FakeSelectionStore()
            val previousProvider =
                com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord.of(
                    com.poyka.ripdpi.data.xray.VpnProviderKind.Xray,
                    "xray-old",
                )
            provider.set(previousProvider)
            val activator = newActivator(controller, store, { previous }, provider)
            val result = runCatching { activator.activate(sampleRequest("awg-rejected")) }
            org.junit.Assert.assertTrue(result.isFailure)
            assertEquals(previous.profileId, store.activeAwgProfileId())
            assertEquals(previousProvider, provider.current())
            assertNull(activator.selectedAwgEgress())
        }

    @Test
    fun `cleared durable authority cannot be revived by cached request`() =
        runTest {
            val controller = RecordingServiceController()
            val store = RecordingBootSessionStateStore()
            val activator = newActivator(controller, store, { null })
            activator.activate(sampleRequest("awg-old"))
            store.setActiveAwgProfileId(null)
            assertNull(activator.selectedAwgEgress())
        }

    @Test
    fun `cancelled activation cannot roll back same profile under a newer intent`() =
        runTest {
            val controller = RecordingServiceController(autoApply = false)
            val store = RecordingBootSessionStateStore(activeAwgId = "old-profile")
            val arbiter = ServiceIntentArbiter()
            val activator = newActivator(controller, store, { null }, serviceIntentArbiter = arbiter)
            val activation = launch { activator.activate(sampleRequest("awg-same")) }
            runCurrent()
            arbiter.userStart({ store.setActiveAwgProfileId("awg-same") }) { true }
            activation.cancelAndJoin()
            assertEquals("awg-same", store.activeAwgProfileId())
        }

    @Test
    fun `stale cached standalone selection cannot stop a newer ordinary session`() =
        runTest {
            val controller = RecordingServiceController()
            val store = RecordingBootSessionStateStore()
            val arbiter = ServiceIntentArbiter()
            val activator = newActivator(controller, store, { null }, serviceIntentArbiter = arbiter)
            activator.activate(sampleRequest("awg-old"))
            arbiter.userStart({ store.setActiveAwgProfileId(null) }) { true }
            activator.deactivate()
            assertEquals(0, controller.stopCalls)
            assertNull(store.activeAwgProfileId())
        }

    @Test
    fun `stale deactivate cannot stop newer intent with the same profile id`() =
        runTest {
            val controller = RecordingServiceController()
            val store = RecordingBootSessionStateStore()
            val arbiter = ServiceIntentArbiter()
            val activator = newActivator(controller, store, { null }, serviceIntentArbiter = arbiter)
            activator.activate(sampleRequest("awg-same"))
            arbiter.userStart({ store.setActiveAwgProfileId("awg-same") }) { true }
            activator.deactivate()
            activator.deactivate()
            assertEquals(0, controller.stopCalls)
            assertEquals("awg-same", store.activeAwgProfileId())
        }

    private fun newActivator(
        serviceController: RecordingServiceController,
        bootSessionStateStore: BootSessionStateStore,
        loadProfile: suspend (String) -> AwgActivationRequest?,
        providerSelectionStore: FakeSelectionStore = FakeSelectionStore(),
        serviceIntentArbiter: ServiceIntentArbiter = ServiceIntentArbiter(),
    ): DefaultStandaloneAmneziaWgActivator {
        val tracker = TransportFailoverApplyTracker()
        serviceController.tracker = tracker
        return DefaultStandaloneAmneziaWgActivator(
            serviceController,
            bootSessionStateStore,
            loadProfile,
            serviceController,
            tracker,
            providerSelectionStore,
            serviceIntentArbiter,
        )
    }

    private fun sampleRequest(profileId: String): AwgActivationRequest =
        AwgActivationRequest(
            profileId = profileId,
            privateKey =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(ByteArray(32) { 7 }),
            peerPublicKey =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(ByteArray(32) { 9 }),
            endpointHost = "vpn.example.org",
            endpointPort = 51820,
            interfaceAddressV4 = "10.8.0.2/32",
            obfuscation = AwgActivationObfuscation(jc = 4),
        )

    private class RecordingServiceController(
        private val startResult: ServiceStartResult = ServiceStartResult.Accepted(Mode.VPN),
        private val autoApply: Boolean = true,
    ) : ServiceController,
        VpnTransportActivationController {
        lateinit var tracker: TransportFailoverApplyTracker
        val targets = mutableListOf<TransportFailoverTarget>()
        var requestId: Long = 0L

        override fun startVpnTransport(
            requestId: Long,
            expectedTarget: TransportFailoverTarget,
        ): ServiceStartResult {
            this.requestId = requestId
            targets += expectedTarget
            if (autoApply && startResult is ServiceStartResult.Accepted) {
                check(tracker.claimApplying(requestId))
                check(tracker.recordApplied(requestId))
                tracker.releaseRuntimeOwnership(requestId)
            }
            return start(Mode.VPN)
        }

        val startCalls = mutableListOf<Mode>()
        var stopCalls = 0

        override fun start(mode: Mode): ServiceStartResult {
            startCalls += mode
            return startResult
        }

        override fun stop() {
            stopCalls++
        }
    }

    private class RecordingBootSessionStateStore(
        private var activeAwgId: String? = null,
    ) : BootSessionStateStore {
        override fun lastSession(): BootSessionPointer? = null

        override fun recordSession(
            profileId: String,
            mode: Mode,
        ) = Unit

        override fun activeAwgProfileId(): String? = activeAwgId

        override fun setActiveAwgProfileId(profileId: String?) {
            activeAwgId = profileId
        }

        override fun clear() = Unit

        override fun wasRunningAtUpdate(): Boolean = false

        override fun setWasRunningAtUpdate(value: Boolean) = Unit
    }
}
