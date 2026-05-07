package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.RelayFinalmaskTypeFragment
import com.poyka.ripdpi.data.RelayFinalmaskTypeHeaderCustom
import com.poyka.ripdpi.data.RelayFinalmaskTypeNoise
import com.poyka.ripdpi.data.RelayFinalmaskTypeOff
import com.poyka.ripdpi.data.RelayFinalmaskTypeSudoku
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.normalizeRelayFinalmaskType
import com.poyka.ripdpi.utility.validateIntRange

internal fun ConfigDraft.supportsUdpRelay(): Boolean = relayKind.supportsRelayUdpMode()

internal fun ConfigDraft.supportsFinalmask(): Boolean =
    relayKind == RelayKindVlessReality ||
        relayKind == RelayKindCloudflareTunnel

@Suppress("ReturnCount")
internal fun validateRelayFinalmaskDraft(draft: ConfigDraft): String? {
    val finalmaskType = normalizeRelayFinalmaskType(draft.relayFinalmaskType)
    if (finalmaskType == RelayFinalmaskTypeOff) {
        return null
    }
    if (
        draft.relayKind == RelayKindVlessReality &&
        draft.relayVlessTransport != RelayVlessTransportXhttp
    ) {
        return "unsupported"
    }
    if (!draft.supportsFinalmask()) {
        return "unsupported"
    }
    return when (finalmaskType) {
        RelayFinalmaskTypeHeaderCustom -> validateHeaderCustomFinalmaskDraft(draft)
        RelayFinalmaskTypeNoise -> validateNoiseFinalmaskDraft(draft)
        RelayFinalmaskTypeSudoku -> if (draft.relayFinalmaskSudokuSeed.isBlank()) "required" else null
        RelayFinalmaskTypeFragment -> validateFragmentFinalmaskDraft(draft)
        else -> null
    }
}

private fun validateHeaderCustomFinalmaskDraft(draft: ConfigDraft): String? =
    when {
        draft.relayFinalmaskRandRange.isNotBlank() &&
            !draft.relayFinalmaskRandRange.matches(Regex("\\d+-\\d+")) -> "invalid_range"

        draft.relayFinalmaskHeaderHex.isBlank() && draft.relayFinalmaskTrailerHex.isBlank() -> "required"

        else -> null
    }

private fun validateNoiseFinalmaskDraft(draft: ConfigDraft): String? =
    when {
        draft.relayFinalmaskRandRange.isBlank() -> {
            "required"
        }

        !draft.relayFinalmaskRandRange.matches(Regex("\\d+-\\d+")) -> {
            "invalid_range"
        }

        else -> {
            val (min, max) =
                draft.relayFinalmaskRandRange
                    .split('-', limit = 2)
                    .mapNotNull { it.toIntOrNull() }
                    .let { values ->
                        if (values.size == 2) values[0] to values[1] else return "invalid_range"
                    }
            if (min > max) "invalid_range" else null
        }
    }

private fun validateFragmentFinalmaskDraft(draft: ConfigDraft): String? =
    if (
        !validateIntRange(
            draft.relayFinalmaskFragmentPackets,
            relayFinalmaskFragmentPacketsMin,
            relayFinalmaskFragmentPacketsMax,
        ) ||
        !validateIntRange(
            draft.relayFinalmaskFragmentMinBytes,
            relayFinalmaskFragmentBytesMin,
            relayFinalmaskFragmentBytesMax,
        ) ||
        !validateIntRange(
            draft.relayFinalmaskFragmentMaxBytes,
            relayFinalmaskFragmentBytesMin,
            relayFinalmaskFragmentBytesMax,
        )
    ) {
        "out_of_range"
    } else {
        null
    }
