package com.poyka.ripdpi.integration

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.activities.MainActivity
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactQueryStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryResetStore
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * Verifies that persisted state (DataStore-backed AppSettings) survives a
 * process death simulated via `am kill`. This closes the mandate in
 * `.claude/rules/android-vpn-lifecycle.md`:
 *
 *   > CI matrix MUST include `adb shell am kill <package>` mid-session and
 *   > verify the next session reconstructs state correctly.
 *
 * Architecture note: the instrumentation runner executes in process
 * `com.poyka.ripdpi.test`, distinct from the app process `com.poyka.ripdpi`.
 * `am kill com.poyka.ripdpi` therefore terminates only the app process; the
 * test runner continues and can restart the app via a new activity launch.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ProcessDeathReconstructionTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var settingsRepository: AppSettingsRepository

    @Inject
    lateinit var diagnosticsArtifactQueryStore: DiagnosticsArtifactQueryStore

    @Inject
    lateinit var diagnosticsDurableStateStore: DiagnosticsDurableStateStore

    @Inject
    lateinit var diagnosticsHistoryResetStore: DiagnosticsHistoryResetStore

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    /**
     * Writes a sentinel value to the DataStore-backed [AppSettingsRepository],
     * kills the app process via `am kill`, then relaunches the app and reads
     * the setting back. The value must survive the kill cycle because DataStore
     * flushes to disk on every write (proto DataStore, WAL semantics).
     *
     * In-memory transient state (ServiceStateStore event history, replay
     * buffers) is intentionally not checked — those are expected to be gone
     * after process death; checking them would assert wrong behavior.
     */
    @Test
    fun persistedSettingsSurviveProcessDeath() {
        val sentinelDns = "9.9.9.9"

        // 1. Write a sentinel value — DataStore persists immediately to disk.
        runBlocking {
            settingsRepository.update { setDnsIp(sentinelDns) }
        }

        // 2-5. Kill and relaunch the app process with the canonical harness.
        killAndRelaunchApp()

        // 6. Read the setting back — must match what was written before the kill.
        val recovered = runBlocking { settingsRepository.snapshot() }
        assertEquals(
            "AppSettings.dnsIp must survive process death (DataStore persistence invariant)",
            sentinelDns,
            recovered.dnsIp,
        )
    }

    @Test
    fun pendingRemoteAcceptanceRunReconstructsAfterProcessDeath() {
        val runSuffix = System.nanoTime().toString(radix = 16)
        val interruptedRun = "process-death-$runSuffix-a"

        runBlocking { diagnosticsHistoryResetStore.clearRuntimeHistory() }

        try {
            val firstBegin =
                beginRemoteAcceptanceRunInAppProcess(
                    runGeneration = interruptedRun,
                    observedAtMillis = 10L,
                )
            assertNotEquals(
                "Remote acceptance debug receiver must run outside the instrumentation process",
                Process.myPid(),
                firstBegin.processPid,
            )
            runBlocking {
                assertEquals(
                    interruptedRun,
                    diagnosticsDurableStateStore
                        .getDurableState(firstBegin.pendingGenerationKey)
                        ?.value,
                )
            }

            killAndRelaunchApp(requiredDeadPid = firstBegin.processPid)

            runBlocking {
                assertNull(
                    diagnosticsDurableStateStore
                        .getDurableState(firstBegin.pendingGenerationKey),
                )

                val interruptedEvents =
                    diagnosticsArtifactQueryStore
                        .getGlobalNativeEvents(limit = 100)
                        .filter { event -> event.message.contains("phase=run_interrupted") }

                assertEquals(1, interruptedEvents.size)
                val interruptedEvent = interruptedEvents.single()
                assertEquals(interruptedRun, interruptedEvent.runtimeId)
                assertEquals("remote_device_acceptance", interruptedEvent.source)
                assertEquals("remote_acceptance", interruptedEvent.subsystem)
                assertTrue(interruptedEvent.message.contains("reason=interrupted_before_startup"))
                assertNull(interruptedEvent.sessionId)
                assertNull(interruptedEvent.connectionSessionId)
                assertNull(interruptedEvent.fingerprintHash)
                assertNull(interruptedEvent.policySignature)
            }
        } finally {
            runBlocking { diagnosticsHistoryResetStore.clearRuntimeHistory() }
        }
    }

    private fun killAndRelaunchApp(requiredDeadPid: Int? = null) {
        // Kill the app process. The test runner is in a separate process
        // (com.poyka.ripdpi.test) and is unaffected.
        val uiAutomation = instrumentation.uiAutomation
        uiAutomation.executeShellCommand("am kill ${BuildConfig.APPLICATION_ID}").let { pfd ->
            // Drain the output stream so the shell command completes before we continue.
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }

        // Wait for the app process to be absent from the process list.
        // Some instrumentation runners execute this test inside the target
        // app process, which cannot be killed without aborting the test. In
        // that environment, treat the current instrumentation PID as the
        // surviving runner process. For remote acceptance reconstruction,
        // require the exact debug receiver process PID to exit.
        val killedByDeadline = SystemClock.elapsedRealtime() + KILL_SETTLE_TIMEOUT_MS
        val instrumentationPid = Process.myPid().toString()
        var appProcessGone = false
        while (SystemClock.elapsedRealtime() < killedByDeadline) {
            val psOutput =
                uiAutomation
                    .executeShellCommand("pidof ${BuildConfig.APPLICATION_ID}")
                    .let { pfd ->
                        ParcelFileDescriptor.AutoCloseInputStream(pfd).use {
                            it.bufferedReader().readText()
                        }
                    }.trim()
            val livePids = psOutput.split(Regex("\\s+")).filter { it.isNotBlank() }
            val requiredPidGone = requiredDeadPid == null || !isPidAlive(requiredDeadPid)
            val instrumentationSurvives = Process.myPid().toString() == instrumentationPid
            if ((livePids.isEmpty() || livePids.all { it == instrumentationPid }) &&
                requiredPidGone &&
                instrumentationSurvives
            ) {
                appProcessGone = true
                break
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        check(appProcessGone) {
            "App process ${BuildConfig.APPLICATION_ID} was still alive after " +
                "$KILL_SETTLE_TIMEOUT_MS ms — am kill may not have succeeded"
        }

        // Relaunch the app by starting MainActivity with a clean intent.
        val launchIntent =
            Intent(appContext, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK,
                )
            }
        appContext.startActivity(launchIntent)

        // Allow the app process to initialise and persistent stores to load.
        SystemClock.sleep(RELAUNCH_SETTLE_MS)
    }

    private fun isPidAlive(pid: Int): Boolean {
        val output =
            instrumentation.uiAutomation
                .executeShellCommand("if [ -d /proc/$pid ]; then echo alive; fi")
                .let { pfd ->
                    ParcelFileDescriptor.AutoCloseInputStream(pfd).use {
                        it.bufferedReader().readText()
                    }
                }.trim()
        return output == "alive"
    }

    private fun beginRemoteAcceptanceRunInAppProcess(
        runGeneration: String,
        observedAtMillis: Long,
    ): RemoteAcceptanceBeginRunResult {
        val latch = CountDownLatch(1)
        val result = AtomicReference<RemoteAcceptanceBeginRunResult?>()
        val intent =
            Intent(RemoteAcceptanceDebugActionBeginRun).apply {
                setClassName(appContext.packageName, RemoteAcceptanceDebugReceiverClassName)
                putExtra(RemoteAcceptanceDebugExtraRunGeneration, runGeneration)
                putExtra(RemoteAcceptanceDebugExtraObservedAtMillis, observedAtMillis)
            }
        appContext.sendOrderedBroadcast(
            intent,
            null,
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    val extras = getResultExtras(false) ?: Bundle.EMPTY
                    result.set(
                        RemoteAcceptanceBeginRunResult(
                            ok =
                                resultCode == Activity.RESULT_OK &&
                                    extras.getBoolean(RemoteAcceptanceDebugExtraOk, false),
                            processPid = extras.getInt(RemoteAcceptanceDebugExtraProcessPid),
                            pendingGenerationKey =
                                extras.getString(RemoteAcceptanceDebugExtraPendingGenerationKey).orEmpty(),
                            errorClass = extras.getString(RemoteAcceptanceDebugExtraErrorClass),
                            errorMessage = extras.getString(RemoteAcceptanceDebugExtraErrorMessage),
                        ),
                    )
                    latch.countDown()
                }
            },
            null,
            Activity.RESULT_CANCELED,
            null,
            null,
        )
        check(latch.await(DebugReceiverTimeoutMs, TimeUnit.MILLISECONDS)) {
            "Timed out waiting for remote acceptance debug receiver"
        }
        val received =
            requireNotNull(result.get()) {
                "Remote acceptance debug receiver did not deliver a result"
            }
        check(received.ok) {
            "Remote acceptance debug beginRun failed: ${received.errorClass}: ${received.errorMessage}"
        }
        check(received.processPid > 0) {
            "Remote acceptance debug receiver did not report a target process pid"
        }
        check(received.pendingGenerationKey.isNotBlank()) {
            "Remote acceptance debug receiver did not report the durable state key"
        }
        return received
    }

    private data class RemoteAcceptanceBeginRunResult(
        val ok: Boolean,
        val processPid: Int,
        val pendingGenerationKey: String,
        val errorClass: String?,
        val errorMessage: String?,
    )

    private companion object {
        private const val RemoteAcceptanceDebugReceiverClassName =
            "com.poyka.ripdpi.services.RemoteAcceptanceDebugReceiver"
        private const val RemoteAcceptanceDebugActionBeginRun =
            "com.poyka.ripdpi.debug.REMOTE_ACCEPTANCE_BEGIN_RUN"
        private const val RemoteAcceptanceDebugExtraRunGeneration = "run_generation"
        private const val RemoteAcceptanceDebugExtraObservedAtMillis = "observed_at_millis"
        private const val RemoteAcceptanceDebugExtraPendingGenerationKey = "pending_generation_key"
        private const val RemoteAcceptanceDebugExtraOk = "ok"
        private const val RemoteAcceptanceDebugExtraProcessPid = "process_pid"
        private const val RemoteAcceptanceDebugExtraErrorClass = "error_class"
        private const val RemoteAcceptanceDebugExtraErrorMessage = "error_message"

        /** Maximum time to wait for the killed process to disappear from ps. */
        private const val KILL_SETTLE_TIMEOUT_MS = 5_000L

        /** Poll interval while waiting for the process to disappear. */
        private const val POLL_INTERVAL_MS = 200L

        /** Settle time after relaunching the activity before reading DataStore. */
        private const val RELAUNCH_SETTLE_MS = 2_000L

        /** Timeout for the debug-only target-app receiver to execute beginRun. */
        private const val DebugReceiverTimeoutMs = 10_000L
    }
}
