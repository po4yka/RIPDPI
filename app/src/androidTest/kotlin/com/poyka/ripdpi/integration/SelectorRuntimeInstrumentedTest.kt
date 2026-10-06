package com.poyka.ripdpi.integration

import android.Manifest
import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.rule.GrantPermissionRule
import com.poyka.ripdpi.activities.MainActivity
import com.poyka.ripdpi.core.DefaultRipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayBindings
import com.poyka.ripdpi.core.RipDpiRelayBindingsModule
import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayNativeBindings
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseAuthorityPersistence
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.PausePhase
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProfileMutationRecoveryAccess
import com.poyka.ripdpi.data.ProfileMutationRecoveryCoordinator
import com.poyka.ripdpi.data.ProfileUtilityCatalogReader
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindShadowsocks
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.services.CandidateMeasurementStage
import com.poyka.ripdpi.services.CandidateRelayMeasurement
import com.poyka.ripdpi.services.CandidateRelayMeasurements
import com.poyka.ripdpi.services.CandidateRelayNetworkEpoch
import com.poyka.ripdpi.services.CandidateRelayPayloadProbe
import com.poyka.ripdpi.services.CandidateXrayMeasurements
import com.poyka.ripdpi.services.MeasuredProfileActivationCoordinator
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartResult
import com.poyka.ripdpi.services.TimedPauseController
import com.poyka.ripdpi.ui.screens.profiles.ProfileMeasurementUiState
import com.poyka.ripdpi.ui.screens.profiles.ProfileUtilityMeasurementCoordinator
import com.poyka.ripdpi.ui.screens.profiles.ProfileUtilityViewModel
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

