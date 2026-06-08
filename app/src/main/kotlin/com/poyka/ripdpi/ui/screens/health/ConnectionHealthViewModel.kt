package com.poyka.ripdpi.ui.screens.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.services.ConnectionHealthBucket
import com.poyka.ripdpi.services.ConnectionHealthDestinationClass
import com.poyka.ripdpi.services.ConnectionHealthRepository
import com.poyka.ripdpi.services.ConnectionHealthSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ConnectionHealthUiState(
    val rows: List<ConnectionHealthRowUiState> =
        ConnectionHealthDestinationClass.entries.map { destinationClass ->
            ConnectionHealthRowUiState(destinationClass = destinationClass)
        },
    val qualityLossPercent: Int? = null,
    val qualityRttP50Ms: Long? = null,
    val observedAt: Long = 0L,
) {
    val hasData: Boolean
        get() = rows.any { it.totalCount > 0L }
}

data class ConnectionHealthRowUiState(
    val destinationClass: ConnectionHealthDestinationClass,
    val activeStrategy: String? = null,
    val successCount: Long = 0L,
    val failureCount: Long = 0L,
    val attributedCount: Long = 0L,
    val lastUpdatedAt: Long = 0L,
) {
    val totalCount: Long
        get() = successCount + failureCount

    val successRatePercent: Int?
        get() = totalCount.takeIf { it > 0L }?.let { total -> ((successCount * 100L) / total).toInt() }
}

@HiltViewModel
class ConnectionHealthViewModel
    @Inject
    constructor(
        repository: ConnectionHealthRepository,
    ) : ViewModel() {
        val uiState: StateFlow<ConnectionHealthUiState> =
            repository.snapshots
                .map(::toUiState)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = ConnectionHealthUiState(),
                )
    }

private fun toUiState(snapshot: ConnectionHealthSnapshot): ConnectionHealthUiState =
    ConnectionHealthUiState(
        rows = snapshot.buckets.map(ConnectionHealthBucket::toRowUiState),
        qualityLossPercent = snapshot.quality?.lossPct?.toInt(),
        qualityRttP50Ms = snapshot.quality?.rttP50Ms,
        observedAt = snapshot.observedAt,
    )

private fun ConnectionHealthBucket.toRowUiState(): ConnectionHealthRowUiState =
    ConnectionHealthRowUiState(
        destinationClass = destinationClass,
        activeStrategy = activeStrategy,
        successCount = successCount,
        failureCount = failureCount,
        attributedCount = attributedCount,
        lastUpdatedAt = lastUpdatedAt,
    )
