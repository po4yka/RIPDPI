package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NumericIp

internal const val MaxWarpEndpointPort = 65_535
private const val MaxWarpEndpointHostLength = 253
private const val MaxWarpEndpointLabelLength = 63

internal fun String.isWarpEndpointHost(): Boolean =
    when {
        NumericIp.parseAddress(this) != null -> {
            true
        }

        all { it in '0'..'9' || it == '.' } -> {
            false
        }

        else -> {
            length <= MaxWarpEndpointHostLength &&
                removeSuffix(".").split('.').all { label ->
                    label.length in 1..MaxWarpEndpointLabelLength && label.first() != '-' && label.last() != '-' &&
                        label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
                }
        }
    }
