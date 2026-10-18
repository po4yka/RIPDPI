package com.poyka.ripdpi.e2e

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.DesiredRuntimeState
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeActivationPhase
import com.poyka.ripdpi.data.RuntimeAuthoritySnapshot
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.DefaultServiceController
import com.poyka.ripdpi.services.ServiceController
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/** Private recovery material. Never pull protobufs or this journal into external artifacts. */
internal class HomeReconnectOwnershipJournal(
    private val context: Context,
    private val directory: File,
) {
    private val marker get() = File(directory, "ownership-private.json")

    fun prepare(original: AppSettings) {
        check(directory.exists() || directory.mkdirs())
        check(!marker.exists() && !File(directory, "saved-settings-before.pb").exists()) { "Use a unique run ID" }
        persistPrivateBytes(File(directory, "saved-settings-before.pb"), original.toByteArray())
        persist(
            JSONObject().put("state", "PREPARED").put("boot", boot()).put("createdAt", SystemClock.elapsedRealtime()),
        )
    }

    fun activate(
        mode: Mode,
        candidates: List<AppSettings>,
        baselineIds: Set<String>,
        commandId: String?,
    ) {
        val state = read()
        check(state.getString("state") == "PREPARED")
        check(candidates.size == 3 && candidates.distinct().size == 3)
        candidates.forEachIndexed { index, value ->
            persistPrivateBytes(File(directory, "owned-settings-$index.pb"), value.toByteArray())
        }
        persist(
            state
                .put("state", "ACTIVE")
                .put("mode", mode.name)
                .put("baselineIds", JSONArray(baselineIds.toList()))
                .put("commandId", commandId ?: JSONObject.NULL)
                .put("runtimes", JSONArray()),
        )
    }

    fun recordCommand(commandId: String) = persist(requireActive().put("commandId", commandId))

    fun recordRuntime(
        configuration: AppliedRuntimeConfiguration,
        authority: RuntimeAuthoritySnapshot,
    ) {
        val state = requireActive()
        check(
            isCoherent(configuration, authority),
        ) { "Native and durable receipts do not identify the same applied runtime" }
        val commandId = checkNotNull(authority.command).commandId
        if (state.getJSONArray("runtimes").length() == 0) {
            check(commandId == state.getString("commandId")) { "Initial start command was superseded" }
        } else {
            check(commandId != state.getString("commandId")) { "Replacement must use a new durable command" }
        }
        check(configuration.mode.name == state.getString("mode"))
        check(configuration.runtimeId !in strings(state.getJSONArray("baselineIds"))) {
            "A stopped receipt is not a new runtime"
        }
        state.getJSONArray("runtimes").put(configuration.toEvidence())
        persist(state.put("commandId", commandId))
    }

    fun isCoherent(
        configuration: AppliedRuntimeConfiguration,
        authority: RuntimeAuthoritySnapshot,
    ): Boolean {
        val command = authority.command ?: return false
        val identity = (command.phase as? RuntimeActivationPhase.Applied)?.identity ?: return false
        return authority.desired == DesiredRuntimeState.Running &&
            authority.desiredMode == configuration.mode.preferenceValue &&
            authority.reference.generation == command.generation &&
            identity.runtimeId == configuration.runtimeId && identity.revision == configuration.revision &&
            identity.mode == configuration.mode.preferenceValue
    }

    fun requireOwnedSettings(current: AppSettings) {
        requireActive()
        check(current == original() || (0..2).any { current == readSettings("owned-settings-$it.pb") }) {
            "Current settings belong to another operation; refuse restoration"
        }
    }

    private fun requireOwnedRuntime(
        state: ServiceStateStore,
        applications: AppliedRuntimeConfigurationSource,
        authority: RuntimeAuthoritySnapshot,
    ) {
        val journal = requireActive()
        val expectedCommand = if (journal.isNull("commandId")) null else journal.getString("commandId")
        check(authority.command?.commandId == expectedCommand) { "Durable command ownership changed" }
        if (state.status.value.first == AppStatus.Halted) return
        val mode = Mode.valueOf(journal.getString("mode"))
        check(state.status.value == AppStatus.Running to mode) { "Unknown or transient runtime owner" }
        val configuration =
            (applications.applications.value[mode] as? RuntimeConfigurationApplication.Applied)
                ?.configuration
        check(
            configuration != null && isCoherent(configuration, authority) &&
                (0 until journal.getJSONArray("runtimes").length()).any { index ->
                    journal.getJSONArray("runtimes").getJSONObject(index).toString() ==
                        configuration.toEvidence().toString()
                },
        ) { "No recorded positive receipt for the current real runtime" }
    }

    /** Checked stop reservation fences a new command between ownership validation and dispatch. */
    fun restore(
        settings: AppSettingsRepository,
        services: ServiceController,
        state: ServiceStateStore,
        applications: AppliedRuntimeConfigurationSource,
    ) {
        requireOwnedSettings(runBlocking { settings.snapshot() })
        val authority = runBlocking { services.captureRuntimeSnapshot() }
        requireOwnedRuntime(state, applications, authority)
        val stop = checkNotNull(runBlocking { services.prepareStopIfCurrent(authority) }) { "Stop owner changed" }
        persist(requireActive().put("commandId", stop.commandId))
        check(services.stopPrepared(stop)) { "Checked owned stop was not dispatched" }
        HomeReconnectNetworkAcceptanceSupport.await("Owned real service failed to halt") {
            state.status.value.first ==
                AppStatus.Halted
        }
        requireOwnedSettings(runBlocking { settings.snapshot() })
        requireOwnedRuntime(state, applications, runBlocking { services.captureRuntimeSnapshot() })
        runBlocking { settings.replace(original()) }
        check(runBlocking { settings.snapshot() } == original()) { "Exact private settings restoration failed" }
        persist(requireActive().put("state", "RESTORED"))
    }

    fun finishUnchanged(settings: AppSettingsRepository) {
        val journal = read()
        check(journal.getString("state") == "PREPARED")
        check(runBlocking { settings.snapshot() } == original()) { "Unowned setup changed settings" }
        persist(journal.put("state", "RESTORED"))
    }

    fun isActive(): Boolean = marker.exists() && read().getString("state") == "ACTIVE"

    private fun requireActive(): JSONObject {
        val journal = read()
        check(journal.getString("state") == "ACTIVE") { "Inactive or completed recovery replay refused" }
        check(journal.getInt("boot") == boot()) { "Recovery belongs to another device boot" }
        check(
            SystemClock.elapsedRealtime() - journal.getLong("createdAt") in 0L..3_600_000L,
        ) { "Stale recovery refused" }
        return journal
    }

    private fun boot(): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).also {
            check(it >= 0)
        }

    private fun read(): JSONObject = JSONObject(marker.readText())

    private fun original(): AppSettings = readSettings("saved-settings-before.pb")

    private fun readSettings(name: String): AppSettings =
        File(directory, name).inputStream().use(AppSettings::parseFrom)

    private fun persist(value: JSONObject) = persistPrivateBytes(marker, value.toString().encodeToByteArray())

    private fun strings(array: JSONArray): Set<String> = (0 until array.length()).map(array::getString).toSet()
}

