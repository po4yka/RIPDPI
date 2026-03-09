package com.poyka.ripdpi.utility

import android.net.InetAddresses
import android.os.Build

fun checkIp(ip: String): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        InetAddresses.isNumericAddress(ip)
    } else {
        true
    }

fun checkNotLocalIp(ip: String): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        InetAddresses.isNumericAddress(ip) && InetAddresses.parseNumericAddress(ip).let {
            !it.isAnyLocalAddress && !it.isLoopbackAddress
        }
    } else {
        true
    }

fun validatePort(value: String): Boolean =
    value.toIntOrNull()?.let { it in 1..65535 } ?: false

fun validateIntRange(value: String, min: Int, max: Int): Boolean =
    value.toIntOrNull()?.let { it in min..max } ?: false
