package com.poyka.ripdpi.diagnostics

import android.net.LinkProperties
import android.os.Build

enum class SystemPrivateDnsStatus(
    val wireValue: String,
) {
    PRESENT("system_private_dns_present"),

    ABSENT("ignored_for_vpn_policy"),

    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWireValue(value: String?): SystemPrivateDnsStatus =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

fun mapPrivateDnsModeToStatus(privateDnsMode: String?): SystemPrivateDnsStatus =
    when (privateDnsMode?.trim()?.lowercase()) {
        null, "", "unknown", "unavailable" -> SystemPrivateDnsStatus.UNKNOWN
        "system", "off", "none", "opportunistic" -> SystemPrivateDnsStatus.ABSENT
        else -> SystemPrivateDnsStatus.PRESENT
    }

interface SystemPrivateDnsStatusProvider {
    fun detect(linkProperties: LinkProperties?): SystemPrivateDnsStatus
}

fun mapLinkPropertiesToStatus(
    privateDnsServerName: String?,
    apiSupported: Boolean,
): SystemPrivateDnsStatus =
    when {
        !apiSupported -> SystemPrivateDnsStatus.UNKNOWN
        privateDnsServerName.isNullOrBlank() -> SystemPrivateDnsStatus.ABSENT
        else -> SystemPrivateDnsStatus.PRESENT
    }

class AndroidSystemPrivateDnsStatusProvider : SystemPrivateDnsStatusProvider {
    override fun detect(linkProperties: LinkProperties?): SystemPrivateDnsStatus =
        if (linkProperties == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            SystemPrivateDnsStatus.UNKNOWN
        } else {
            runCatching {
                mapLinkPropertiesToStatus(linkProperties.privateDnsServerName, apiSupported = true)
            }.getOrDefault(SystemPrivateDnsStatus.UNKNOWN)
        }
}
