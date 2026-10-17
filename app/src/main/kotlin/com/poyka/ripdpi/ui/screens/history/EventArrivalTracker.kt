package com.poyka.ripdpi.ui.screens.history

import androidx.compose.runtime.saveable.mapSaver
import com.poyka.ripdpi.activities.DiagnosticsEventFiltersUiModel
import com.poyka.ripdpi.activities.HistoryEventIdentityUiModel

internal const val eventArrivalWatermarkIdLimit = 256

/** Counts newly newest matching events without retaining the complete event history. */
internal data class EventArrivalTracker(
    val watermark: Long? = null,
    val watermarkIds: Set<String> = emptySet(),
    val watermarkSaturated: Boolean = false,
    val pendingCount: Int = 0,
    val filters: DiagnosticsEventFiltersUiModel? = null,
) {
    fun observe(
        timeline: List<HistoryEventIdentityUiModel>,
        filters: DiagnosticsEventFiltersUiModel,
        isFollowingLatest: Boolean,
    ): EventArrivalTracker {
        val contentFilters = filters.copy(autoScroll = true)
        val baselineChanged = watermark == null || this.filters != contentFilters
        val arrivals =
            if (baselineChanged || isFollowingLatest) {
                0
            } else {
                timeline.distinctBy { it.id }.count { event ->
                    event.matchesFilters &&
                        (
                            event.createdAt > requireNotNull(watermark) ||
                                (
                                    event.createdAt == watermark && !watermarkSaturated &&
                                        event.id !in watermarkIds
                                )
                        )
                }
            }
        val nextCount =
            if (baselineChanged || isFollowingLatest) {
                0
            } else {
                (pendingCount.toLong() + arrivals).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
        val snapshotTimestamp = timeline.maxOfOrNull { it.createdAt }
        val nextTimestamp = listOfNotNull(watermark, snapshotTimestamp).maxOrNull()
        val sameTimestamp = nextTimestamp == watermark
        val tiedIds =
            (
                (if (sameTimestamp) watermarkIds.asSequence() else emptySequence()) +
                    timeline.asSequence().filter { it.createdAt == nextTimestamp }.map { it.id }
            ).distinct().take(eventArrivalWatermarkIdLimit + 1).toList()
        return EventArrivalTracker(
            watermark = nextTimestamp,
            watermarkIds = tiedIds.take(eventArrivalWatermarkIdLimit).toSet(),
            // Once saturated, ignore more arrivals at this timestamp until time advances.
            watermarkSaturated =
                (sameTimestamp && watermarkSaturated) || tiedIds.size > eventArrivalWatermarkIdLimit,
            pendingCount = nextCount,
            filters = contentFilters,
        )
    }

    fun clearPending(): EventArrivalTracker = copy(pendingCount = 0)
}

internal val EventArrivalTrackerSaver =
    mapSaver(
        save = { tracker: EventArrivalTracker ->
            mapOf(
                "watermark" to tracker.watermark,
                "ids" to ArrayList(tracker.watermarkIds),
                "saturated" to tracker.watermarkSaturated,
                "count" to tracker.pendingCount,
                "hasFilters" to (tracker.filters != null),
                "source" to tracker.filters?.source,
                "severity" to tracker.filters?.severity,
                "query" to tracker.filters?.search,
            )
        },
        restore = { saved ->
            EventArrivalTracker(
                watermark = saved["watermark"] as? Long,
                watermarkIds =
                    (saved["ids"] as? List<*>)
                        .orEmpty()
                        .filterIsInstance<String>()
                        .take(eventArrivalWatermarkIdLimit)
                        .toSet(),
                watermarkSaturated = saved["saturated"] == true,
                pendingCount = (saved["count"] as? Int)?.coerceAtLeast(0) ?: 0,
                filters =
                    if (saved["hasFilters"] == true) {
                        DiagnosticsEventFiltersUiModel(
                            source = saved["source"] as? String,
                            severity = saved["severity"] as? String,
                            search = saved["query"] as? String ?: "",
                        )
                    } else {
                        null
                    },
            )
        },
    )