internal fun persistPrivateBytes(
    destination: File,
    bytes: ByteArray,
) {
    val temporary = File(destination.parentFile, "${destination.name}.tmp")
    FileOutputStream(temporary).use { output ->
        output.write(bytes)
        output.fd.sync()
    }
    check(temporary.renameTo(destination)) { "Private recovery data could not be persisted" }
}

/** Default suite discovery is not a device acceptance run. Selected manual lanes remain strict. */
internal fun explicitReconnectDriverRequest(arguments: android.os.Bundle): Boolean =
    listOf("ripdpi.acceptanceRunId", "ripdpi.reconnectRecovery", "ripdpi.reconnectTalkBack")
        .any(arguments::containsKey)

internal fun requireExternalReconnectDriver() {
    org.junit.Assume.assumeTrue(
        "External-driver acceptance is not part of implicit suite discovery",
        explicitReconnectDriverRequest(InstrumentationRegistry.getArguments()),
    )
}

/** Recovery only. A completed/stale journal or an unrelated runtime must fail before mutation. */
@HiltAndroidTest
class HomeReconnectSettingsRestorationTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var services: ServiceController

    @Inject lateinit var settings: AppSettingsRepository

    @Inject lateinit var state: ServiceStateStore

    @Inject lateinit var applications: AppliedRuntimeConfigurationSource

    @Test
    fun restoreOwnedSettingsAfterInterruptedAcceptance() {
        requireExternalReconnectDriver()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("ripdpi.reconnectRecovery") == "true")
        val runId = checkNotNull(arguments.getString("ripdpi.acceptanceRunId"))
        check(runId.matches(Regex("[A-Za-z0-9-]{1,80}")))
        val method = checkNotNull(arguments.getString("ripdpi.reconnectRecoveryMethod"))
        check(
            method in
                setOf(
                    "vpnHomeReviewCancelStaleAndConfirmUseActualTunnelAndDns",
                    "proxyHomeReviewCancelStaleAndConfirmUseActualNativeSocksAndDns",
                ),
        )
        hilt.inject()
        check(services is DefaultServiceController)
        val context: Context = ApplicationProvider.getApplicationContext()
        val directory = File(context.filesDir, "home-reconnect-acceptance/$runId/$method")
        HomeReconnectOwnershipJournal(context, directory).restore(settings, services, state, applications)
        val receipt = JSONObject().put("recoveryOnly", true).put("settingsExact", true).put("serviceHalted", true)
        persistPrivateBytes(File(directory, "recovery-restored.json"), receipt.toString(2).encodeToByteArray())
    }
}

