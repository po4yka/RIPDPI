package com.poyka.ripdpi.ui.debug

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * Debug-only recomposition tracker. Counts recompositions per tagged composable
 * and periodically dumps a report to logcat.
 *
 * Usage:
 *   1. Add `TrackRecomposition("ScreenName")` at the top of composables you want to monitor
 *   2. Run the app and interact with it
 *   3. Capture output: `adb logcat -s RecomposeTracker:D RecomposeReport:I *:S`
 *   4. Share the logcat output for analysis
 *
 * The report is emitted every 5 seconds and shows:
 *   - Total recomposition count per composable
 *   - Recompositions in the last interval (delta)
 *   - Composables sorted by delta (most active first)
 */
object RecompositionTracker {
    private val counts = ConcurrentHashMap<String, Int>()
    private val snapshots = ConcurrentHashMap<String, Int>()

    fun record(tag: String) {
        counts.merge(tag, 1, Int::plus)
    }

    fun report(): String {
        val current = HashMap(counts)
        val lines =
            current.entries
                .map { (tag, total) ->
                    val prev = snapshots[tag] ?: 0
                    val delta = total - prev
                    Triple(tag, total, delta)
                }.sortedByDescending { it.third }

        snapshots.clear()
        snapshots.putAll(current)

        if (lines.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("--- Recomposition Report ---")
        sb.appendLine("%-40s %8s %8s".format("Composable", "Total", "Delta"))
        sb.appendLine("-".repeat(60))
        for ((tag, total, delta) in lines) {
            val marker =
                when {
                    delta > 20 -> " !!!"
                    delta > 5 -> " !"
                    else -> ""
                }
            sb.appendLine("%-40s %8d %8d%s".format(tag, total, delta, marker))
        }
        sb.appendLine("-".repeat(60))
        return sb.toString()
    }

    fun reset() {
        counts.clear()
        snapshots.clear()
    }
}

/**
 * Drop this into any composable to track its recomposition count.
 *
 * ```
 * @Composable
 * fun MyScreen(...) {
 *     TrackRecomposition("MyScreen")
 *     // ... rest of composable
 * }
 * ```
 */
@Composable
fun TrackRecomposition(tag: String) {
    val count = remember { mutableIntStateOf(0) }
    SideEffect {
        count.intValue++
        RecompositionTracker.record(tag)
        if (count.intValue % 10 == 1) {
            Log.d("RecomposeTracker", "$tag recomposed (#${count.intValue})")
        }
    }
}

/**
 * Place this once in your root composable (e.g., MainActivityContent) to
 * periodically dump the recomposition report to logcat.
 *
 * ```
 * @Composable
 * fun MainActivityContent(...) {
 *     RecompositionReportEffect()
 *     // ...
 * }
 * ```
 */
@Composable
fun RecompositionReportEffect(intervalMs: Long = 5_000L) {
    LaunchedEffect(Unit) {
        while (true) {
            delay(intervalMs)
            val report = RecompositionTracker.report()
            if (report.isNotEmpty()) {
                report.lines().forEach { line ->
                    Log.i("RecomposeReport", line)
                }
            }
        }
    }
}
