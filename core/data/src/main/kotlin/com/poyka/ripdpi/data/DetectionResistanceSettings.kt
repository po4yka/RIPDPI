package com.poyka.ripdpi.data

const val TlsFingerprintProfileChromeStable = "chrome_stable"
const val TlsFingerprintProfileFirefoxStable = "firefox_stable"

const val EntropyModeDisabled = "disabled"
const val EntropyModePopcount = "popcount"
const val EntropyModeShannon = "shannon"
const val EntropyModeCombined = "combined"

const val DefaultEntropyPaddingTargetPermil = 3400
const val DefaultEntropyPaddingMax = 256
const val DefaultShannonEntropyTargetPermil = 7920
const val DefaultEvolutionEpsilon = 0.1

fun normalizeTlsFingerprintProfile(value: String): String =
    when (value.trim().lowercase()) {
        TlsFingerprintProfileChromeStable -> TlsFingerprintProfileChromeStable
        TlsFingerprintProfileFirefoxStable -> TlsFingerprintProfileFirefoxStable
        "native_default" -> TlsFingerprintProfileChromeStable
        else -> TlsFingerprintProfileChromeStable
    }

fun tlsFingerprintProfileSummary(value: String): String =
    when (normalizeTlsFingerprintProfile(value)) {
        TlsFingerprintProfileChromeStable -> "Chrome stable"
        TlsFingerprintProfileFirefoxStable -> "Firefox stable"
        else -> "Chrome stable"
    }

fun normalizeEntropyMode(value: String): String =
    when (value.trim().lowercase()) {
        EntropyModePopcount -> EntropyModePopcount
        EntropyModeShannon -> EntropyModeShannon
        EntropyModeCombined -> EntropyModeCombined
        else -> EntropyModeDisabled
    }

private const val EntropyModeCombinedProto = 3

fun entropyModeFromProto(value: Int): String =
    when (value) {
        1 -> EntropyModePopcount
        2 -> EntropyModeShannon
        EntropyModeCombinedProto -> EntropyModeCombined
        else -> EntropyModeDisabled
    }

fun entropyModeToProto(value: String): Int =
    when (normalizeEntropyMode(value)) {
        EntropyModePopcount -> 1
        EntropyModeShannon -> 2
        EntropyModeCombined -> EntropyModeCombinedProto
        else -> 0
    }
