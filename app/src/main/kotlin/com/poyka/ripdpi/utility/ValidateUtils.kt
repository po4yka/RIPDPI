package com.poyka.ripdpi.utility

import com.poyka.ripdpi.data.NumericIp

fun checkIp(ip: String): Boolean = NumericIp.parseAddress(ip) != null

fun checkNotLocalIp(ip: String): Boolean {
    val parsed = NumericIp.parseAddress(ip) ?: return false
    // A mapped IPv4 payload retains its local-address restrictions.
    val address = if (parsed.isIPv6 && parsed.toIPv6().isIPv4Mapped) parsed.toIPv4() else parsed
    return address.toInetAddress().let { !it.isAnyLocalAddress && !it.isLoopbackAddress }
}

const val MinPortNumber = 1
const val MaxPortNumber = 65535

fun validatePort(value: String): Boolean = value.toIntOrNull()?.let { it in MinPortNumber..MaxPortNumber } ?: false

fun validateIntRange(
    value: String,
    min: Int,
    max: Int,
): Boolean = value.toIntOrNull()?.let { it in min..max } ?: false
