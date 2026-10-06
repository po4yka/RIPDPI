package com.poyka.ripdpi.services

import android.content.Context
import android.content.Intent
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.DesiredRuntimeState
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.boot.BootSessionPointer
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Optional

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowServiceControllerVpnPrepareService::class])
class BootResumeCoordinatorTest {
    @Test
    fun `accepted legacy boot policy dispatches before publishing reconnecting`() =
        runTest {
            ShadowServiceControllerVpnPrepareService.prepareIntent = null
            val fixture = Fixture(mode = Mode.VPN)
            assertEquals(DesiredRuntimeState.LegacyUnknown, fixture.authority.snapshotAuthority().desired)

            fixture.coordinator.resume(Intent.ACTION_BOOT_COMPLETED)

            assertEquals(1, fixture.starter.intents.size)
            assertEquals(
                bootRecoveryStartAction,
                fixture.starter.intents
                    .single()
                    .action,
            )
            assertEquals(
                RipDpiVpnService::class.java.name,
                fixture.starter.intents
                    .single()
                    .component
                    ?.className,
            )
            assertEquals(listOf(AppStatus.Halted), fixture.starter.statusAtDispatch)
            assertEquals(AppStatus.Reconnecting to Mode.VPN, fixture.state.status.value)
            assertEquals(DesiredRuntimeState.Running, fixture.authority.snapshotAuthority().desired)
            assertEquals(Mode.VPN.preferenceValue, fixture.authority.snapshotAuthority().desiredMode)
        }

    @Test
    fun `standing boot preference can start after durable user Stop`() =
        runTest {
            val fixture = Fixture()
            fixture.authority.reserveStop()
            val before = fixture.authority.reference().generation

            fixture.coordinator.resume(Intent.ACTION_BOOT_COMPLETED)

            assertEquals(1, fixture.starter.intents.size)
            assertEquals(
                bootRecoveryStartAction,
                fixture.starter.intents
                    .single()
                    .action,
            )
            assertEquals(before + 1, fixture.authority.reference().generation)
            assertEquals(DesiredRuntimeState.Running, fixture.authority.snapshotAuthority().desired)
            assertEquals(AppStatus.Reconnecting to Mode.Proxy, fixture.state.status.value)
        }

    @Test
    fun `disabled boot preference leaves durable Stop and status unchanged`() =
        runTest {
            val fixture = Fixture(startOnBoot = false)
            fixture.authority.reserveStop()
            val before = fixture.authority.snapshotAuthority()

            fixture.coordinator.resume(Intent.ACTION_BOOT_COMPLETED)

            assertTrue(fixture.starter.intents.isEmpty())
            assertEquals(before, fixture.authority.snapshotAuthority())
            assertEquals(AppStatus.Halted to Mode.VPN, fixture.state.status.value)
        }

    @Test
    fun `stale boot pointer is cleared without starting a foreground service`() =
        runTest {
            val fixture = Fixture(profileId = "deleted", profileCheck = { false })

            fixture.coordinator.resume(Intent.ACTION_BOOT_COMPLETED)

            assertTrue(fixture.boot.cleared)
            assertNull(fixture.boot.lastSession())
            assertTrue(fixture.starter.intents.isEmpty())
            assertEquals(AppStatus.Halted to Mode.VPN, fixture.state.status.value)
        }

    @Test
    fun `package replacement consumes running marker and resumes matching durable Running`() =
        runTest {
            val fixture = Fixture(runningAtUpdate = true, startOnBoot = false)
            testAppliedRuntimeCommand(fixture.authority, Mode.Proxy)
            val before = fixture.authority.snapshotAuthority()

            fixture.coordinator.resume(Intent.ACTION_MY_PACKAGE_REPLACED)

            assertEquals(listOf(false), fixture.boot.runningFlagWrites)
            assertEquals(
                packageReplacedRecoveryStartAction,
                fixture.starter.intents
                    .single()
                    .action,
            )
            assertEquals(before.reference, fixture.authority.snapshotAuthority().reference)
            assertEquals(AppStatus.Reconnecting to Mode.Proxy, fixture.state.status.value)
        }

    @Test
    fun `package replacement cannot resurrect durable Stop from stale running marker`() =
        runTest {
            val fixture = Fixture(runningAtUpdate = true)
            fixture.authority.reserveStop()
            val before = fixture.authority.snapshotAuthority()

            fixture.coordinator.resume(Intent.ACTION_MY_PACKAGE_REPLACED)

            assertEquals(listOf(false), fixture.boot.runningFlagWrites)
            assertTrue(fixture.starter.intents.isEmpty())
            assertEquals(before, fixture.authority.snapshotAuthority())
            assertEquals(AppStatus.Halted to Mode.VPN, fixture.state.status.value)
        }

    @Test
    fun `package replacement cannot infer Running from legacy marker`() =
        runTest {
            val fixture = Fixture(runningAtUpdate = true)
            val before = fixture.authority.snapshotAuthority()

            fixture.coordinator.resume(Intent.ACTION_MY_PACKAGE_REPLACED)

            assertEquals(listOf(false), fixture.boot.runningFlagWrites)
            assertTrue(fixture.starter.intents.isEmpty())
            assertEquals(before, fixture.authority.snapshotAuthority())
        }

