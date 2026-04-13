package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val IpIdModeDefault = ""
const val IpIdModeSeq = "seq"
const val IpIdModeSeqGroup = "seqgroup"
const val IpIdModeRnd = "rnd"
const val IpIdModeZero = "zero"

private val KnownIpIdModes = setOf(IpIdModeSeq, IpIdModeSeqGroup, IpIdModeRnd, IpIdModeZero)

fun normalizeIpIdMode(value: String): String {
    val normalized = value.trim().lowercase()
    return normalized.takeIf { it in KnownIpIdModes } ?: IpIdModeDefault
}

fun AppSettings.effectiveIpIdMode(): String = normalizeIpIdMode(ipIdMode)
