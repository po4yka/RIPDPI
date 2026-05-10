package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.DefaultSplitMarker
import com.poyka.ripdpi.data.DefaultTlsRecordMarker
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.isTlsPrelude
import com.poyka.ripdpi.data.normalizeActivationFilter
import com.poyka.ripdpi.data.normalizeOffsetExpression
import com.poyka.ripdpi.data.normalizeTcpChainStepModel
import com.poyka.ripdpi.data.normalizeUdpChainStepModel

internal fun normalizeChainConfig(config: RipDpiChainConfig): RipDpiChainConfig =
    config.copy(
        groupActivationFilter = normalizeActivationFilter(config.groupActivationFilter),
        tcpSteps = config.tcpSteps.map(::normalizeTcpChainStep),
        udpSteps = config.udpSteps.map(::normalizeUdpChainStepModel),
        anyProtocol = config.anyProtocol,
    )

private fun normalizeMarkerForStep(
    kind: TcpChainStepKind,
    marker: String,
): String {
    val defaultValue = if (kind.isTlsPrelude) DefaultTlsRecordMarker else DefaultSplitMarker
    return normalizeOffsetExpression(marker, defaultValue)
}

private fun normalizeTcpChainStep(step: TcpChainStepModel): TcpChainStepModel =
    normalizeTcpChainStepModel(step.copy(marker = normalizeMarkerForStep(step.kind, step.marker)))