    @Test
    fun `new Stop during profile validation prevents older boot policy dispatch`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture =
                Fixture(profileCheck = {
                    entered.complete(Unit)
                    release.await()
                    true
                })
            val boot = async { fixture.coordinator.resume(Intent.ACTION_BOOT_COMPLETED) }
            entered.await()
            fixture.authority.reserveStop()
            val newer = fixture.authority.snapshotAuthority()
            release.complete(Unit)
            boot.await()

            assertTrue(fixture.starter.intents.isEmpty())
            assertEquals(newer, fixture.authority.snapshotAuthority())
            assertEquals(AppStatus.Halted to Mode.VPN, fixture.state.status.value)
        }

    @Test
    fun `new Pause during profile validation prevents older boot policy dispatch`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture =
                Fixture(profileCheck = {
                    entered.complete(Unit)
                    release.await()
                    true
                })
            val boot = async { fixture.coordinator.resume(Intent.ACTION_BOOT_COMPLETED) }
            entered.await()
            val newerPause = fixture.authority.begin(Mode.Proxy, 300_000, fixture.authority.snapshotAuthority())
            val newer = fixture.authority.snapshotAuthority()
            release.complete(Unit)
            boot.await()

            assertTrue(fixture.starter.intents.isEmpty())
            assertEquals(newer, fixture.authority.snapshotAuthority())
            assertEquals(newerPause, fixture.authority.snapshot())
            assertEquals(AppStatus.Halted to Mode.VPN, fixture.state.status.value)
        }

    @Test
    fun `pending Pause takes priority over standing boot policy before profile validation`() =
        runTest {
            var reads = 0
            val fixture =
                Fixture(profileCheck = {
                    reads += 1
                    true
                })
            val pending = fixture.authority.begin(Mode.Proxy, 300_000, fixture.authority.snapshotAuthority())
            val before = fixture.authority.snapshotAuthority()

            fixture.coordinator.resume(Intent.ACTION_BOOT_COMPLETED)

            assertEquals(0, reads)
            assertTrue(fixture.starter.intents.isEmpty())
            assertEquals(before, fixture.authority.snapshotAuthority())
            assertEquals(pending, fixture.authority.snapshot())
            assertFalse(fixture.boot.cleared)
        }

    private class Fixture(
        mode: Mode = Mode.Proxy,
        profileId: String = "default",
        startOnBoot: Boolean = true,
        runningAtUpdate: Boolean = false,
        profileCheck: suspend (String) -> Boolean = { true },
    ) {
        val authority: PauseIntentAuthority =
            com.poyka.ripdpi.data
                .testPauseAuthority()
        val boot = FakeBootSessionStateStore(BootSessionPointer(profileId, mode), runningAtUpdate)
        val state = DefaultServiceStateStore()
        val starter = RecordingBootForegroundStarter(state)
        private val controller =
            DefaultServiceController(
                context = RuntimeEnvironment.getApplication(),
                serviceStateStore = state,
                serviceAutomationController = Optional.empty(),
                foregroundServiceStarter = starter,
                bootSessionStateStore = boot,
                runtimeResumeIntentTracker = RuntimeResumeIntentTracker(authority),
                serviceIntentArbiter = ServiceIntentArbiter(authority),
                pauseAuthority = authority,
                appSettings =
                    TestAppSettingsRepository(
                        AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setStartOnBoot(startOnBoot)
                            .build(),
                    ),
                profileRecovery =
                    com.poyka.ripdpi.data
                        .testProfileRecovery(),
            )
        val coordinator =
            BootResumeCoordinator(
                boot,
                object : BootResumeProfileGuard {
                    override suspend fun isResumable(profileId: String) = profileCheck(profileId)
                },
                controller,
                state,
            )
    }
}

private class RecordingBootForegroundStarter(
    private val state: DefaultServiceStateStore,
) : ForegroundServiceStarter {
    val intents = mutableListOf<Intent>()
    val statusAtDispatch = mutableListOf<AppStatus>()

    override fun startForegroundService(
        context: Context,
        intent: Intent,
    ) {
        statusAtDispatch += state.status.value.first
        intents += intent
    }
}

private class FakeBootSessionStateStore(
    private var pointer: BootSessionPointer?,
    private var runningAtUpdate: Boolean = false,
) : BootSessionStateStore {
    var cleared = false
        private set
    val runningFlagWrites = mutableListOf<Boolean>()

    override fun lastSession(): BootSessionPointer? = pointer

    override fun recordSession(
        profileId: String,
        mode: Mode,
    ) {
        pointer = BootSessionPointer(profileId, mode)
    }

    override fun clear() {
        cleared = true
        pointer = null
    }

    override fun wasRunningAtUpdate(): Boolean = runningAtUpdate

    override fun setWasRunningAtUpdate(value: Boolean) {
        runningFlagWrites += value
        runningAtUpdate = value
    }
}
