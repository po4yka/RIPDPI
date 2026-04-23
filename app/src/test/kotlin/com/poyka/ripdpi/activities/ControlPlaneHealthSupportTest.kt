package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ControlPlaneCacheDegradationCode
import com.poyka.ripdpi.data.StrategyPackRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlPlaneHealthSupportTest {
    private val stringResolver = FakeStringResolver()

    @Test
    fun `healthy control planes produce no home summary`() {
        val summary =
            stringResolver.buildControlPlaneHealthSummary(
                hostPackCatalog = HostPackCatalogUiState(),
                strategyPackRuntimeState = StrategyPackRuntimeState(),
            )

        assertNull(summary)
    }

    @Test
    fun `home summary groups host degradation and strategy rollback rejection`() {
        val summary =
            stringResolver.buildControlPlaneHealthSummary(
                hostPackCatalog =
                    HostPackCatalogUiState(
                        cacheDegradationCode = ControlPlaneCacheDegradationCode.CachedSnapshotUnreadable,
                    ),
                strategyPackRuntimeState =
                    StrategyPackRuntimeState(
                        lastRefreshError = "rollback rejected",
                        lastRefreshFailureCode = com.poyka.ripdpi.data.StrategyPackRefreshFailureCode.RollbackRejected,
                        lastAcceptedSequence = 12,
                        lastRejectedSequence = 11,
                    ),
            )

        requireNotNull(summary)
        assertEquals(R.string.home_control_plane_health_title.toString(), summary.title)
        assertEquals(R.string.home_control_plane_health_action.toString(), summary.actionLabel)
        assertEquals(ControlPlaneHealthSeverityUiModel.Warning, summary.severity)
        assertEquals(2, summary.items.size)
        assertEquals(R.string.home_control_plane_host_packs_label.toString(), summary.items[0].label)
        assertEquals(
            R.string.home_control_plane_host_pack_degraded.toString(),
            summary.items[0].summary,
        )
        assertEquals(
            "${R.string.home_control_plane_strategy_pack_rollback_rejected}:11,12",
            summary.items[1].summary,
        )
    }

    @Test
    fun `host verification failure escalates summary severity to error`() {
        val summary =
            stringResolver.buildControlPlaneHealthSummary(
                hostPackCatalog =
                    HostPackCatalogUiState(
                        lastRefreshFailureCode = HostPackRefreshFailureCodeUiModel.VerificationFailed,
                    ),
                strategyPackRuntimeState = StrategyPackRuntimeState(),
            )

        requireNotNull(summary)
        assertEquals(ControlPlaneHealthSeverityUiModel.Error, summary.severity)
        assertTrue(summary.items.any { it.severity == ControlPlaneHealthSeverityUiModel.Error })
    }
}