/** Uses the production resolver/factory/probe and observes handles delegated to actual JNI. */
@HiltAndroidTest
@UninstallModules(RipDpiRelayBindingsModule::class)
class SelectorRuntimeInstrumentedTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val notificationPermissionRule: TestRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TestRule { statement, _ -> statement }
        }

    @Inject lateinit var candidateProbe: CandidateRelayPayloadProbe

    @Inject lateinit var settings: AppSettingsRepository

    @Inject lateinit var relayFactory: RipDpiRelayFactory

    @Inject lateinit var authority: PauseIntentAuthority

    @Inject lateinit var persistence: PauseAuthorityPersistence

    @Inject lateinit var mutations: ProfileMutationRecoveryCoordinator

    @Inject lateinit var recovery: ProfileMutationRecoveryAccess

    @Inject lateinit var profileStore: RelayProfileStore

    @Inject lateinit var credentialStore: RelayCredentialStore

    @Inject lateinit var groups: com.poyka.ripdpi.data.ProxyGroupRepository

    @Inject lateinit var catalog: ProfileUtilityCatalogReader

    @Inject lateinit var applied: AppliedRuntimeConfigurationSource

    @Inject lateinit var state: ServiceStateStore

    @Inject lateinit var controller: ServiceController

    @Inject lateinit var pause: TimedPauseController

    @Inject lateinit var networkEpoch: CandidateRelayNetworkEpoch

    @Inject lateinit var measurements: CandidateRelayMeasurements

    @Inject lateinit var xrayMeasurements: CandidateXrayMeasurements

    @Inject lateinit var selection: MeasuredProfileActivationCoordinator

    @Inject internal lateinit var utilityMeasurement: ProfileUtilityMeasurementCoordinator

    @BindValue
    @JvmField
    val relayBindings: RipDpiRelayBindings = ObservedNativeRelayBindings()

    @Before
    fun injectProductionGraph() {
        hiltRule.inject()
        assertTrue(relayFactory is DefaultRipDpiRelayFactory)
        assertTrue((relayBindings as ObservedNativeRelayBindings).native is RipDpiRelayNativeBindings)
    }

    @Test
    fun nativeCandidateRelaysConfiguredHttpPayloadWithoutActivatingSettings() =
        runBlocking {
            verifyCandidate(truncated = false)
        }

    @Test
    fun nativeCandidateRejectsTruncatedHttpPayloadWithoutActivatingSettings() =
        runBlocking {
            verifyCandidate(truncated = true)
        }

    @Test
    fun nativeCandidateCleansFailedRuntimeBeforeNextSuccessfulMeasurement() =
        runBlocking {
            verifyCandidate(truncated = true)
            verifyCandidate(truncated = false)
        }

    @Test
    fun realProfileUtilityCheckPreservesPausedIntentAndPersistedFavoriteAfterViewModelRecreation() =
        runBlocking {
            verifyUtilitySelection(select = false)
        }

    @Test
    fun realProfileUtilityCheckAndSelectConsumesPauseAndRecordsOnlyActualNativeAck() =
        runBlocking {
            verifyUtilitySelection(select = true)
        }

    @Test
    fun realSelectorNamespacesAndGlobalFastestUseOnlyPayloadMeasurementsAndNativeAcknowledgments() =
        runBlocking {
            withTimeout(120_000L) {
                val original = settings.snapshot()
                val groupA = "utility-selector-a"
                val groupB = "utility-selector-b"
                val memberId = "shared-member"
                val referenceB = ProfileUtilityReference.SelectorMember(groupB, memberId)
                val observed = relayBindings as ObservedNativeRelayBindings
                val previousHandles = observed.handles.toSet()
                ActivityScenario.launch(MainActivity::class.java).use {
                    SharedCandidateHttpFixture(3).use { http ->
                        NativeCandidateFixture(false, http.port, 1).use { peerA ->
                            NativeCandidateFixture(false, http.port, 2).use { peerB ->
                                val models = ViewModelStore()
                                var collector: kotlinx.coroutines.Job? = null
                                try {
                                    recovery.recover()
                                    settings.update {
                                        setRipdpiMode(Mode.Proxy.preferenceValue)
                                        setEnableCmdSettings(false)
                                        setRelayEnabled(false)
                                    }
                                    for ((id, peer) in listOf(groupA to peerA, groupB to peerB)) {
                                        groups.add(
                                            com.poyka.ripdpi.data.ProxyGroup(
                                                id,
                                                id,
                                                com.poyka.ripdpi.data.ProxyGroupType.BASIC,
                                                if (id == groupA) 0 else 1,
                                                true,
                                                members =
                                                    listOf(
                                                        com.poyka.ripdpi.data.ProxyProfile.Shadowsocks(
                                                            memberId,
                                                            "Actual AES payload",
                                                            id,
                                                            "127.0.0.1",
                                                            peer.relayPort,
                                                            "aes-256-gcm",
                                                            FixturePassword,
                                                        ),
                                                    ),
                                            ),
                                        )
                                    }
                                    assertTrue(controller.start(Mode.Proxy) is ServiceStartResult.Accepted)
                                    state.status.first { it == AppStatus.Running to Mode.Proxy }
                                    pause.pause(300_000L)
                                    authority.states.first { it?.pause?.phase == PausePhase.Paused }
                                    withTimeout(5_000L) { while (networkEpoch.capture() == null) delay(20) }
                                    val vm = createUtilityViewModel()
                                    withContext(Dispatchers.Main) { models.put("selector-utility", vm) }
                                    collector = launch { vm.uiState.collect {} }
                                    vm.uiState.first { it.profiles.any { row -> row.reference == referenceB } }
                                    vm.updateUrl(http.probeUrl)
                                    vm.checkAndSelect(referenceB)
                                    applied.applications.first { values ->
                                        val selected =
                                            (values[Mode.Proxy] as? RuntimeConfigurationApplication.Applied)
                                                ?.configuration
                                                ?.effectiveSelection
                                        selected?.selectorGroupId == groupB && selected.selectorMemberId == memberId
                                    }
                                    state.status.first { it == AppStatus.Running to Mode.Proxy }
                                    assertEquals(
                                        referenceB,
                                        checkNotNull(persistence.read())
                                            .profileUtility.recents
                                            .first()
                                            .reference,
                                    )
                                    val beforeFastest =
                                        checkNotNull(
                                            persistence.read(),
                                        ).profileUtility.lastSequence
                                    vm.checkAndSelectFastest()
                                    withContext(Dispatchers.IO) { http.assertPayloadRequests() }
                                    val measured =
                                        vm.uiState.first { ui ->
                                            val entries =
                                                ui.profiles.filter { row ->
                                                    row.reference is ProfileUtilityReference.SelectorMember
                                                }
                                            entries.size == 2 &&
                                                entries.all { row ->
                                                    row.measurement is ProfileMeasurementUiState.Measured
                                                }
                                        }
                                    val expected =
                                        measured.profiles
                                            .filter { row ->
                                                row.reference is ProfileUtilityReference.SelectorMember
                                            }.minBy { row ->
                                                (row.measurement as ProfileMeasurementUiState.Measured).latencyMillis
                                            }.reference
                                    applied.applications.first { values ->
                                        val selected =
                                            (values[Mode.Proxy] as? RuntimeConfigurationApplication.Applied)
                                                ?.configuration
                                                ?.effectiveSelection
                                        val ref = expected as ProfileUtilityReference.SelectorMember
                                        selected?.selectorGroupId == ref.groupId &&
                                            selected.selectorMemberId == ref.memberId &&
                                            checkNotNull(persistence.read()).profileUtility.lastSequence >
                                            beforeFastest
                                    }
                                    state.status.first { it == AppStatus.Running to Mode.Proxy }
                                    val stored = checkNotNull(persistence.read()).profileUtility
                                    assertEquals(expected, stored.recents.first().reference)
                                    assertEquals(beforeFastest + 1, stored.lastSequence)
                                    assertEquals(1, stored.recents.count { row -> row.reference == expected })
                                    assertEquals(null, authority.snapshot())
                                    http.assertPayloadRequests()
                                    peerA.assertRelayedRequest()
                                    peerB.assertRelayedRequest()
                                } finally {
                                    collector?.cancelAndJoin()
                                    withContext(Dispatchers.Main) { models.clear() }
                                    pause.stop()
                                    state.status.first { it.first == AppStatus.Halted }
                                    observed.assertRetiredSince(previousHandles)
                                    for (id in listOf(groupA, groupB)) {
                                        groups.delete(
                                            mutations.captureMutation(ProfileMutationOrigin.ExplicitDeletion),
                                            id,
                                        )
                                    }
                                    settings.replace(original)
                                }
                            }
                        }
                    }
                }
            }
        }

    private suspend fun verifyUtilitySelection(select: Boolean) =
        withTimeout(60_000L) {
            val originalSettings = settings.snapshot()
            val profileId = if (select) "utility-native-activation" else "utility-native-check"
            val reference = ProfileUtilityReference.NativeRelay(profileId)
            val observed = relayBindings as ObservedNativeRelayBindings
            val previousHandles = observed.handles.toSet()
            ActivityScenario.launch(MainActivity::class.java).use {
                NativeCandidateFixture(truncated = false, sharedHttpPort = null, exchanges = 1).use { fixture ->
                    val viewModels = ViewModelStore()
                    var collector: kotlinx.coroutines.Job? = null
                    try {
                        recovery.recover()
                        settings.update {
                            setRipdpiMode(Mode.Proxy.preferenceValue)
                            setEnableCmdSettings(false)
                            setRelayEnabled(false)
                        }
                        val profile =
                            RelayProfileRecord(
                                id = profileId,
                                kind = RelayKindShadowsocks,
                                server = "127.0.0.1",
                                serverPort = fixture.relayPort,
                                udpEnabled = false,
                            )
                        val secret =
                            RelayCredentialRecord(
                                profileId = profileId,
                                shadowsocksMethod = "aes-256-gcm",
                                shadowsocksPassword = FixturePassword,
                            )
                        mutations.upsertRelay(
                            mutations.captureMutation(ProfileMutationOrigin.SavedEdit),
                            profile,
                            secret,
                            enabled = false,
                            select = false,
                        )
                        assertTrue(controller.start(Mode.Proxy) is ServiceStartResult.Accepted)
                        state.status.first { it == AppStatus.Running to Mode.Proxy }
                        pause.pause(300_000L)
                        val paused =
                            checkNotNull(authority.states.first { it?.pause?.phase == PausePhase.Paused }?.pause)
                        withTimeout(5_000L) { while (networkEpoch.capture() == null) delay(20) }
                        val beforeHistory = checkNotNull(persistence.read()).profileUtility.recents
                        val beforeSelection = settings.snapshot()
                        val vm = createUtilityViewModel()
                        withContext(Dispatchers.Main) { viewModels.put("profiles", vm) }
                        collector = launch { vm.uiState.collect {} }
                        val initial = vm.uiState.first { it.profiles.any { row -> row.reference == reference } }
                        vm.favorite(reference, initial.catalogGeneration, true)
                        vm.uiState.first { it.profiles.any { row -> row.reference == reference && row.favorite } }
                        vm.updateUrl(fixture.probeUrl)
                        if (select) vm.checkAndSelect(reference) else vm.check(reference)
                        vm.uiState.first { ui ->
                            ui.profiles.any { row ->
                                row.reference == reference && row.measurement is ProfileMeasurementUiState.Measured
                            }
                        }
                        fixture.assertRelayedRequest()
                        if (select) {
                            applied.applications.first { applications ->
                                (applications[Mode.Proxy] as? RuntimeConfigurationApplication.Applied)
                                    ?.configuration
                                    ?.effectiveSelection
                                    ?.profileId == profileId
                            }
                            state.status.first { it == AppStatus.Running to Mode.Proxy }
                            assertEquals(profileId, settings.snapshot().relayProfileId)
                            assertEquals(null, authority.snapshot())
                            val stored = checkNotNull(persistence.read()).profileUtility
                            assertEquals(reference, stored.recents.first().reference)
                            assertTrue(stored.recents.first().sequence > (beforeHistory.firstOrNull()?.sequence ?: 0))
                            assertEquals(1, stored.recents.count { row -> row.reference == reference })
                        } else {
                            assertEquals(paused, authority.snapshot())
                            assertEquals(beforeSelection, settings.snapshot())
                            assertEquals(beforeHistory, checkNotNull(persistence.read()).profileUtility.recents)
                            observed.assertRetiredSince(previousHandles)
                        }
                        assertTrue(reference in checkNotNull(persistence.read()).profileUtility.favorites)
                        collector.cancelAndJoin()
                        collector = null
                        withContext(Dispatchers.Main) { viewModels.clear() }
                        val recreated = createUtilityViewModel()
                        withContext(Dispatchers.Main) { viewModels.put("profiles", recreated) }
                        collector = launch { recreated.uiState.collect {} }
                        val restored =
                            recreated.uiState.first { ui ->
                                ui.profiles.any { row ->
                                    row.reference == reference && row.favorite
                                }
                            }
                        assertEquals(
                            select,
                            restored.profiles.first { row -> row.reference == reference }.recentSequence != null,
                        )
                    } finally {
                        collector?.cancelAndJoin()
                        withContext(Dispatchers.Main) { viewModels.clear() }
                        pause.stop()
                        state.status.first { it.first == AppStatus.Halted }
                        observed.assertRetiredSince(previousHandles)
                        recovery.mutateCatalog(mutations.captureMutation(ProfileMutationOrigin.ExplicitDeletion)) {
                            profileStore.clear(profileId)
                            credentialStore.clear(profileId)
                        }
                        settings.update {
                            clear()
                            mergeFrom(originalSettings)
                        }
                    }
                }
            }
        }

    private fun createUtilityViewModel() =
        ProfileUtilityViewModel(
            catalog,
            authority,
            recovery,
            applied,
            utilityMeasurement,
            selection,
            measurements,
            xrayMeasurements,
        )

    private suspend fun verifyCandidate(truncated: Boolean) =
        withTimeout(30_000L) {
            val before = settings.snapshot()
            val observedBindings = relayBindings as ObservedNativeRelayBindings
            val previousHandles = observedBindings.handles.toSet()
            NativeCandidateFixture(truncated, sharedHttpPort = null, exchanges = 1).use { fixture ->
                val profile =
                    RelayProfileRecord(
                        id = "instrumented-ephemeral-candidate",
                        kind = RelayKindShadowsocks,
                        server = "127.0.0.1",
                        serverPort = fixture.relayPort,
                        udpEnabled = false,
                    )
                val credentials =
                    RelayCredentialRecord(
                        profileId = profile.id,
                        shadowsocksMethod = "aes-256-gcm",
                        shadowsocksPassword = FixturePassword,
                    )
                val latency = candidateProbe.measure(profile, credentials, fixture.probeUrl)
                observedBindings.assertRetiredSince(previousHandles)
                // Both the decrypted relay target and HTTP request must be observed. Direct HTTP
                // or a listener-readiness-only measurement cannot satisfy these assertions.
                fixture.assertRelayedRequest()
                if (truncated) {
                    assertEquals(
                        "Partial HTTP bodies must not count as candidate health",
                        CandidateRelayMeasurement.Failed(CandidateMeasurementStage.Http),
                        latency,
                    )
                } else {
                    assertTrue(
                        "A complete payload through the native candidate must succeed",
                        latency is CandidateRelayMeasurement.Succeeded,
                    )
                    assertTrue((latency as CandidateRelayMeasurement.Succeeded).latencyMillis >= 0L)
                }
                assertEquals(
                    "Candidate probing must not activate or edit persisted settings",
                    before,
                    settings.snapshot(),
                )
            }
        }
}

