package com.poyka.ripdpi.core.detection

import android.content.Context
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
