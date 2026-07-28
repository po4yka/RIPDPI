package com.poyka.ripdpi.diagnostics.exit

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.diagnostics.FakeDiagnosticsHistoryStores
import com.poyka.ripdpi.diagnostics.exit.DefaultLastExitInspector.Companion.AndroidMemoryLimiterSubtype
import com.poyka.ripdpi.diagnostics.exit.DefaultLastExitInspector.Companion.DescriptionMarker
import com.poyka.ripdpi.diagnostics.exit.DefaultLastExitInspector.Companion.NoSubtype
import com.poyka.ripdpi.diagnostics.exit.DefaultLastExitInspector.Companion.Source
import com.poyka.ripdpi.diagnostics.exit.DefaultLastExitInspector.Companion.Subsystem
import com.poyka.ripdpi.diagnostics.exit.DefaultLastExitInspector.Companion.importanceBand
import com.poyka.ripdpi.diagnostics.exit.DefaultLastExitInspector.Companion.memoryBand
import com.poyka.ripdpi.diagnostics.exit.DefaultLastExitInspector.Companion.processExitEvent
import com.poyka.ripdpi.diagnostics.exit.DefaultLastExitInspector.Companion.reasonCategory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LastExitInspectorTest {
    @Test
    fun `API 30 maps every platform exit reason through OTHER`() {
        val expected =
            listOf(
                "unknown",
                "exit_self",
                "signaled",
                "low_memory",
                "crash",
                "crash_native",
                "anr",
                "initialization_failure",
                "permission_change",
                "excessive_resource_usage",
                "user_requested",
                "user_stopped",
                "dependency_died",
                "other",
            )

        assertEquals(expected, (0..13).map { reasonCategory(reason = it, sdkInt = 30) })
    }

    @Test
    fun `new reasons honor API gates and unknown fallback`() {
        assertEquals("unknown", reasonCategory(reason = 14, sdkInt = 32))
        assertEquals("freezer", reasonCategory(reason = 14, sdkInt = 33))
        assertEquals("unknown", reasonCategory(reason = 15, sdkInt = 33))
        assertEquals("unknown", reasonCategory(reason = 16, sdkInt = 33))
        assertEquals("package_state_change", reasonCategory(reason = 15, sdkInt = 34))
        assertEquals("package_updated", reasonCategory(reason = 16, sdkInt = 34))
        assertEquals("unknown", reasonCategory(reason = 99, sdkInt = 37))
        assertEquals("unknown", reasonCategory(reason = ApplicationExitInfo.REASON_CRASH, sdkInt = 29))
    }

    @Test
    fun `importance is reduced to a coarse category`() {
        assertEquals("unknown", importanceBand(0))
        assertEquals("foreground", importanceBand(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertEquals(
            "foreground_service",
            importanceBand(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE),
        )
        assertEquals("visible", importanceBand(ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE))
        assertEquals("perceptible", importanceBand(ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE))
        assertEquals("service", importanceBand(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE))
        assertEquals(
            "background",
            importanceBand(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE + 1),
        )
        assertEquals("background", importanceBand(ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING))
        assertEquals("background", importanceBand(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE))
        assertEquals(
            "background",
            importanceBand(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED - 1),
        )
        assertEquals("cached", importanceBand(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED))
        assertEquals("unknown", importanceBand(ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE))
    }

    @Test
    fun `PSS and RSS are reduced to coarse bands`() {
        assertEquals("unknown", memoryBand(0))
        assertEquals("low", memoryBand(127 * 1024L))
        assertEquals("medium", memoryBand(128 * 1024L))
        assertEquals("high", memoryBand(512 * 1024L))
    }

    @Test
    fun `memory limiter marker only changes subtype on API 37 and later`() {
        val api36 = event(reason = ApplicationExitInfo.REASON_OTHER, sdkInt = 36, markerPresent = true)
        val api37 = event(reason = ApplicationExitInfo.REASON_OTHER, sdkInt = 37, markerPresent = true)
        val wrongReason = event(reason = ApplicationExitInfo.REASON_LOW_MEMORY, sdkInt = 37, markerPresent = true)

        assertTrue(api36.message.contains("subtype=$NoSubtype"))
        assertTrue(api37.message.contains("subtype=$AndroidMemoryLimiterSubtype"))
        assertTrue(wrongReason.message.contains("subtype=$NoSubtype"))
    }

    @Test
    fun `event uses fixed categorical source subsystem message and severity`() {
        val crash = event(reason = ApplicationExitInfo.REASON_CRASH, sdkInt = 30)
        val userStop = event(reason = ApplicationExitInfo.REASON_USER_STOPPED, sdkInt = 30)
        val memoryLimiter = event(reason = ApplicationExitInfo.REASON_OTHER, sdkInt = 37, markerPresent = true)

        assertEquals(Source, crash.source)
        assertEquals(Subsystem, crash.subsystem)
        assertEquals("error", crash.level)
        assertEquals("info", userStop.level)
        assertEquals("warn", memoryLimiter.level)
        assertEquals(
            "process_exit reason=crash;subtype=none;importance=service;pss=medium;rss=high",
            crash.message,
        )
    }

    @Test
    fun `same exit projection is deterministic for REPLACE idempotency`() {
        val first = event(reason = ApplicationExitInfo.REASON_ANR, sdkInt = 30)
        val second = event(reason = ApplicationExitInfo.REASON_ANR, sdkInt = 30)

        assertEquals(first, second)
        assertEquals(first.id, second.id)
    }

    @Test
    fun `identity discriminators use stable same-timestamp ordinals without exposing pid`() {
        val firstPid = 4_243
        val secondPid = 4_242

        val discriminators =
            processExitIdentityDiscriminators(
                listOf(
                    1_700L to firstPid,
                    1_800L to 9_000,
                    1_700L to secondPid,
                ),
            )

        assertEquals(listOf("ordinal-1", "ordinal-0", "ordinal-0"), discriminators)
        assertFalse(discriminators.joinToString().contains(firstPid.toString()))
        assertFalse(discriminators.joinToString().contains(secondPid.toString()))
    }

    @Test
    fun `hostile raw process fields cannot enter persisted or exported event`() =
        runTest {
            val hostileDescription = "$DescriptionMarker secret.example 10.0.0.1"
            val hostileProcess = "com.example.private:relay"
            val hostilePid = 4_242
            val hostileStatus = 137
            val hostileTrace = "trace contains private endpoint and token"
            val stores = FakeDiagnosticsHistoryStores()
            val source =
                FakeProcessExitHistorySource(
                    sdkInt = 37,
                    exits =
                        listOf(
                            ProcessExitHistoryItem(
                                timestampMs = 1_700,
                                identityDiscriminator = "ordinal-0",
                                reason = ApplicationExitInfo.REASON_OTHER,
                                importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE,
                                pssKb = 256 * 1024L,
                                rssKb = 768 * 1024L,
                                memoryLimiterMarkerPresent = hostileDescription.contains(DescriptionMarker),
                                rawPid = hostilePid,
                                rawProcessName = hostileProcess,
                                rawStatus = hostileStatus,
                                rawDescription = hostileDescription,
                                rawTraceMarker = hostileTrace,
                            ),
                        ),
                )

            DefaultLastExitInspector(source, stores).recordRecentProcessExits()

            val event = stores.nativeEventsState.value.single()
            val encoded = Json.encodeToString(event)

            listOf(
                hostileDescription,
                hostileProcess,
                hostilePid.toString(),
                hostileStatus.toString(),
                hostileTrace,
            ).forEach { secret ->
                assertFalse(encoded.contains(secret))
                assertFalse(event.id.contains(secret))
                assertFalse(event.message.contains(secret))
            }
            assertTrue(event.message.contains("subtype=$AndroidMemoryLimiterSubtype"))
            assertEquals(listOf(16), source.requestedLimits)
        }

    @Test
    fun `recorder persists at most sixteen exits`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val source =
                FakeProcessExitHistorySource(
                    sdkInt = 34,
                    exits =
                        (0 until 20).map { index ->
                            ProcessExitHistoryItem(
                                timestampMs = index.toLong(),
                                identityDiscriminator = "ordinal-0",
                                reason = ApplicationExitInfo.REASON_USER_STOPPED,
                                importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED,
                                pssKb = 0,
                                rssKb = 0,
                                memoryLimiterMarkerPresent = false,
                            )
                        },
                )

            DefaultLastExitInspector(source, stores).recordRecentProcessExits()

            assertEquals(16, stores.nativeEventsState.value.size)
            assertEquals(listOf(16), source.requestedLimits)
        }

    @Test
    fun `same timestamp exits keep distinct opaque identities without exposing pid`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val timestamp = 1_700L
            val firstPid = 4_242
            val secondPid = 4_243
            val source =
                FakeProcessExitHistorySource(
                    sdkInt = 34,
                    exits =
                        listOf(firstPid, secondPid).mapIndexed { ordinal, pid ->
                            ProcessExitHistoryItem(
                                timestampMs = timestamp,
                                identityDiscriminator = "ordinal-$ordinal",
                                reason = ApplicationExitInfo.REASON_CRASH,
                                importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE,
                                pssKb = 0,
                                rssKb = 0,
                                memoryLimiterMarkerPresent = false,
                                rawPid = pid,
                            )
                        },
                )

            val inspector = DefaultLastExitInspector(source, stores)
            inspector.recordRecentProcessExits()
            inspector.recordRecentProcessExits()

            val events = stores.nativeEventsState.value
            assertEquals(4, events.size)
            assertEquals(2, events.map { it.id }.distinct().size)
            events.forEach { event ->
                assertFalse(event.id.contains(firstPid.toString()))
                assertFalse(event.id.contains(secondPid.toString()))
            }
        }

    private class FakeProcessExitHistorySource(
        override val sdkInt: Int,
        private val exits: List<ProcessExitHistoryItem>,
    ) : ProcessExitHistorySource {
        val requestedLimits = mutableListOf<Int>()

        override fun recentProcessExits(limit: Int): List<ProcessExitHistoryItem> {
            requestedLimits += limit
            return exits
        }
    }

    private fun event(
        reason: Int,
        sdkInt: Int,
        markerPresent: Boolean = false,
    ): NativeSessionEventEntity =
        processExitEvent(
            timestampMs = 1_700,
            identityDiscriminator = "ordinal-0",
            reason = reason,
            sdkInt = sdkInt,
            importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE,
            pssKb = 256 * 1024L,
            rssKb = 768 * 1024L,
            memoryLimiterMarkerPresent = markerPresent,
        )
}
