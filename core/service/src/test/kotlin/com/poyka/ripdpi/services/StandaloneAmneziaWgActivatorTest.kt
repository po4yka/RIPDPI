package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DurableCommandReceipt
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.ProfileActivationReceipt
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProfileMutationOutcome
import com.poyka.ripdpi.data.ProfileMutationPreparation
import com.poyka.ripdpi.data.RuntimeActivationEnvelope
import com.poyka.ripdpi.data.RuntimeActivationReceipt
import com.poyka.ripdpi.data.RuntimeCommandOrigin
import com.poyka.ripdpi.data.RuntimeStopReceipt
import com.poyka.ripdpi.data.awg.AwgActivationObfuscation
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.boot.BootSessionPointer
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
            val original = serviceController.mutations.activationReceipts.single()
            val dispatched = serviceController.dispatchedReceipts.single()
            assertEquals(original.commandId, dispatched.commandId)
            assertEquals(original.authority, dispatched.authority)
            assertTrue(dispatched.origin is RuntimeCommandOrigin.ProfileMutation)
            assertEquals(0, serviceController.prepareStartCalls)
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
            assertSame(
                serviceController.mutations.activationReceipts.single(),
                serviceController.mutations.compensations.single(),
            )
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
            testAcknowledgeRuntimeCommand(controller.testAuthority, controller.dispatchedReceipts.single())
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
            val arbiter =
                ServiceIntentArbiter(
                    controller.testAuthority,
                )
            val activator = newActivator(controller, store, { null }, serviceIntentArbiter = arbiter)
            val activation = launch { activator.activate(sampleRequest("awg-same")) }
            runCurrent()
            controller.prepareStart(Mode.VPN)
            arbiter.userStart({ store.setActiveAwgProfileId("awg-same") }) { true }
            activation.cancelAndJoin()
            assertEquals("awg-same", store.activeAwgProfileId())
        }

    @Test
    fun `stale cached standalone selection cannot stop a newer ordinary session`() =
        runTest {
            val controller = RecordingServiceController()
            val store = RecordingBootSessionStateStore()
            val arbiter =
                ServiceIntentArbiter(
                    controller.testAuthority,
                )
            val activator = newActivator(controller, store, { null }, serviceIntentArbiter = arbiter)
            activator.activate(sampleRequest("awg-old"))
            controller.prepareStart(Mode.VPN)
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
            val arbiter =
                ServiceIntentArbiter(
                    controller.testAuthority,
                )
            val activator = newActivator(controller, store, { null }, serviceIntentArbiter = arbiter)
            activator.activate(sampleRequest("awg-same"))
            controller.prepareStart(Mode.VPN)
            arbiter.userStart({ store.setActiveAwgProfileId("awg-same") }) { true }
            activator.deactivate()
            activator.deactivate()
            assertEquals(0, controller.stopCalls)
            assertEquals("awg-same", store.activeAwgProfileId())
        }

    @Test
    fun `activation queued behind lifecycle rejects preparation superseded by newer stop`() =
        runTest {
            val controller = RecordingServiceController(autoApply = false)
            val store = RecordingBootSessionStateStore()
            val activator = newActivator(controller, store, { null })
            val first = launch { activator.activate(sampleRequest("awg-first")) }
            runCurrent()
            val originalReceipt = controller.mutations.activationReceipts.single()
            val queued = async { runCatching { activator.activate(sampleRequest("awg-queued")) } }
            runCurrent()
            assertFalse(queued.isCompleted)
            assertEquals(
                originalReceipt.authority,
                controller.mutations.preparations
                    .last()
                    .expectedPauseAuthority,
            )
            val stopped = controller.prepareStop()
            first.cancelAndJoin()
            assertTrue(queued.await().exceptionOrNull() is IllegalStateException)
            assertEquals(listOf(TransportFailoverTarget(TransportKindAmneziaWg, "awg-first")), controller.targets)
            assertEquals(listOf(originalReceipt), controller.mutations.compensations)
            assertEquals("awg-first", store.activeAwgProfileId())
            assertTrue(controller.testAuthority.isCurrent(stopped))
            assertEquals(
                stopped.commandId,
                controller.testAuthority
                    .snapshotAuthority()
                    .command
                    ?.commandId,
            )
        }

    @Test
    fun `deactivate does not reserve stop for provider owned by another transport`() =
        runTest {
            val controller = RecordingServiceController()
            val store = RecordingBootSessionStateStore()
            val provider = FakeSelectionStore()
            val activator = newActivator(controller, store, { null }, provider)
            activator.activate(sampleRequest("awg-old"))
            val other =
                com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord.of(
                    com.poyka.ripdpi.data.xray.VpnProviderKind.Xray,
                    "xray-new",
                )
            provider.set(other)
            activator.deactivate()
            assertEquals(0, controller.prepareStopCalls)
            assertEquals(0, controller.stopCalls)
            assertTrue(controller.mutations.clears.isEmpty())
            assertEquals("awg-old", store.activeAwgProfileId())
            assertEquals(other, provider.current())
        }

    @Test
    fun `deactivate dispatches owned stop without clearing a replaced pointer`() =
        runTest {
            val controller = RecordingServiceController()
            val store = RecordingBootSessionStateStore()
            val activator = newActivator(controller, store, { null })
            activator.activate(sampleRequest("awg-old"))
            controller.afterStopPreparation = { store.setActiveAwgProfileId("awg-other") }
            activator.deactivate()
            assertEquals(1, controller.prepareStopCalls)
            assertEquals(1, controller.mutations.clears.size)
            assertTrue(controller.testAuthority.isCurrent(controller.mutations.clears.single()))
            assertEquals(1, controller.stopCalls)
            assertEquals("awg-other", store.activeAwgProfileId())
        }

    @Test
    fun `dispatch failure preserves original cause with suppressed compensation failure`() =
        runTest {
            val controller = RecordingServiceController()
            val store = RecordingBootSessionStateStore()
            val activator = newActivator(controller, store, { null })
            val original = IllegalStateException("dispatch failed")
            val cleanup = IllegalStateException("compensation failed")
            controller.dispatchFailure = original
            controller.mutations.compensationFailure = cleanup
            val result = runCatching { activator.activate(sampleRequest("awg-failure")) }
            assertSame(original, result.exceptionOrNull())
            assertEquals(1, original.suppressed.size)
            val suppressed = original.suppressed.single()
            assertEquals(cleanup.javaClass, suppressed.javaClass)
            assertEquals(cleanup.message, suppressed.message)
            assertTrue(suppressed === cleanup || suppressed.cause === cleanup)
            assertSame(controller.mutations.activationReceipts.single(), controller.mutations.compensations.single())
        }

    @Test
    fun `cancelled applying activation retains selection until runtime ownership is released`() =
        runTest {
            val controller = RecordingServiceController(autoApply = false)
            val store = RecordingBootSessionStateStore()
            val activator = newActivator(controller, store, { null })
            val request = sampleRequest("awg-applying")
            val activation = launch { activator.activate(request) }
            runCurrent()
            assertTrue(controller.tracker.claimApplying(controller.requestId))
            activation.cancel()
            runCurrent()
            assertFalse(activation.isCompleted)
            advanceTimeBy(30_001L)
            runCurrent()
            activation.join()
            assertTrue(controller.mutations.compensations.isEmpty())
            assertEquals(request, activator.selectedAwgEgress())
            assertEquals(request.profileId, store.activeAwgProfileId())
            assertNull(controller.tracker.tryBegin())
            testAcknowledgeRuntimeCommand(controller.testAuthority, controller.dispatchedReceipts.single())
            assertTrue(controller.tracker.recordApplied(controller.requestId))
            controller.tracker.releaseRuntimeOwnership(controller.requestId)
        }

    @Test
    fun `cancel after activation reservation compensates original receipt without dispatch`() =
        runTest {
            val controller = RecordingServiceController()
            val store = RecordingBootSessionStateStore("awg-before")
            val activator = newActivator(controller, store, { null })
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            controller.mutations.afterActivationReservation = {
                entered.complete(Unit)
                release.await()
            }
            val activation = launch { activator.activate(sampleRequest("awg-cancelled")) }
            entered.await()
            activation.cancel()
            runCurrent()
            assertFalse(activation.isCompleted)
            release.complete(Unit)
            activation.join()
            assertTrue(activation.isCancelled)
            assertSame(controller.mutations.activationReceipts.single(), controller.mutations.compensations.single())
            assertEquals("awg-before", store.activeAwgProfileId())
            assertTrue(controller.dispatchedReceipts.isEmpty())
        }

    @Test
    fun `stale deactivate cannot reserve stop after newer same profile start`() =
        runTest {
            val controller = RecordingServiceController()
            val store = RecordingBootSessionStateStore()
            val activator = newActivator(controller, store, { null })
            activator.activate(sampleRequest("awg-same"))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            controller.beforeConditionalStop = {
                entered.complete(Unit)
                release.await()
            }
            val deactivation = launch { activator.deactivate() }
            entered.await()
            val newer = controller.testAuthority.reserveStart(Mode.VPN)
            release.complete(Unit)
            deactivation.join()
            assertTrue(controller.testAuthority.isCurrent(newer))
            assertEquals("awg-same", store.activeAwgProfileId())
            assertTrue(controller.mutations.clears.isEmpty())
            assertEquals(0, controller.stopCalls)
        }

    @Test
    fun `cancel after stop reservation completes clear and original dispatch`() =
        runTest {
            val controller = RecordingServiceController()
            val store = RecordingBootSessionStateStore()
            val activator = newActivator(controller, store, { null })
            activator.activate(sampleRequest("awg-stop"))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            controller.mutations.beforeClear = {
                entered.complete(Unit)
                release.await()
            }
            val deactivation = launch { activator.deactivate() }
            entered.await()
            deactivation.cancel()
            runCurrent()
            assertFalse(deactivation.isCompleted)
            release.complete(Unit)
            deactivation.join()
            assertNull(store.activeAwgProfileId())
            assertEquals(1, controller.stopCalls)
            assertTrue(controller.testAuthority.isCurrent(controller.mutations.clears.single()))
        }

    @Test
    fun `clear failure still dispatches captured stop and preserves failure`() =
        runTest {
            val controller = RecordingServiceController()
            val store = RecordingBootSessionStateStore()
            val activator = newActivator(controller, store, { null })
            activator.activate(sampleRequest("awg-stop-fault"))
            val failure = IllegalStateException("clear failed")
            controller.mutations.clearFailure = failure
            val result = runCatching { activator.deactivate() }
            val actual = checkNotNull(result.exceptionOrNull())
            assertEquals(failure.javaClass, actual.javaClass)
            assertEquals(failure.message, actual.message)
            assertTrue(actual === failure || actual.cause === failure)
            assertEquals(1, controller.stopCalls)
            assertTrue(controller.testAuthority.isCurrent(controller.mutations.clears.single()))
        }

    private fun newActivator(
        serviceController: RecordingServiceController,
        bootSessionStateStore: BootSessionStateStore,
        loadProfile: suspend (String) -> AwgActivationRequest?,
        providerSelectionStore: FakeSelectionStore = FakeSelectionStore(),
        serviceIntentArbiter: ServiceIntentArbiter = serviceController.intentArbiter,
    ): DefaultStandaloneAmneziaWgActivator {
        val tracker = TransportFailoverApplyTracker()
        serviceController.tracker = tracker
        val mutations =
            RecordingAwgMutations(serviceController.testAuthority, bootSessionStateStore, providerSelectionStore)
        serviceController.mutations = mutations
        serviceController.appliedCallback = { receipt ->
            // Models the service queue's positive ACK; accepted Android dispatch alone is never applied proof.
            testAcknowledgeRuntimeCommand(serviceController.testAuthority, receipt)
            check(tracker.claimApplying(serviceController.requestId))
            check(tracker.recordApplied(serviceController.requestId))
            tracker.releaseRuntimeOwnership(serviceController.requestId)
        }
        return DefaultStandaloneAmneziaWgActivator(
            serviceController,
            bootSessionStateStore,
            loadProfile,
            serviceController,
            tracker,
            providerSelectionStore,
            serviceIntentArbiter,
            serviceController.testAuthority,
            mutations,
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
        private val startResult: ServiceStartResult? = null,
        private val autoApply: Boolean = true,
    ) : com.poyka.ripdpi.services.TestSynchronousServiceController(),
        VpnTransportActivationController {
        lateinit var tracker: TransportFailoverApplyTracker
        lateinit var mutations: RecordingAwgMutations
        lateinit var appliedCallback: (RuntimeActivationReceipt) -> Unit
        val dispatchedReceipts = mutableListOf<RuntimeActivationReceipt>()
        var prepareStopCalls = 0
        var prepareStartCalls = 0
        var dispatchFailure: Exception? = null
        var afterStopPreparation: ((RuntimeStopReceipt) -> Unit)? = null
        var beforeConditionalStop: (suspend () -> Unit)? = null
        val targets = mutableListOf<TransportFailoverTarget>()
        var requestId: Long = 0L

        override fun startVpnTransport(
            requestId: Long,
            expectedTarget: TransportFailoverTarget,
            receipt: com.poyka.ripdpi.data.DurableCommandReceipt,
        ): ServiceStartResult {
            this.requestId = requestId
            targets += expectedTarget
            val activation = receipt as RuntimeActivationReceipt
            assertEquals(mutations.activationReceipts.last().commandId, activation.commandId)
            assertEquals(mutations.activationReceipts.last().authority, activation.authority)
            assertEquals(expectedTarget.profileId, mutations.store.activeAwgProfileId())
            assertEquals(com.poyka.ripdpi.data.xray.VpnProviderKind.Native, mutations.provider.current().kind)
            dispatchedReceipts += activation
            dispatchFailure?.let { throw it }
            val result = startPrepared(Mode.VPN, activation)
            if (autoApply && result is ServiceStartResult.Accepted) appliedCallback(activation)
            return result
        }

        val startCalls = mutableListOf<Mode>()
        var stopCalls = 0

        override fun recordStart(
            mode: Mode,
            receipt: com.poyka.ripdpi.data.RuntimeActivationReceipt?,
        ): ServiceStartResult {
            startCalls += mode
            return startResult ?: ServiceStartResult.Accepted(checkNotNull(receipt))
        }

        override suspend fun prepareStart(mode: Mode): RuntimeActivationReceipt {
            prepareStartCalls++
            return super.prepareStart(mode)
        }

        override suspend fun prepareStop(): RuntimeStopReceipt {
            prepareStopCalls++
            beforeConditionalStop?.invoke()
            return super.prepareStop().also { afterStopPreparation?.invoke(it) }
        }

        override suspend fun prepareStopIfCurrent(
            expected: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot,
        ): RuntimeStopReceipt? {
            prepareStopCalls++
            beforeConditionalStop?.invoke()
            return testAuthority.reserveStopIfCurrent(expected)?.also { afterStopPreparation?.invoke(it) }
        }

        override fun recordStop() {
            stopCalls++
        }
    }

    /** Models only coordinator-owned pointer effects, using the controller's checked authority. */
    private class RecordingAwgMutations(
        private val authority: PauseIntentAuthority,
        val store: BootSessionStateStore,
        val provider: FakeSelectionStore,
    ) : ProfileMutationCoordinator {
        private data class BeforeImage(
            val profileId: String?,
            val provider: com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord,
        )

        private val beforeImages = mutableMapOf<String, BeforeImage>()
        val preparations = mutableListOf<ProfileMutationPreparation>()
        val activationReceipts = mutableListOf<ProfileActivationReceipt>()
        val compensations = mutableListOf<ProfileActivationReceipt>()
        val clears = mutableListOf<RuntimeStopReceipt>()
        var compensationFailure: Exception? = null
        var afterActivationReservation: (suspend () -> Unit)? = null
        var beforeClear: (suspend () -> Unit)? = null
        var clearFailure: Exception? = null

        override suspend fun captureMutation(origin: ProfileMutationOrigin): ProfileMutationPreparation =
            ProfileMutationPreparation(origin, authority.reference()).also { preparations += it }

        override suspend fun activateStandaloneAwg(
            preparation: ProfileMutationPreparation,
            profileId: String,
        ): ProfileMutationOutcome {
            val outcome =
                authority.intentLinearizer.serialize {
                    require(preparation.origin == ProfileMutationOrigin.ExplicitActivation)
                    val outcome =
                        authority.invalidateForMutation(
                            preparation.origin,
                            java.util.UUID
                                .randomUUID()
                                .toString(),
                            preparation.expectedPauseAuthority,
                        )
                    if (outcome is ProfileMutationOutcome.Reserved) {
                        val receipt = outcome.receipt as ProfileActivationReceipt
                        beforeImages[receipt.commandId] = BeforeImage(store.activeAwgProfileId(), provider.current())
                        store.setActiveAwgProfileId(profileId)
                        provider.update(
                            com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord.of(
                                com.poyka.ripdpi.data.xray.VpnProviderKind.Native,
                                null,
                            ),
                        )
                        activationReceipts += receipt
                    }
                    outcome
                }
            afterActivationReservation?.invoke()
            return outcome
        }

        override suspend fun compensateStandaloneAwg(
            receipt: ProfileActivationReceipt,
            expectedProfileId: String,
        ): Boolean =
            authority.intentLinearizer.serialize {
                compensations += receipt
                compensationFailure?.let { throw it }
                if (!authority.isCurrent(receipt) || store.activeAwgProfileId() != expectedProfileId ||
                    provider.current().kind != com.poyka.ripdpi.data.xray.VpnProviderKind.Native
                ) {
                    return@serialize false
                }
                val before = beforeImages[receipt.commandId] ?: return@serialize false
                val bound =
                    authority.activationReceiptFromEnvelope(
                        RuntimeActivationEnvelope(
                            receipt.authority.generation,
                            receipt.commandId,
                            receipt.origin,
                            Mode.VPN.preferenceValue,
                        ),
                    )
                val cancelled =
                    if (bound != null) {
                        authority.cancelPendingActivation(bound)
                    } else {
                        authority.cancelProfileActivation(receipt)
                    }
                if (!cancelled) return@serialize false
                store.setActiveAwgProfileId(before.profileId)
                provider.update(before.provider)
                beforeImages.remove(receipt.commandId)
                true
            }

        override suspend fun clearStandaloneAwg(
            receipt: RuntimeStopReceipt,
            expectedProfileId: String,
        ): Boolean {
            beforeClear?.invoke()
            return authority.intentLinearizer.serialize {
                clears += receipt
                clearFailure?.let { throw it }
                if (!authority.isCurrent(receipt) || store.activeAwgProfileId() != expectedProfileId ||
                    provider.current().kind != com.poyka.ripdpi.data.xray.VpnProviderKind.Native
                ) {
                    return@serialize false
                }
                store.setActiveAwgProfileId(null)
                true
            }
        }

        // Unrelated catalog operations fail closed; this double supplies no generic successful mutation path.
        override suspend fun recover(): Unit = unsupported()

        override suspend fun <T> readRecovered(block: suspend () -> T): T = unsupported()

        override suspend fun runReset(block: suspend (DurableCommandReceipt) -> Unit): DurableCommandReceipt =
            unsupported()

        override suspend fun commitMutationIntent(preparation: ProfileMutationPreparation): ProfileMutationOutcome =
            unsupported()

        override suspend fun <T> mutateCatalog(
            preparation: ProfileMutationPreparation,
            block: suspend () -> T,
        ): T = unsupported()

        override suspend fun <T> mutateReservedCatalog(
            receipt: DurableCommandReceipt,
            block: suspend () -> T,
        ): T = unsupported()

        override suspend fun activateSelector(
            preparation: ProfileMutationPreparation,
            groupId: String,
            memberId: String,
            choice: com.poyka.ripdpi.data.selector.SelectorChoicePersistence,
        ): ProfileMutationOutcome = unsupported()

        override fun warpRuntimeRevision(profileId: String): Long = unsupported()

        override suspend fun upsertAwg(
            preparation: ProfileMutationPreparation,
            profile: com.poyka.ripdpi.data.awg.AwgProfileEntity,
            secrets: com.poyka.ripdpi.data.awg.AwgSecrets,
        ): ProfileMutationOutcome = unsupported()

        override suspend fun deleteAwg(
            preparation: ProfileMutationPreparation,
            profileId: String,
        ): ProfileMutationOutcome = unsupported()

        override suspend fun upsertRelay(
            preparation: ProfileMutationPreparation,
            profile: com.poyka.ripdpi.data.RelayProfileRecord,
            credentials: com.poyka.ripdpi.data.RelayCredentialRecord,
            enabled: Boolean,
            select: Boolean,
            settingsAfterImage: com.poyka.ripdpi.proto.AppSettings?,
            modeAfterImage: String?,
            xraySelectionAfterImage: com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord?,
            expectedState: com.poyka.ripdpi.data.ExpectedRelayProfileState?,
        ): ProfileMutationOutcome = unsupported()

        override suspend fun upsertWarp(
            preparation: ProfileMutationPreparation,
            profile: com.poyka.ripdpi.data.WarpProfile,
            credentials: com.poyka.ripdpi.data.WarpCredentials,
            endpoints: List<com.poyka.ripdpi.data.WarpEndpointCacheEntry>,
            activate: Boolean,
            scannerMode: String,
        ): ProfileMutationOutcome = unsupported()

        override suspend fun upsertWarpForRuntimeProvisioning(
            profile: com.poyka.ripdpi.data.WarpProfile,
            credentials: com.poyka.ripdpi.data.WarpCredentials,
            endpoints: List<com.poyka.ripdpi.data.WarpEndpointCacheEntry>,
            activate: Boolean,
            scannerMode: String,
            expectedCredentials: com.poyka.ripdpi.data.WarpCredentials,
            expectedRevision: Long,
        ): Boolean = unsupported()

        override suspend fun deleteWarp(
            preparation: ProfileMutationPreparation,
            profileId: String,
            clearActive: Boolean,
        ): ProfileMutationOutcome = unsupported()

        override suspend fun deactivateWarp(
            preparation: ProfileMutationPreparation,
            profileId: String,
        ): ProfileMutationOutcome = unsupported()

        override suspend fun replacePrivateBackup(
            preparation: ProfileMutationPreparation,
            data: com.poyka.ripdpi.data.backup.BackupPrivateDataV1,
            rollbackData: com.poyka.ripdpi.data.backup.BackupPrivateDataV1?,
        ): ProfileMutationOutcome = unsupported()

        private fun unsupported(): Nothing = error("Unexpected operation outside standalone AWG test ownership")
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
