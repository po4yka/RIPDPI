package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val DefaultAdaptiveFakeTtlDelta = -1
const val DefaultAdaptiveFakeTtlMin = 3
const val DefaultAdaptiveFakeTtlMax = 12
const val DefaultAdaptiveFakeTtlFallback = 8

fun normalizeAdaptiveFakeTtlDelta(value: Int): Int = value.coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt())

private const val MaxTtlValue = 255

fun normalizeAdaptiveFakeTtlMin(value: Int): Int = value.coerceIn(1, MaxTtlValue)

fun normalizeAdaptiveFakeTtlMax(
    value: Int,
    min: Int = DefaultAdaptiveFakeTtlMin,
): Int = value.coerceIn(min.coerceIn(1, MaxTtlValue), MaxTtlValue)

fun normalizeAdaptiveFakeTtlFallback(
    value: Int,
    defaultValue: Int = DefaultAdaptiveFakeTtlFallback,
): Int = value.takeIf { it in 1..MaxTtlValue } ?: defaultValue.coerceIn(1, MaxTtlValue)

fun AppSettings.effectiveAdaptiveFakeTtlDelta(): Int = normalizeAdaptiveFakeTtlDelta(adaptiveFakeTtlDelta)

fun AppSettings.effectiveAdaptiveFakeTtlMin(): Int = normalizeAdaptiveFakeTtlMin(adaptiveFakeTtlMin)

fun AppSettings.effectiveAdaptiveFakeTtlMax(): Int =
    normalizeAdaptiveFakeTtlMax(adaptiveFakeTtlMax, effectiveAdaptiveFakeTtlMin())

fun AppSettings.effectiveAdaptiveFakeTtlFallback(): Int =
    normalizeAdaptiveFakeTtlFallback(
        adaptiveFakeTtlFallback,
        fakeTtl.takeIf { it > 0 } ?: DefaultAdaptiveFakeTtlFallback,
    )
