package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.data.DirectPathLearningEvent
import com.poyka.ripdpi.data.DirectPathLearningSignal
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

enum class ConnectionHealthDestinationClass {
    VK,
    YOUTUBE,
    TELEGRAM,
    GENERIC_TLS,
}

data class ConnectionHealthBucket(
    val destinationClass: ConnectionHealthDestinationClass,
    val activeStrategy: String?,
    val successCount: Long,
    val failureCount: Long,
    val attributedCount: Long,
    val lastUpdatedAt: Long,
) {
    val totalCount: Long
        get() = successCount + failureCount

    val successRatePercent: Int?
        get() = totalCount.takeIf { it > 0 }?.let { total -> ((successCount * PercentScale) / total).toInt() }
}

data class ConnectionHealthSnapshot(
    val buckets: List<ConnectionHealthBucket> = emptyList(),
    val observedAt: Long = 0L,
    val quality: ConnectionQualitySnapshot? = null,
) {
    val hasData: Boolean
        get() = buckets.any { it.totalCount > 0 }
}

interface ConnectionHealthRepository {
    val snapshots: StateFlow<ConnectionHealthSnapshot>
}

@Singleton
class DefaultConnectionHealthRepository
    @Inject
    constructor(
        serviceStateStore: ServiceStateStore,
        private val flowAppAttributionStore: FlowAppAttributionStore,
        @ApplicationScope applicationScope: CoroutineScope,
    ) : ConnectionHealthRepository {
        private val accumulator = ConnectionHealthAccumulator()
        private val mutableSnapshots = MutableStateFlow(ConnectionHealthSnapshot(buckets = accumulator.buckets()))
        override val snapshots: StateFlow<ConnectionHealthSnapshot> = mutableSnapshots.asStateFlow()

        init {
            applicationScope.launch {
                serviceStateStore.telemetry.collect { telemetry ->
                    mutableSnapshots.value =
                        accumulator.consume(
                            telemetry = telemetry,
                            isAttributed = { digest -> flowAppAttributionStore.lookup(digest) != null },
                        )
                }
            }
        }
    }

internal class ConnectionHealthAccumulator {
    private val observations = ArrayDeque<ConnectionHealthObservation>()
    private var lastQualitySuccessCount: Long = 0L
    private var lastEstimatedQualityFailureCount: Long = 0L

    fun consume(
        telemetry: ServiceTelemetrySnapshot,
        isAttributed: (String) -> Boolean = { false },
    ): ConnectionHealthSnapshot {
        val proxy = telemetry.proxyTelemetry
        val activeStrategy = activeStrategy(telemetry, proxy)
        proxy.directPathLearningSignals.forEach { signal ->
            signal.toObservation(activeStrategy, isAttributed)?.let(observations::addLast)
        }
        qualityObservations(telemetry, activeStrategy).forEach(observations::addLast)
        trim(telemetry.updatedAt.takeIf { it > 0L } ?: proxy.capturedAt)
        return ConnectionHealthSnapshot(
            buckets = buckets(),
            observedAt = telemetry.updatedAt.takeIf { it > 0L } ?: proxy.capturedAt,
            quality = proxy.connectionQuality ?: telemetry.tunnelTelemetry.connectionQuality,
        )
    }

    fun buckets(): List<ConnectionHealthBucket> =
        ConnectionHealthDestinationClass.entries.map { destinationClass ->
            val matching = observations.filter { it.destinationClass == destinationClass }
            ConnectionHealthBucket(
                destinationClass = destinationClass,
                activeStrategy = matching.lastOrNull()?.activeStrategy,
                successCount = matching.count { it.succeeded }.toLong(),
                failureCount = matching.count { !it.succeeded }.toLong(),
                attributedCount = matching.count { it.attributed }.toLong(),
                lastUpdatedAt = matching.maxOfOrNull { it.capturedAt } ?: 0L,
            )
        }

    private fun qualityObservations(
        telemetry: ServiceTelemetrySnapshot,
        activeStrategy: String?,
    ): List<ConnectionHealthObservation> {
        val proxy = telemetry.proxyTelemetry
        val quality = proxy.connectionQuality
        val host = proxy.lastHost ?: proxy.lastTarget
        if (quality == null || host == null) return emptyList()
        val currentSuccess = quality.sampleCount
        val estimatedFailures = estimateQualityFailures(quality)
        val successDelta = (currentSuccess - lastQualitySuccessCount).coerceAtLeast(0L)
        val failureDelta = (estimatedFailures - lastEstimatedQualityFailureCount).coerceAtLeast(0L)
        lastQualitySuccessCount = currentSuccess
        lastEstimatedQualityFailureCount = estimatedFailures
        return if (successDelta == 0L && failureDelta == 0L) {
            emptyList()
        } else {
            val destinationClass = classifyDestination(host)
            val capturedAt = proxy.capturedAt.takeIf { it > 0L } ?: telemetry.updatedAt
            buildList {
                repeat(successDelta.coerceAtMost(MaxQualityDeltaObservations).toInt()) {
                    addQualityObservation(destinationClass, activeStrategy, true, capturedAt)
                }
                repeat(failureDelta.coerceAtMost(MaxQualityDeltaObservations).toInt()) {
                    addQualityObservation(destinationClass, activeStrategy, false, capturedAt)
                }
            }
        }
    }

