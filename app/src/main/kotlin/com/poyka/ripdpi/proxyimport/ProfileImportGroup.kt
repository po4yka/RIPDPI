package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile

internal fun ProxyProfile.importedGroup(groupId: String): ProxyGroup {
    val member =
        when (this) {
            is ProxyProfile.Vless -> copy(groupId = groupId)
            is ProxyProfile.VlessReality -> copy(groupId = groupId)
            is ProxyProfile.Shadowsocks -> copy(groupId = groupId)
            is ProxyProfile.Trojan -> copy(groupId = groupId)
            is ProxyProfile.Tuic -> copy(groupId = groupId)
            is ProxyProfile.Hysteria2 -> copy(groupId = groupId)
            is ProxyProfile.AnyTls -> copy(groupId = groupId)
            is ProxyProfile.Mieru -> copy(groupId = groupId)
            is ProxyProfile.Naive -> copy(groupId = groupId)
            is ProxyProfile.Ssh -> copy(groupId = groupId)
            is ProxyProfile.RawConfig -> copy(groupId = groupId)
        }
    return ProxyGroup(
        id = groupId,
        name = displayName,
        type = ProxyGroupType.BASIC,
        order = 0,
        isSelector = false,
        members = listOf(member),
    )
}
