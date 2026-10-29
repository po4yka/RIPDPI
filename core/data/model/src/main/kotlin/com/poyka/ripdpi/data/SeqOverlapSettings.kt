package com.poyka.ripdpi.data

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

fun TcpChainStepModel.usesSeqOverlapFakeProfile(): Boolean =
    kind == TcpChainStepKind.SeqOverlap &&
        fakeMode == SeqOverlapFakeModeProfile
