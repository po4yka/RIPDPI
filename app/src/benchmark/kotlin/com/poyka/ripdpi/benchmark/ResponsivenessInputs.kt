package com.poyka.ripdpi.benchmark

import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.ui.screens.profiles.ProfileCatalogState
import com.poyka.ripdpi.ui.screens.profiles.ProfileMeasurementUiState
import com.poyka.ripdpi.ui.screens.profiles.ProfileUtilityItem
import com.poyka.ripdpi.ui.screens.profiles.ProfileUtilityUiState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import java.util.concurrent.atomic.AtomicLong

internal const val CounterTicks = 100
internal const val CounterTickMillis = 20L
internal const val RuntimeEventCount = 250
internal const val FixtureEpochMillis = 1_800_000_000_000L

/** Counts element reads made by the real runtime-log mapper, without replacing the mapper. */
internal class CountingEvents(
    private val events: List<NativeRuntimeEvent>,
    private val reads: AtomicLong,
) : AbstractList<NativeRuntimeEvent>() {
    override val size: Int get() = events.size

    override fun get(index: Int): NativeRuntimeEvent {
        reads.incrementAndGet()
        return events[index]
    }
}

/** The same immutable instance is retained through independent catalog control updates. */
internal class CountingProfiles(
    private val profiles: ImmutableList<ProfileUtilityItem>,
    private val reads: AtomicLong,
) : ImmutableList<ProfileUtilityItem> by profiles {
    override fun get(index: Int): ProfileUtilityItem {
        reads.incrementAndGet()
        return profiles[index]
    }

    override fun iterator(): Iterator<ProfileUtilityItem> =
        object : Iterator<ProfileUtilityItem> {
            private var index = 0

            override fun hasNext(): Boolean = index < size

            override fun next(): ProfileUtilityItem {
                if (!hasNext()) throw NoSuchElementException()
                return get(index++)
            }
        }
}

internal fun runtimeEvent(index: Int): NativeRuntimeEvent =
    NativeRuntimeEvent(
        source = "proxy",
        level = "info",
        message = "Benchmark event $index",
        createdAt = FixtureEpochMillis + index,
        kind = "benchmark",
    )

internal fun catalogInput(
    size: Int,
    reads: AtomicLong = AtomicLong(),
): ProfileUtilityUiState =
    ProfileUtilityUiState(
        catalogGeneration = 1,
        profiles =
            CountingProfiles(
                List(size) { index ->
                    ProfileUtilityItem(
                        reference = ProfileUtilityReference.NativeRelay("benchmark-$index"),
                        label = "Profile ${index.toString().padStart(5, '0')}",
                        groupLabel = "Benchmark group ${index % 10}",
                        kind = "vless",
                        favorite = index % 7 == 0,
                        applied = index == 0,
                        lastUsedAtMillis = FixtureEpochMillis + index,
                        recentSequence = index.toLong(),
                        measurement = ProfileMeasurementUiState.NotChecked,
                    )
                }.toImmutableList(),
                reads,
            ),
        probeUrl = "https://benchmark.invalid/check",
        catalogState = ProfileCatalogState.Ready,
        canMeasure = false,
        cleanupPending = false,
        failure = null,
    )
