package com.poyka.ripdpi.utility

import java.net.InetAddress

fun checkIp(ip: String): Boolean = parseIp(ip) != null

fun checkNotLocalIp(ip: String): Boolean = parseIp(ip)?.let { !it.isAnyLocalAddress && !it.isLoopbackAddress } ?: false

private fun parseIp(ip: String): InetAddress? =
    if (':' in ip) {
        if (ip.all { it in "0123456789abcdefABCDEF:." }) runCatching { InetAddress.getByName(ip) }.getOrNull() else null
    } else {
        val octets = ip.split('.')
        if (octets.size == Ipv4OctetCount && octets.all(::isIpv4Octet)) {
            InetAddress.getByAddress(octets.map { it.toInt().toByte() }.toByteArray())
        } else {
            null
        }
    }

private fun isIpv4Octet(value: String): Boolean =
    value.length in 1..MaxIpv4Digits && value.all { it in '0'..'9' } && value.toIntOrNull() in 0..MaxIpv4Octet

private const val Ipv4OctetCount = 4
private const val MaxIpv4Digits = 3
private const val MaxIpv4Octet = 255

private const val MaxPortNumber = 65535

fun validatePort(value: String): Boolean = value.toIntOrNull()?.let { it in 1..MaxPortNumber } ?: false

fun validateIntRange(
    value: String,
    min: Int,
    max: Int,
): Boolean = value.toIntOrNull()?.let { it in min..max } ?: false
