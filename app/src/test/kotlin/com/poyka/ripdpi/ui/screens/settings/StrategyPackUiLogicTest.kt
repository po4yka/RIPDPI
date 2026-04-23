package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.StrategyPackCatalogUiState
import com.poyka.ripdpi.activities.StrategyPackHealthFailureCodeUiModel
import com.poyka.ripdpi.data.StrategyPackCatalog
import com.poyka.ripdpi.data.StrategyPackCatalogSourceDownloaded
import com.poyka.ripdpi.data.StrategyPackRuntimeState
import com.poyka.ripdpi.data.StrategyPackSnapshot
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrategyPackUiLogicTest {
    @Test
    fun `rollback rejection exposes accepted and rejected sequence context`() {
        val status =
            strategyPackCatalogStatusSpec(
                StrategyPackCatalogUiState(
                    runtimeState =
                        StrategyPackRuntimeState(
                            snapshot =
                                StrategyPackSnapshot(
                                    catalog = StrategyPackCatalog(sequence = 12),
                                    source = StrategyPackCatalogSourceDownloaded,
                                ),
                            lastRefreshError = "rollback rejected",
                            lastRefreshFailureCode =
                                com.poyka.ripdpi.data.StrategyPackRefreshFailureCode.RollbackRejected,
                            lastAcceptedSequence = 12,
                            lastRejectedSequence = 11,
                        ),
                ),
            )

        assertEquals(R.string.strategy_pack_rollback_rejected_status_title, status.labelResId)
        assertEquals(R.string.strategy_pack_rollback_rejected_status_body, status.bodyResId)
        assertEquals(listOf(11L, 12L), status.bodyArgs)
        assertEquals(StatusIndicatorTone.Warning, status.tone)
    }

    @Test
    fun `healthy downloaded strategy pack remains active`() {
        val status =
            strategyPackCatalogStatusSpec(
                StrategyPackCatalogUiState(
                    runtimeState =
                        StrategyPackRuntimeState(
                            snapshot =
                                StrategyPackSnapshot(
                                    source = StrategyPackCatalogSourceDownloaded,
                                ),
                            selectedPackId = "browser_family_v2",
                            selectedPackVersion = "2026.04.23",
                        ),
                ),
            )

        assertEquals(R.string.strategy_pack_downloaded_status_title, status.labelResId)
        assertEquals(R.string.strategy_pack_downloaded_status_body, status.bodyResId)
        assertEquals(StatusIndicatorTone.Active, status.tone)
    }

    @Test
    fun `non coded refresh error groups into compatibility or download buckets`() {
        val compatibilityState =
            StrategyPackCatalogUiState(
                runtimeState =
                    StrategyPackRuntimeState(
                        lastRefreshError = "Requires app version 9.9.9 or newer",
                    ),
            )
        val downloadState =
            StrategyPackCatalogUiState(
                runtimeState =
                    StrategyPackRuntimeState(
                        lastRefreshError = "Remote request failed with HTTP 503",
                    ),
            )

        assertEquals(
            StrategyPackHealthFailureCodeUiModel.CompatibilityRejected,
            compatibilityState.lastRefreshFailureCode,
        )
        assertEquals(
            StrategyPackHealthFailureCodeUiModel.DownloadFailed,
            downloadState.lastRefreshFailureCode,
        )
        assertNull(StrategyPackCatalogUiState().lastRefreshFailureCode)
    }
}
