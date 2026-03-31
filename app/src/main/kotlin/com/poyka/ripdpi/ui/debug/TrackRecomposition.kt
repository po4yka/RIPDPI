package com.poyka.ripdpi.ui.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.BuildConfig
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks recomposition counts per composable and periodically dumps a report.
 * All methods are no-ops when [BuildConfig.DEBUG] is false.
 *
 * Capture: `adb logcat -s RecomposeTracker:D RecomposeReport:I *:S`
 */
object RecompositionCounter {
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

        if (lines.isEmpty() || lines.all { it.third == 0 }) return ""

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
 * Logs recomposition events for a composable. No-op in release builds.
 *
 * ```
 * @Composable
 * fun MyScreen(...) {
 *     TrackRecomposition("MyScreen")
 *     // ...
 * }
 * ```
 */
@Composable
fun TrackRecomposition(tag: String) {
    if (!BuildConfig.DEBUG) return
    val count = remember { mutableIntStateOf(0) }
    SideEffect {
        count.intValue++
        RecompositionCounter.record(tag)
        if (count.intValue % 10 == 1) {
            Logger.withTag("RecomposeTracker").d { "$tag recomposed (#${count.intValue})" }
        }
    }
}

/**
 * Periodically dumps recomposition report to logcat. No-op in release builds.
 * Place once in the root composable.
 */
@Composable
fun RecompositionReportEffect(intervalMs: Long = 5_000L) {
    if (!BuildConfig.DEBUG) return
    LaunchedEffect(Unit) {
        Logger.withTag("RecomposeReport").d { "RecompositionReportEffect INSTALLED" }
        while (true) {
            delay(intervalMs)
            val report = RecompositionCounter.report()
            if (report.isNotEmpty()) {
                report.lines().forEach { line ->
                    Logger.withTag("RecomposeReport").d { line }
                }
            }
        }
    }
}
