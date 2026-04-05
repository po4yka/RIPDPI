package com.poyka.ripdpi.data

const val EntropyModeDisabled = "disabled"
const val EntropyModePopcount = "popcount"
const val EntropyModeShannon = "shannon"
const val EntropyModeCombined = "combined"

const val DefaultEntropyPaddingTargetPermil = 3400
const val DefaultEntropyPaddingMax = 256
const val DefaultShannonEntropyTargetPermil = 7920
const val DefaultEvolutionEpsilon = 0.1

fun normalizeEntropyMode(value: String): String =
    when (value.trim().lowercase()) {
        EntropyModePopcount -> EntropyModePopcount
        EntropyModeShannon -> EntropyModeShannon
        EntropyModeCombined -> EntropyModeCombined
        else -> EntropyModeDisabled
    }

fun entropyModeFromProto(value: Int): String =
    when (value) {
        1 -> EntropyModePopcount
        2 -> EntropyModeShannon
        3 -> EntropyModeCombined
        else -> EntropyModeDisabled
    }

fun entropyModeToProto(value: String): Int =
    when (normalizeEntropyMode(value)) {
        EntropyModePopcount -> 1
        EntropyModeShannon -> 2
        EntropyModeCombined -> 3
        else -> 0
    }

