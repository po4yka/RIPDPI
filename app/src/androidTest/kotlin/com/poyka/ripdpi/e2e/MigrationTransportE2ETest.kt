package com.poyka.ripdpi.e2e

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProfileMutationRecoveryCoordinator
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.selector.SelectorActiveGroupStore
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import com.poyka.ripdpi.services.PhysicalNetworkObserver
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartResult
import com.poyka.ripdpi.services.SplitTunnelMode
import com.poyka.ripdpi.subscription.DeviceMigrationCoordinator
import com.poyka.ripdpi.subscription.MigrationCheckResult
import com.poyka.ripdpi.subscription.SubscriptionExpiryClock
import com.poyka.ripdpi.subscription.SubscriptionRefreshCoordinator
import com.poyka.ripdpi.subscription.SubscriptionRefreshResult
import com.poyka.ripdpi.subscription.SubscriptionStatusNotifier
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Real saved subscription, HTTPS refresh, app TUN, candidate measurement and explicit receipt. */
@HiltAndroidTest
class MigrationTransportE2ETest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var groups: ProxyGroupRepository

    @Inject lateinit var awg: AwgProfileRepository

    @Inject internal lateinit var notifier: SubscriptionStatusNotifier

    @Inject lateinit var clock: SubscriptionExpiryClock

    @Inject lateinit var migration: DeviceMigrationCoordinator

    @Inject lateinit var selections: SelectorSelectionStore

    @Inject lateinit var activeGroup: SelectorActiveGroupStore

    @Inject lateinit var provider: XrayProviderSelectionStore

    @Inject lateinit var settings: AppSettingsRepository

    @Inject lateinit var controller: ServiceController

    @Inject lateinit var state: ServiceStateStore

    @Inject lateinit var applied: AppliedRuntimeConfigurationSource

    @Inject lateinit var mutations: ProfileMutationRecoveryCoordinator

    @Inject lateinit var epoch: PhysicalNetworkObserver

    @Test
    fun savedReserveAndIndependentRefreshProduceTestedMigrationReceipt() {
        check(InstrumentationRegistry.getArguments().getString("ripdpi.usbDataScope") != "true")
        exerciseIndependentRefresh()
    }

    @Test
    fun usbSavedReserveAndIndependentRefreshProduceTestedMigrationReceipt() {
        check(InstrumentationRegistry.getArguments().getString("ripdpi.usbDataScope") == "true")
        exerciseIndependentRefresh()
    }

    private fun exerciseIndependentRefresh() {
        val args = InstrumentationRegistry.getArguments()
        check(isLikelyEmulator() || args.getString("ripdpi.authorizedPhysicalDevice") == "true")
        val runId = requireNotNull(args.getString("ripdpi.acceptanceRunId"))
        require(runId.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}")))
        val host = args.getString("ripdpi.migrationControlHost") ?: "10.0.2.2"
        val updatePort = requireNotNull(args.getString("ripdpi.migrationHttpsPort")).toInt()
        val controlPort = requireNotNull(args.getString("ripdpi.migrationControlPort")).toInt()
        val oldPort = requireNotNull(args.getString("ripdpi.oldControlPort")).toInt()
        val reservePort = requireNotNull(args.getString("ripdpi.reserveControlPort")).toInt()
        val replacementPort = requireNotNull(args.getString("ripdpi.replacementControlPort")).toInt()

        fun control(
            port: Int,
            path: String,
            method: String = "GET",
        ): String {
            val connection = URI("http://$host:$port/$path").toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 3_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = false
                check(connection.responseCode == 200)
                return connection.inputStream.use { it.readBytes().decodeToString() }
            } finally {
                connection.disconnect()
            }
        }
        hilt.inject()
        val context: Context = ApplicationProvider.getApplicationContext()
        val originalSettings = runBlocking { settings.snapshot() }
        val originalProvider = runBlocking { provider.current() }
        val originalActive = activeGroup.activeGroupId.value
        val groupId = "owned-migration-${UUID.randomUUID()}"
        try {
            runBlocking { controller.stop() }
            awaitUntil { state.status.value.first == AppStatus.Halted }
            ensureLocalNetworkAccessGranted(context)
            ensureLocalNetworkAccessGranted(InstrumentationRegistry.getInstrumentation().context)
            ensureVpnConsentGranted(context)
            val certificate =
                CertificateFactory.getInstance("X.509").generateCertificate(
                    ByteArrayInputStream(
                        Base64.getDecoder().decode(requireNotNull(args.getString("ripdpi.migrationCertificate"))),
                    ),
                )
            val trusted =
                KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null)
                    setCertificateEntry("owned", certificate)
                }
            val trust =
                TrustManagerFactory
                    .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    .apply { init(trusted) }
                    .trustManagers
                    .single() as X509TrustManager
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            val http =
                OkHttpClient
                    .Builder()
                    .proxy(Proxy.NO_PROXY)
                    .sslSocketFactory(tls.socketFactory, trust)
                    .dns { name ->
                        check(name == "fixture.test")
                        listOf(InetAddress.getByName(host))
                    }.build()
            val refresh = SubscriptionRefreshCoordinator(groups, awg, notifier, http, clock::nowMillis, Unit)
            runBlocking {
                settings.update {
                    ripdpiMode = Mode.VPN.preferenceValue
                    rootModeEnabled = false
                    enableCmdSettings = false
                    ipv6Enable = false
                    setSplitTunnelMode(SplitTunnelMode.Off)
                    clearSplitTunnelPackages()
                    relayEnabled = false
                }
                provider.update(XrayProviderSelectionRecord())
                groups.add(
                    ProxyGroup(
                        groupId,
                        "Owned migration acceptance",
                        ProxyGroupType.SUBSCRIPTION,
                        0,
                        true,
                        subscription = Subscription(link = "https://fixture.test:$updatePort/subscription"),
                    ),
                )
                assertTrue(refresh.refresh(groupId) is SubscriptionRefreshResult.Updated)
            }
            val imported = runBlocking { groups.list().single { it.id == groupId } }
            val old = imported.members.single { it.displayName == "old" }
            val reserve = imported.members.single { it.displayName == "reserve" }
            assertFalse(requireNotNull(imported.subscription).mirrors.mirrors.isEmpty())

            fun activate(member: String) {
                runBlocking {
                    controller.stop()
                    selections.select(groupId, member)
                    assertTrue(controller.start(Mode.VPN) is ServiceStartResult.Accepted)
                }
                awaitServiceStatus(state, AppStatus.Running, Mode.VPN, timeoutMs = 30_000)
                awaitUntil(timeoutMs = 30_000) {
                    val effective =
                        (applied.applications.value[Mode.VPN] as? RuntimeConfigurationApplication.Applied)
                            ?.configuration
                            ?.effectiveSelection
                    effective?.selectorGroupId == groupId && effective.selectorMemberId == member
                }
            }

            fun transfer(
                label: String,
                peer: Int,
            ) {
                val id = "migration-$label-${SystemClock.elapsedRealtime()}"
                bindTestProcessDnsProbeService(10_000).use { probe ->
                    assertTrue(probe.awaitVpnDefaultNetwork(10_000))
                    val result = probe.transfer("download", id)
                    assertTrue("Migration TUN transfer failed: $result", result.getBoolean("ok"))
                    assertTrue(result.getInt("uid") != Process.myUid())
                    awaitUntil {
                        val receipts = JSONArray(control(peer, "transport-receipts"))
                        (0 until receipts.length()).any { index ->
                            val receipt = receipts.getJSONObject(index)
                            receipt.getString("id") == id && receipt.getInt("bytes") == result.getInt("bytes") &&
                                receipt.getString("sha256") == result.getString("sha256")
                        }
                    }
                }
            }
            activate(old.id)
            transfer("old", oldPort)
            control(oldPort, "peer/stop", "POST")
            bindTestProcessDnsProbeService(10_000).use { probe ->
                assertFalse(probe.transfer("download", "migration-old-unreachable").getBoolean("ok"))
            }
            // This reserve is read from storage before any further subscription network request.
            val saved = runBlocking { groups.list().single { it.id == groupId } }
            assertEquals(reserve, saved.members.single { it.id == reserve.id })
            activate(reserve.id)
            transfer("saved-reserve", reservePort)
            control(controlPort, "revision/next", "POST")
            control(controlPort, "subscription/stop", "POST")
            val refreshResult = runBlocking { refresh.refresh(groupId) }
            val deliveryReceipts =
                runCatching {
                    val updates = JSONArray(control(controlPort, "receipts"))
                    val summary =
                        (0 until updates.length()).joinToString { index ->
                            val receipt = updates.getJSONObject(index)
                            val endpoint = if (receipt.getString("path") == "/mirror") "mirror" else "original"
                            "$endpoint:${receipt.getInt("revision")}:${receipt.getInt("status")}:" +
                                "authorized=${receipt.getBoolean("authorized")}"
                        }
                    updates to summary
                }
            val deliverySummary =
                deliveryReceipts.fold(
                    onSuccess = { it.second },
                    onFailure = { "unavailable:${it.javaClass.simpleName}" },
                )
            assertTrue(
                "Independent refresh result=$refreshResult delivery=[$deliverySummary]",
                refreshResult is SubscriptionRefreshResult.Updated,
            )
            val updates = deliveryReceipts.getOrThrow().first
            val updated = runBlocking { groups.list().single { it.id == groupId } }
            assertFalse(updated.members.any { it.displayName == "old" })
            val replacement = updated.members.single { it.displayName == "replacement" }
            assertFalse(imported.members.any { it.id == replacement.id })
            val received = JSONObject(requireNotNull(runBlocking { migration.receipt(groupId) }))
            assertEquals("received", received.getString("state"))
            assertTrue(
                (0 until updates.length()).any { index ->
                    val receipt = updates.getJSONObject(index)
                    receipt.getString("path") == "/mirror" && receipt.getInt("revision") == 1 &&
                        receipt.getBoolean("authorized") && receipt.getInt("status") == 200
                },
            )
            awaitUntil(timeoutMs = 30_000) { epoch.capture() != null }
            val candidateId = "migration-candidate-${SystemClock.elapsedRealtime()}"
            val candidateUrl = "http://192.0.2.77/transport/download?id=$candidateId&bytes=65536"
            control(replacementPort, "peer/stop", "POST")
            try {
                assertEquals(
                    MigrationCheckResult.Failed,
                    runBlocking { migration.check(groupId, "replacement", candidateUrl) },
                )
                assertFalse(runBlocking { migration.hasCurrentTest(groupId) })
                assertEquals(
                    "received",
                    JSONObject(requireNotNull(runBlocking { migration.receipt(groupId) })).getString("state"),
                )
                assertEquals(0, JSONArray(control(replacementPort, "transport-receipts")).length())
            } finally {
                control(replacementPort, "peer/start", "POST")
            }
            assertEquals(
                MigrationCheckResult.Tested,
                runBlocking { migration.check(groupId, "replacement", candidateUrl) },
            )
            val receipt = requireNotNull(runBlocking { migration.receipt(groupId) })
            val parsed = JSONObject(receipt)
            assertEquals("path-tested", parsed.getString("state"))
            assertEquals("replacement", parsed.getString("tested_tag"))
            assertTrue(runBlocking { migration.hasCurrentTest(groupId) })
            val destination = JSONArray(control(replacementPort, "transport-receipts"))
            assertTrue(
                (0 until destination.length()).any { destination.getJSONObject(it).getString("id") == candidateId },
            )
            control(oldPort, "peer/stop", "POST")
            activate(replacement.id)
            transfer("after-refresh", replacementPort)
            val file = File(context.filesDir, "migration-acceptance-$runId.json")
            check(file.createNewFile())
            file.writeText(receipt)
        } finally {
            runBlocking {
                controller.stop()
                groups.delete(mutations.captureMutation(ProfileMutationOrigin.ExplicitDeletion), groupId)
                selections.clearSelection(groupId)
                activeGroup.restoreActiveGroup(originalActive)
                provider.update(originalProvider)
                settings.replace(originalSettings)
            }
            clearTestProbeNetworkEligibility()
        }
    }
}
