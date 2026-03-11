package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val DefaultFakeSni = "www.iana.org"
const val FakeTlsSniModeFixed = "fixed"
const val FakeTlsSniModeRandomized = "randomized"

private val KnownFakeTlsSniModes = setOf(FakeTlsSniModeFixed, FakeTlsSniModeRandomized)

fun normalizeFakeTlsSniMode(value: String): String {
    val normalized = value.trim().lowercase()
    return normalized.takeIf { it in KnownFakeTlsSniModes } ?: FakeTlsSniModeFixed
}

fun AppSettings.effectiveFakeTlsSniMode(): String = normalizeFakeTlsSniMode(fakeTlsSniMode)

fun AppSettings.hasCustomFakeTlsProfile(): Boolean =
    fakeTlsUseOriginal ||
        fakeTlsRandomize ||
        fakeTlsDupSessionId ||
        fakeTlsPadEncap ||
        fakeTlsSize != 0 ||
        effectiveFakeTlsSniMode() != FakeTlsSniModeFixed ||
        (effectiveFakeTlsSniMode() == FakeTlsSniModeFixed && fakeSni.isNotBlank() && fakeSni != DefaultFakeSni)
