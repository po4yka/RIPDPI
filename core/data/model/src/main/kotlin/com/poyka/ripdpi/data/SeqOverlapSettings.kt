package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val DefaultSeqOverlapSize = 12
const val SeqOverlapFakeModeProfile = "profile"
const val SeqOverlapFakeModeRand = "rand"

private val KnownSeqOverlapFakeModes = setOf(SeqOverlapFakeModeProfile, SeqOverlapFakeModeRand)

fun canonicalSeqOverlapFakeMode(value: String): String = value.trim().lowercase()

fun isValidSeqOverlapFakeMode(value: String): Boolean = canonicalSeqOverlapFakeMode(value) in KnownSeqOverlapFakeModes

fun normalizeSeqOverlapFakeMode(value: String): String {
    val normalized = canonicalSeqOverlapFakeMode(value)
    return normalized.takeIf { it in KnownSeqOverlapFakeModes } ?: SeqOverlapFakeModeProfile
}

fun AppSettings.hasSeqOverlapStep(): Boolean = effectiveTcpChainSteps().any { it.kind == TcpChainStepKind.SeqOverlap }

fun TcpChainStepModel.usesSeqOverlapFakeProfile(): Boolean =
    kind == TcpChainStepKind.SeqOverlap &&
        fakeMode == SeqOverlapFakeModeProfile
