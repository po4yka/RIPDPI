package com.poyka.ripdpi.core.detection

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class DetectionCheckSchedulerTest {
    @Test
    fun `same-key underlay handover during check does not save stale result`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val state = DefaultServiceStateStore().apply { setStatus(AppStatus.Running, Mode.VPN) }
            val fingerprint =
                object : NetworkFingerprintProvider {
                    var current = network("wifi").copy(directDnsUnderlayGeneration = 1L)

                    override fun capture(): NetworkFingerprint = current
                }
            val history = mutableListOf<DetectionHistoryEntry>()
            var runs = 0
            val runner =
                object : DetectionCheckRunner {
                    override suspend fun run(
                        context: Context,
                        config: DetectionRunnerConfig,
                        onProgress: (suspend (DetectionProgress) -> Unit)?,
                    ): DetectionCheckResult {
                        runs++
                        fingerprint.current = fingerprint.current.copy(directDnsUnderlayGeneration = 2L)
                        return cleanResult()
                    }
                }
            val scheduler = DetectionCheckScheduler(context, state, fingerprint, runner, recordingHistory(history))

            scheduler.startObserving(context, this)
            advanceUntilIdle()

            assertEquals(1, runs)
            assertTrue(history.isEmpty())
            scheduler.stopObserving()
        }

    @Test
    fun `detected alert is localized and opens the detection check route`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val launcher = ComponentName(context, "com.example.Launcher")
            shadowOf(context.packageManager).apply {
                addActivityIfNotPresent(launcher)
                addIntentFilterForActivity(
                    launcher,
                    IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
                )
            }
            val state = DefaultServiceStateStore().apply { setStatus(AppStatus.Running, Mode.VPN) }
            val fingerprint =
                object : NetworkFingerprintProvider {
                    override fun capture(): NetworkFingerprint = network("wifi")
                }
            val runner =
                object : DetectionCheckRunner {
                    override suspend fun run(
                        context: Context,
                        config: DetectionRunnerConfig,
                        onProgress: (suspend (DetectionProgress) -> Unit)?,
                    ): DetectionCheckResult = cleanResult().copy(verdict = Verdict.DETECTED)
                }
            val scheduler =
                DetectionCheckScheduler(context, state, fingerprint, runner, recordingHistory(mutableListOf()))

            scheduler.startObserving(context, this)
            advanceUntilIdle()
            scheduler.stopObserving()

            val notification =
                shadowOf(
                    context.getSystemService(NotificationManager::class.java),
                ).allNotifications.single()
            val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
            assertTrue(title, title.startsWith("VPN detected: score "))
            assertEquals(
                "Tap to open Detection check for details and fixes.",
                notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
            )
            assertEquals(R.drawable.ic_notification, notification.smallIcon.resId)
            val opened = shadowOf(notification.contentIntent).savedIntent
            assertEquals(launcher, opened.component)
            assertEquals(Intent.ACTION_VIEW, opened.action)
            assertEquals(Uri.parse("ripdpi://detection-check"), opened.data)
        }

    @Test
    fun `automatic check on VPN start makes no third-party IP lookups`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val state = DefaultServiceStateStore()
            val fingerprint =
                object : NetworkFingerprintProvider {
                    override fun capture(): NetworkFingerprint = network("wifi")
                }
            val configs = mutableListOf<DetectionRunnerConfig>()
            val runner =
                object : DetectionCheckRunner {
                    override suspend fun run(
                        context: Context,
                        config: DetectionRunnerConfig,
                        onProgress: (suspend (DetectionProgress) -> Unit)?,
                    ): DetectionCheckResult {
                        configs += config
                        return cleanResult()
                    }
                }
            val scheduler =
                DetectionCheckScheduler(context, state, fingerprint, runner, recordingHistory(mutableListOf()))

            scheduler.startObserving(context, this)
            state.setStatus(AppStatus.Running, Mode.VPN)
            advanceUntilIdle()

            val config = configs.single()
            assertFalse(config.includeGeoIpCheck)
            assertFalse(config.includeIpComparisonCheck)
            assertFalse(config.includeCdnPullingCheck)
            assertFalse(config.includeRttTriangulationCheck)
            assertFalse(config.includeCallTransportCheck)
            assertFalse(config.includeBypassCheck)
            scheduler.stopObserving()
        }

    private fun network(transport: String) = NetworkFingerprint(transport, true, false, "system", emptyList())

    private fun cleanResult(): DetectionCheckResult {
        val category = CategoryResult("clean", false, emptyList())
        return DetectionCheckResult(
            geoIp = category,
            directSigns = category,
            indirectSigns = category,
            locationSignals = category,
            bypassResult = BypassResult(null, null, null, null, findings = emptyList(), detected = false),
            verdict = Verdict.NOT_DETECTED,
        )
    }

    private fun recordingHistory(entries: MutableList<DetectionHistoryEntry>) =
        object : DetectionHistoryRepository {
            override suspend fun save(entry: DetectionHistoryEntry) {
                entries += entry
            }

            override suspend fun loadLatest(count: Int): List<DetectionHistoryEntry> = entries.take(count)

            override suspend fun findByFingerprint(fingerprint: String): DetectionHistoryEntry? =
                entries.firstOrNull { it.networkFingerprint == fingerprint }

            override suspend fun clear() {
                entries.clear()
            }
        }
}