    private fun trim(now: Long) {
        val cutoff = now - ObservationWindowMs
        while (
            observations.size > MaxObservations ||
            observations.firstOrNull()?.capturedAt?.let { it < cutoff } == true
        ) {
            observations.removeFirstOrNull() ?: break
        }
    }
}

private fun MutableList<ConnectionHealthObservation>.addQualityObservation(
    destinationClass: ConnectionHealthDestinationClass,
    activeStrategy: String?,
    succeeded: Boolean,
    capturedAt: Long,
) {
    add(
        ConnectionHealthObservation(
            destinationClass = destinationClass,
            activeStrategy = activeStrategy,
            succeeded = succeeded,
            attributed = false,
            capturedAt = capturedAt,
        ),
    )
}

private data class ConnectionHealthObservation(
    val destinationClass: ConnectionHealthDestinationClass,
    val activeStrategy: String?,
    val succeeded: Boolean,
    val attributed: Boolean,
    val capturedAt: Long,
)

private fun DirectPathLearningSignal.toObservation(
    fallbackStrategy: String?,
    isAttributed: (String) -> Boolean,
): ConnectionHealthObservation? {
    val succeeded =
        when (event) {
            DirectPathLearningEvent.QUIC_SUCCESS,
            DirectPathLearningEvent.NO_TCP_FALLBACK_DETECTED,
            -> true

            DirectPathLearningEvent.QUIC_BLOCKED_TCP_OK,
            DirectPathLearningEvent.TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK,
            DirectPathLearningEvent.ALL_IPS_FAILED,
            -> false

            else -> return null
        }
    return ConnectionHealthObservation(
        destinationClass = classifyDestination(authority),
        activeStrategy = strategyFamily?.takeIf(String::isNotBlank) ?: fallbackStrategy,
        succeeded = succeeded,
        attributed = isAttributed(ipSetDigest),
        capturedAt = capturedAt,
    )
}

private fun activeStrategy(
    telemetry: ServiceTelemetrySnapshot,
    proxy: NativeRuntimeSnapshot,
): String? =
    telemetry.runtimeFieldTelemetry.winningStrategyFamily
        ?: proxy.morphHintFamily
        ?: proxy.tlsProfileId
        ?: proxy.strategyPackId

internal fun classifyDestination(value: String): ConnectionHealthDestinationClass {
    val normalized = value.trim().trimEnd('.').lowercase(Locale.ROOT)
    return when {
        normalized.isBlank() -> ConnectionHealthDestinationClass.GENERIC_TLS
        normalized.isYouTubeDestination() -> ConnectionHealthDestinationClass.YOUTUBE
        normalized.isTelegramDestination() -> ConnectionHealthDestinationClass.TELEGRAM
        normalized.isVkDestination() -> ConnectionHealthDestinationClass.VK
        else -> ConnectionHealthDestinationClass.GENERIC_TLS
    }
}

private fun String.isYouTubeDestination(): Boolean =
    contains("youtube.com") ||
        contains("youtu.be") ||
        contains("googlevideo.com") ||
        contains("ytimg.com") ||
        contains("youtubei.googleapis.com")

private fun String.isTelegramDestination(): Boolean =
    contains("telegram.org") ||
        contains("web.telegram.org") ||
        this == "t.me" ||
        endsWith(".t.me") ||
        contains("telegram.me") ||
        startsWith("149.154.") ||
        startsWith("91.108.")

private fun String.isVkDestination(): Boolean =
    this == "vk.com" ||
        endsWith(".vk.com") ||
        contains("vk.ru") ||
        contains("vkontakte.ru") ||
        contains("userapi.com") ||
        contains("vkvideo.ru") ||
        contains("vkcdn")

private fun estimateQualityFailures(quality: ConnectionQualitySnapshot): Long {
    val success = quality.sampleCount
    val loss = quality.lossPct.coerceIn(MinLossPercent, MaxLossPercent)
    return when {
        loss <= MinLossPercent -> {
            0L
        }

        loss >= MaxLossPercent -> {
            (success + 1L).coerceAtLeast(1L)
        }

        else -> {
            (
                (success.toDouble() * loss.toDouble()) /
                    (PercentScale - loss.toDouble())
            ).roundToLong().coerceAtLeast(0L)
        }
    }
}

private const val PercentScale = 100
private const val MinLossPercent = 0f
private const val MaxLossPercent = 100f
private const val ObservationWindowMs = 5 * 60 * 1000L
private const val MaxObservations = 256
private const val MaxQualityDeltaObservations = 32L

@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectionHealthRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindConnectionHealthRepository(
        repository: DefaultConnectionHealthRepository,
    ): ConnectionHealthRepository
}