/** Records creation only; readiness, start, stop, telemetry and retirement all call real JNI. */
private class ObservedNativeRelayBindings(
    val native: RipDpiRelayBindings = RipDpiRelayNativeBindings(),
) : RipDpiRelayBindings by native {
    val handles = CopyOnWriteArrayList<Long>()

    override fun create(configJson: String): Long =
        native.create(configJson).also { handle ->
            handles.add(handle)
        }

    fun assertRetiredSince(previousHandles: Set<Long>) {
        val created = handles.filterNot(previousHandles::contains)
        assertTrue("A real native session must have been created", created.isNotEmpty())
        assertTrue("Native JNI must accept the candidate configuration", created.all { it != 0L })
        val unknownHandleTelemetry = native.pollTelemetry(0L)
        assertNotNull(unknownHandleTelemetry)
        created.forEach { handle ->
            assertEquals(
                "Candidate cleanup must retire its native registry handle before returning",
                unknownHandleTelemetry,
                native.pollTelemetry(handle),
            )
        }
    }
}

/** SIP004 AES-256-GCM peer forwarding to an independently bound loopback HTTP server. */
private class NativeCandidateFixture(
    truncated: Boolean,
    sharedHttpPort: Int?,
    private val exchanges: Int,
) : Closeable {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val relay = ServerSocket(0, 1, loopback)
    private val http = ServerSocket(0, 1, loopback)
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val executor = Executors.newFixedThreadPool(2)
    private val expectedPort = sharedHttpPort ?: http.localPort
    val relayPort: Int = relay.localPort
    val probeUrl = "http://127.0.0.1:${http.localPort}$FixturePath"
    private val httpRequest =
        if (sharedHttpPort == null) {
            executor.submit<String> {
                accept(http).use { socket ->
                    val request = readHttpHeaders(DataInputStream(socket.getInputStream()))
                    val body = "candidate-payload"
                    val length = body.length + if (truncated) 7 else 0
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: $length\r\nConnection: close\r\n\r\n$body".toByteArray(),
                    )
                    socket.getOutputStream().flush()
                    request
                }
            }
        } else {
            null
        }
    private val relayTarget =
        executor.submit<List<Int>> {
            List(exchanges) {
                accept(relay).use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val decoder = FixtureAead(input.readExact(32))
                    val first = decoder.readFrame(input)
                    val addressLength =
                        when (first[0].toInt()) {
                            1 -> 7
                            3 -> 4 + (first[1].toInt() and 0xff)
                            else -> error("Unexpected target address family")
                        }
                    val host =
                        if (first[0].toInt() == 1) {
                            InetAddress.getByAddress(first.copyOfRange(1, 5)).hostAddress
                        } else {
                            first.copyOfRange(2, addressLength - 2).toString(Charsets.UTF_8)
                        }
                    assertEquals("127.0.0.1", host)
                    val targetPort =
                        ((first[addressLength - 2].toInt() and 0xff) shl 8) or
                            (first[addressLength - 1].toInt() and 0xff)
                    assertEquals(expectedPort, targetPort)
                    val request = ByteArrayOutputStream()
                    request.write(first, addressLength, first.size - addressLength)
                    while (!request.toString("UTF-8").endsWith("\r\n\r\n")) {
                        check(request.size() < 16_384) { "HTTP headers exceeded fixture bound" }
                        request.write(decoder.readFrame(input))
                    }
                    Socket(loopback, targetPort).also(sockets::add).use { target ->
                        target.soTimeout = FixtureTimeoutMillis
                        target.getOutputStream().write(request.toByteArray())
                        target.getOutputStream().flush()
                        val response = target.getInputStream().readBytes()
                        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
                        val encoder = FixtureAead(salt)
                        socket.getOutputStream().write(salt + encoder.writeFrame(response))
                        socket.getOutputStream().flush()
                    }
                    targetPort
                }
            }
        }

    fun assertRelayedRequest() {
        assertEquals(List(exchanges) { expectedPort }, relayTarget.get(2, TimeUnit.SECONDS))
        httpRequest?.let { request ->
            assertEquals("GET $FixturePath HTTP/1.1", request.get(2, TimeUnit.SECONDS).lineSequence().first())
        }
    }

    private fun accept(server: ServerSocket): Socket =
        server.accept().also {
            it.soTimeout = FixtureTimeoutMillis
            sockets.add(it)
        }

    override fun close() {
        relay.close()
        http.close()
        sockets.forEach { it.close() }
        executor.shutdownNow()
        check(executor.awaitTermination(2, TimeUnit.SECONDS)) { "Fixture threads did not terminate" }
    }
}

