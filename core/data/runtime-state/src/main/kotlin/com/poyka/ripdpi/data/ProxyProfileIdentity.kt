package com.poyka.ripdpi.data

fun ProxyProfile.withIdentity(
    newId: String,
    name: String = displayName,
): ProxyProfile =
    when (this) {
        is ProxyProfile.Vless -> copy(id = newId, displayName = name)
        is ProxyProfile.VlessReality -> copy(id = newId, displayName = name)
        is ProxyProfile.Shadowsocks -> copy(id = newId, displayName = name)
        is ProxyProfile.Trojan -> copy(id = newId, displayName = name)
        is ProxyProfile.Tuic -> copy(id = newId, displayName = name)
        is ProxyProfile.Hysteria2 -> copy(id = newId, displayName = name)
        is ProxyProfile.AnyTls -> copy(id = newId, displayName = name)
        is ProxyProfile.Snell -> copy(id = newId, displayName = name)
        is ProxyProfile.Mieru -> copy(id = newId, displayName = name)
        is ProxyProfile.Naive -> copy(id = newId, displayName = name)
        is ProxyProfile.Ssh -> copy(id = newId, displayName = name)
        is ProxyProfile.RawConfig -> copy(id = newId, displayName = name)
    }
