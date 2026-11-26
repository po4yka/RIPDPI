package com.poyka.ripdpi.data

/** The protocol name shown for a profile. */
fun ProxyProfile.kindLabel(): String =
    when (this) {
        is ProxyProfile.Vless -> "VLESS"
        is ProxyProfile.VlessReality -> "VLESS + REALITY"
        is ProxyProfile.Shadowsocks -> "Shadowsocks"
        is ProxyProfile.Trojan -> "Trojan"
        is ProxyProfile.Tuic -> "TUIC"
        is ProxyProfile.Hysteria2 -> "Hysteria2"
        is ProxyProfile.AnyTls -> "AnyTLS"
        is ProxyProfile.Snell -> "Snell"
        is ProxyProfile.Mieru -> "Mieru"
        is ProxyProfile.Naive -> "NaiveProxy"
        is ProxyProfile.Ssh -> "SSH"
        is ProxyProfile.RawConfig -> "Raw URI"
    }

/** The name shown for an exit: its saved remark, or its kind when the remark is blank or names its host. */
fun ProxyProfile.exitName(): String {
    val host = host()
    return displayName.takeUnless { it.isBlank() || (host != null && host in it) } ?: kindLabel()
}

fun List<ProxyGroup>.serverNames(): Map<String, String> =
    flatMap { it.members }.associate { it.id to it.displayName.ifBlank { it.host() ?: it.kindLabel() } }

private fun ProxyProfile.host(): String? =
    when (this) {
        is ProxyProfile.Vless -> server
        is ProxyProfile.VlessReality -> server
        is ProxyProfile.Shadowsocks -> server
        is ProxyProfile.Trojan -> server
        is ProxyProfile.Tuic -> server
        is ProxyProfile.Hysteria2 -> server
        is ProxyProfile.AnyTls -> server
        is ProxyProfile.Snell -> server
        is ProxyProfile.Mieru -> server
        is ProxyProfile.Naive -> server
        is ProxyProfile.Ssh -> server
        is ProxyProfile.RawConfig -> null
    }.takeUnless { it.isNullOrBlank() }