/** Exercises real private-journal validation; no service binding or fake runtime is needed. */
class HomeReconnectOwnershipJournalTest {
    @Test
    fun defaultDiscoveryAndClassFiltersAreImplicitUntilDriverOptIn() {
        check(!explicitReconnectDriverRequest(android.os.Bundle()))
        val className = HomeReconnectNetworkAcceptanceTest::class.java.name
        check(
            !explicitReconnectDriverRequest(android.os.Bundle().apply { putString("package", "com.poyka.ripdpi.e2e") }),
        )
        listOf(className, "$className#selectedMethod", "other.Class, $className#selectedMethod").forEach { selector ->
            // Orchestrator generates class selectors for implicit tests; class alone is not driver opt-in.
            check(!explicitReconnectDriverRequest(android.os.Bundle().apply { putString("class", selector) }))
        }
        listOf("ripdpi.acceptanceRunId", "ripdpi.reconnectRecovery", "ripdpi.reconnectTalkBack").forEach { key ->
            // Presence means explicit, even false/null. Strict setup must reject missing or invalid values.
            check(explicitReconnectDriverRequest(android.os.Bundle().apply { putString(key, "false") }))
            check(explicitReconnectDriverRequest(android.os.Bundle().apply { putString(key, null) }))
        }
    }

    @Test
    fun coherentRuntimeRejectsAnotherCommandRevisionAndMode() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val directory = File(context.cacheDir, "reconnect-journal-${java.util.UUID.randomUUID()}")
        val journal = HomeReconnectOwnershipJournal(context, directory)
        val original = com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue
        val configuration =
            AppliedRuntimeConfiguration(
                "actual-runtime",
                1L,
                1L,
                Mode.VPN,
                com.poyka.ripdpi.data
                    .RuntimeConfigurationSelection("native"),
                com.poyka.ripdpi.data
                    .RuntimeConfigurationSelection("native"),
                com.poyka.ripdpi.data
                    .RuntimeConfigurationDns("encrypted", "custom", "doh"),
                com.poyka.ripdpi.data
                    .RuntimeConfigurationStrategy(false),
                com.poyka.ripdpi.data.RuntimeConfigurationApplyReason.InitialStart,
            )
        val identity =
            com.poyka.ripdpi.data
                .RuntimeAppliedUseIdentity(configuration.runtimeId, 1L, "vpn")
        val command =
            com.poyka.ripdpi.data.DurableCommandRecord(
                1L,
                "owned-command",
                com.poyka.ripdpi.data.RuntimeCommandOrigin.UserStart,
                RuntimeActivationPhase.Applied(identity, com.poyka.ripdpi.data.RuntimeClaimKind.Activation),
            )
        val authority =
            RuntimeAuthoritySnapshot(
                com.poyka.ripdpi.data
                    .PauseAuthorityRef(1L),
                DesiredRuntimeState.Running,
                "vpn",
                null,
                command,
            )
        try {
            journal.prepare(original)
            journal.activate(
                Mode.VPN,
                (1..3).map {
                    original.toBuilder().setProxyPort(20_000 + it).build()
                },
                emptySet(),
                command.commandId,
            )
            check(journal.isCoherent(configuration, authority))
            check(!journal.isCoherent(configuration.copy(revision = 2L), authority))
            check(!journal.isCoherent(configuration.copy(mode = Mode.Proxy), authority))
            check(!journal.isCoherent(configuration.copy(runtimeId = "other-runtime"), authority))
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                journal.recordRuntime(
                    configuration,
                    authority.copy(command = command.copy(commandId = "foreign-command")),
                )
            }
            journal.recordRuntime(configuration, authority)
        } finally {
            check(directory.deleteRecursively())
        }
    }

    @Test
    fun inactiveCompletedAndMismatchedSettingsRefuseRecovery() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val directory = File(context.cacheDir, "reconnect-journal-${java.util.UUID.randomUUID()}")
        val journal = HomeReconnectOwnershipJournal(context, directory)
        val original = com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue
        val owned = (1..3).map { original.toBuilder().setProxyPort(20_000 + it).build() }
        try {
            journal.prepare(original)
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { journal.requireOwnedSettings(original) }
            journal.activate(Mode.VPN, owned, emptySet(), null)
            journal.requireOwnedSettings(owned[1])
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                journal.requireOwnedSettings(original.toBuilder().setProxyPort(30_000).build())
            }
            val marker = File(directory, "ownership-private.json")
            val active = marker.readText()
            marker.writeText(JSONObject(active).put("createdAt", -3_600_001L).toString())
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { journal.requireOwnedSettings(owned[0]) }
            marker.writeText(JSONObject(active).put("state", "RESTORED").toString())
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { journal.requireOwnedSettings(owned[0]) }
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { journal.prepare(original) }
        } finally {
            check(directory.deleteRecursively())
        }
    }
}