/** One independent HTTP endpoint serves every member's genuine encrypted payload request. */
private class SharedCandidateHttpFixture(
    private val expected: Int,
) : Closeable {
    private val server = ServerSocket(0, expected, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newSingleThreadExecutor()
    val port = server.localPort
    val probeUrl = "http://127.0.0.1:$port$FixturePath"
    private val requests =
        executor.submit<List<String>> {
            List(expected) {
                server.accept().use { socket ->
                    socket.soTimeout = FixtureTimeoutMillis
                    val request = readHttpHeaders(DataInputStream(socket.getInputStream()))
                    val body = "actual-shared-selector-payload"
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                            .toByteArray(),
                    )
                    socket.getOutputStream().flush()
                    request
                }
            }
        }

    fun assertPayloadRequests() {
        val actual = requests.get(30, TimeUnit.SECONDS)
        assertEquals(expected, actual.size)
        assertTrue(actual.all { it.lineSequence().first() == "GET $FixturePath HTTP/1.1" })
    }

    override fun close() {
        server.close()
        executor.shutdownNow()
        check(executor.awaitTermination(2, TimeUnit.SECONDS)) { "Shared HTTP fixture did not terminate" }
    }
}

/** Mirrors native/rust/crates/ripdpi-shadowsocks/src/{cipher,tcp}.rs SIP004 framing. */
private class FixtureAead(
    salt: ByteArray,
) {
    private val key: ByteArray
    private var counter = 0L

    init {
        val password = FixturePassword.toByteArray()
        val md5 = MessageDigest.getInstance("MD5")
        val first = md5.digest(password)
        val master = first + md5.digest(first + password)
        val extract = Mac.getInstance("HmacSHA1")
        extract.init(SecretKeySpec(salt, "HmacSHA1"))
        val prk = extract.doFinal(master)
        val expand = Mac.getInstance("HmacSHA1")
        expand.init(SecretKeySpec(prk, "HmacSHA1"))
        val info = "ss-subkey".toByteArray()
        val block = expand.doFinal(info + byteArrayOf(1))
        key = (block + expand.doFinal(block + info + byteArrayOf(2))).copyOf(32)
    }

    fun readFrame(input: DataInputStream): ByteArray {
        val lengthBytes = transform(Cipher.DECRYPT_MODE, input.readExact(18))
        val length = ((lengthBytes[0].toInt() and 0xff) shl 8) or (lengthBytes[1].toInt() and 0xff)
        check(length <= 0x3fff) { "Invalid SIP004 frame length" }
        return transform(Cipher.DECRYPT_MODE, input.readExact(length + 16))
    }

    fun writeFrame(payload: ByteArray): ByteArray {
        check(payload.size <= 0x3fff)
        val length = byteArrayOf((payload.size ushr 8).toByte(), payload.size.toByte())
        return transform(Cipher.ENCRYPT_MODE, length) + transform(Cipher.ENCRYPT_MODE, payload)
    }

    private fun transform(
        mode: Int,
        bytes: ByteArray,
    ): ByteArray {
        val nonce = ByteArray(12)
        val observed = counter++
        repeat(8) { index -> nonce[index] = (observed ushr (8 * index)).toByte() }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(bytes)
        }
    }
}

private fun DataInputStream.readExact(size: Int): ByteArray = ByteArray(size).also(::readFully)

private fun readHttpHeaders(input: DataInputStream): String {
    val bytes = ByteArrayOutputStream()
    while (!bytes.toString("UTF-8").endsWith("\r\n\r\n")) {
        check(bytes.size() < 16_384) { "HTTP headers exceeded fixture bound" }
        bytes.write(input.readUnsignedByte())
    }
    return bytes.toString("UTF-8")
}

private const val FixturePassword = "loopback-instrumented-only"
private const val FixturePath = "/selector/custom-health?contract=payload"
private const val FixtureTimeoutMillis = 10_000
