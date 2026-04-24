package com.poyka.ripdpi.data

const val DefaultHostAutolearnPenaltyTtlHours: Int = 6
const val DefaultHostAutolearnMaxHosts: Int = 512

fun normalizeHostAutolearnPenaltyTtlHours(value: Int): Int =
    value.takeIf { it > 0 } ?: DefaultHostAutolearnPenaltyTtlHours

fun normalizeHostAutolearnMaxHosts(value: Int): Int = value.takeIf { it > 0 } ?: DefaultHostAutolearnMaxHosts
