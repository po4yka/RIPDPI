package com.poyka.ripdpi.data

const val FakeOrderDefault = "0"
const val FakeOrderAllFakesFirst = "1"
const val FakeOrderInterleaveRealFirst = "2"
const val FakeOrderAllRealsFirst = "3"

const val FakeSeqModeDuplicate = "duplicate"
const val FakeSeqModeSequential = "sequential"

val SupportedFakeOrderValues =
    setOf(
        FakeOrderDefault,
        FakeOrderAllFakesFirst,
        FakeOrderInterleaveRealFirst,
        FakeOrderAllRealsFirst,
    )

private val SupportedFakeSeqModes = setOf(FakeSeqModeDuplicate, FakeSeqModeSequential)

fun canonicalFakeOrder(value: String): String = value.trim().lowercase()

fun normalizeFakeOrder(value: String): String {
    val normalized = canonicalFakeOrder(value)
    return normalized.takeIf { it in SupportedFakeOrderValues } ?: FakeOrderDefault
}

fun canonicalFakeSeqMode(value: String): String = value.trim().lowercase()

fun normalizeFakeSeqMode(value: String): String {
    val normalized = canonicalFakeSeqMode(value)
    return normalized.takeIf { it in SupportedFakeSeqModes } ?: FakeSeqModeDuplicate
}

val TcpChainStepKind.supportsFakeOrdering: Boolean
    get() =
        this == TcpChainStepKind.Fake ||
            this == TcpChainStepKind.FakeSplit ||
            this == TcpChainStepKind.FakeDisorder ||
            this == TcpChainStepKind.HostFake
