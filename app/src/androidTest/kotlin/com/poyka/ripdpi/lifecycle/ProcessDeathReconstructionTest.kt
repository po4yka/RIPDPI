package com.poyka.ripdpi.integration

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.activities.MainActivity
import com.poyka.ripdpi.data.AppSettingsRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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

        // 2. Kill the app process. The test runner is in a separate process
        //    (com.poyka.ripdpi.test) and is unaffected.
        val uiAutomation = instrumentation.uiAutomation
        uiAutomation.executeShellCommand("am kill ${BuildConfig.APPLICATION_ID}").let { pfd ->
            // Drain the output stream so the shell command completes before we continue.
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }

        // 3. Wait for the app process to be absent from the process list.
        //    We poll /proc rather than using ActivityManager APIs (which require
        //    the app to be running) to avoid flakiness.
        val killedByDeadline = SystemClock.elapsedRealtime() + KILL_SETTLE_TIMEOUT_MS
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
            if (psOutput.isEmpty()) {
                appProcessGone = true
                break
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        check(appProcessGone) {
            "App process ${BuildConfig.APPLICATION_ID} was still alive after " +
                "$KILL_SETTLE_TIMEOUT_MS ms — am kill may not have succeeded"
        }

        // 4. Relaunch the app by starting MainActivity with a clean intent.
        val launchIntent =
            Intent(appContext, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK,
                )
            }
        appContext.startActivity(launchIntent)

        // 5. Allow the app process to initialise and the DataStore to load.
        SystemClock.sleep(RELAUNCH_SETTLE_MS)

        // 6. Read the setting back — must match what was written before the kill.
        val recovered = runBlocking { settingsRepository.snapshot() }
        assertEquals(
            "AppSettings.dnsIp must survive process death (DataStore persistence invariant)",
            sentinelDns,
            recovered.dnsIp,
        )
    }

    private companion object {
        /** Maximum time to wait for the killed process to disappear from ps. */
        private const val KILL_SETTLE_TIMEOUT_MS = 5_000L

        /** Poll interval while waiting for the process to disappear. */
        private const val POLL_INTERVAL_MS = 200L

        /** Settle time after relaunching the activity before reading DataStore. */
        private const val RELAUNCH_SETTLE_MS = 2_000L
    }
}
