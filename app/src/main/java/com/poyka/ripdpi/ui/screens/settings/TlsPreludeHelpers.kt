package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.DefaultTlsRandRecFragmentCount
import com.poyka.ripdpi.data.DefaultTlsRandRecMaxFragmentSize
import com.poyka.ripdpi.data.DefaultTlsRandRecMinFragmentSize
import com.poyka.ripdpi.data.DefaultTlsRecordMarker
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.normalizeOffsetExpression
import com.poyka.ripdpi.data.replaceTlsPreludeTcpChainSteps

internal fun SettingsUiState.toTlsPreludeEditorStep(
    mode: String = tlsPreludeMode,
    marker: String = tlsrecMarker,
    fragmentCount: Int = tlsRandRecFragmentCount,
    minFragmentSize: Int = tlsRandRecMinFragmentSize,
    maxFragmentSize: Int = tlsRandRecMaxFragmentSize,
): TcpChainStepModel? {
    val normalizedMarker = normalizeOffsetExpression(marker, DefaultTlsRecordMarker)
    return when (mode) {
        TcpChainStepKind.TlsRec.wireName -> {
            TcpChainStepModel(
                kind = TcpChainStepKind.TlsRec,
                marker = normalizedMarker,
            )
        }

        TcpChainStepKind.TlsRandRec.wireName -> {
            TcpChainStepModel(
                kind = TcpChainStepKind.TlsRandRec,
                marker = normalizedMarker,
                fragmentCount = fragmentCount.takeIf { it > 0 } ?: DefaultTlsRandRecFragmentCount,
                minFragmentSize = minFragmentSize.takeIf { it > 0 } ?: DefaultTlsRandRecMinFragmentSize,
                maxFragmentSize = maxFragmentSize.takeIf { it > 0 } ?: DefaultTlsRandRecMaxFragmentSize,
            )
        }

        else -> {
            null
        }
    }
}

internal fun SettingsUiState.rewriteTlsPreludeChainForEditor(
    mode: String = tlsPreludeMode,
    marker: String = tlsrecMarker,
    fragmentCount: Int = tlsRandRecFragmentCount,
    minFragmentSize: Int = tlsRandRecMinFragmentSize,
    maxFragmentSize: Int = tlsRandRecMaxFragmentSize,
): List<TcpChainStepModel> =
    replaceTlsPreludeTcpChainSteps(
        tcpSteps = tcpChainSteps,
        newPreludeSteps =
            listOfNotNull(
                toTlsPreludeEditorStep(
                    mode = mode,
                    marker = marker,
                    fragmentCount = fragmentCount,
                    minFragmentSize = minFragmentSize,
                    maxFragmentSize = maxFragmentSize,
                ),
            ),
    )
