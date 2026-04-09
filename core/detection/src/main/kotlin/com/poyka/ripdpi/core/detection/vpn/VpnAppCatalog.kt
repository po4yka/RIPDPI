package com.poyka.ripdpi.core.detection.vpn

import com.poyka.ripdpi.core.detection.VpnAppKind

enum class VpnClientSignal {
    VPN_SERVICE,
    LOCAL_PROXY,
    XRAY_API,
}

data class VpnAppSignature(
    val packageName: String,
    val appName: String,
    val family: String,
    val kind: VpnAppKind,
    val defaultPorts: Set<Int> = emptySet(),
    val signals: Set<VpnClientSignal> = setOf(VpnClientSignal.VPN_SERVICE),
)

object VpnAppCatalog {
    const val FAMILY_XRAY = "Xray/V2Ray"
    const val FAMILY_SING_BOX = "sing-box"
    const val FAMILY_NEKOBOX = "NekoBox"
    const val FAMILY_HAPP = "HAPP"
    const val FAMILY_HIDDIFY = "Hiddify"
    const val FAMILY_CLASH = "Clash"
    const val FAMILY_SHADOWSOCKS = "Shadowsocks"
    const val FAMILY_TOR = "Tor/Orbot"
    const val FAMILY_OUTLINE = "Outline"
    const val FAMILY_WIREGUARD = "WireGuard"
    const val FAMILY_IPSEC = "IPSec/L2TP"
    const val FAMILY_PSIPHON = "Psiphon"
    const val FAMILY_LANTERN = "Lantern"
    const val FAMILY_DPI = "path optimization"
    const val FAMILY_AMNEZIA = "AmneziaVPN"

    val signatures: List<VpnAppSignature> =
        listOf(
            VpnAppSignature(
                packageName = "com.v2ray.ang",
                appName = "v2rayNG",
                family = FAMILY_XRAY,
                kind = VpnAppKind.TARGETED_BYPASS,
                defaultPorts = setOf(1080, 10808, 10809),
                signals = setOf(VpnClientSignal.VPN_SERVICE, VpnClientSignal.LOCAL_PROXY, VpnClientSignal.XRAY_API),
            ),
            VpnAppSignature(
                packageName = "io.github.saeeddev94.xray",
                appName = "Xray",
                family = FAMILY_XRAY,
                kind = VpnAppKind.TARGETED_BYPASS,
                defaultPorts = setOf(1080, 10808, 10809),
                signals = setOf(VpnClientSignal.VPN_SERVICE, VpnClientSignal.LOCAL_PROXY, VpnClientSignal.XRAY_API),
            ),
            VpnAppSignature(
                packageName = "io.nekohasekai.sfa",
                appName = "sing-box",
                family = FAMILY_SING_BOX,
                kind = VpnAppKind.TARGETED_BYPASS,
                defaultPorts = setOf(1080, 2080, 2081),
                signals = setOf(VpnClientSignal.VPN_SERVICE, VpnClientSignal.LOCAL_PROXY),
            ),
            VpnAppSignature(
                packageName = "moe.nb4a",
                appName = "NekoBox",
                family = FAMILY_NEKOBOX,
                kind = VpnAppKind.TARGETED_BYPASS,
                defaultPorts = setOf(1080, 2080, 2081),
                signals = setOf(VpnClientSignal.VPN_SERVICE, VpnClientSignal.LOCAL_PROXY),
            ),
            VpnAppSignature(
                packageName = "com.happproxy",
                appName = "HAPP VPN",
                family = FAMILY_HAPP,
                kind = VpnAppKind.TARGETED_BYPASS,
                defaultPorts = setOf(1080, 8080),
                signals = setOf(VpnClientSignal.VPN_SERVICE, VpnClientSignal.LOCAL_PROXY),
            ),
            VpnAppSignature(
                packageName = "app.hiddify.com",
                appName = "Hiddify",
                family = FAMILY_HIDDIFY,
                kind = VpnAppKind.TARGETED_BYPASS,
                defaultPorts = setOf(1080, 12334),
                signals = setOf(VpnClientSignal.VPN_SERVICE, VpnClientSignal.LOCAL_PROXY),
            ),
            VpnAppSignature(
                packageName = "com.github.metacubex.clash.meta",
                appName = "ClashMeta for Android",
                family = FAMILY_CLASH,
                kind = VpnAppKind.GENERIC_VPN,
                defaultPorts = setOf(7890, 7891),
                signals = setOf(VpnClientSignal.VPN_SERVICE, VpnClientSignal.LOCAL_PROXY),
            ),
            VpnAppSignature(
                packageName = "com.github.shadowsocks",
                appName = "Shadowsocks",
                family = FAMILY_SHADOWSOCKS,
                kind = VpnAppKind.GENERIC_VPN,
                defaultPorts = setOf(1080),
                signals = setOf(VpnClientSignal.VPN_SERVICE, VpnClientSignal.LOCAL_PROXY),
            ),
            VpnAppSignature(
                packageName = "com.github.shadowsocks.tv",
                appName = "Shadowsocks TV",
                family = FAMILY_SHADOWSOCKS,
                kind = VpnAppKind.GENERIC_VPN,
                defaultPorts = setOf(1080),
                signals = setOf(VpnClientSignal.VPN_SERVICE, VpnClientSignal.LOCAL_PROXY),
            ),
            VpnAppSignature(
                packageName = "io.github.dovecoteescapee.byedpi",
                appName = "ByeDPI",
                family = FAMILY_DPI,
                kind = VpnAppKind.TARGETED_BYPASS,
            ),
            VpnAppSignature(
                packageName = "com.romanvht.byebyedpi",
                appName = "ByeByeDPI",
                family = FAMILY_DPI,
                kind = VpnAppKind.TARGETED_BYPASS,
            ),
            VpnAppSignature(
                packageName = "org.outline.android.client",
                appName = "Outline",
                family = FAMILY_OUTLINE,
                kind = VpnAppKind.GENERIC_VPN,
            ),
            VpnAppSignature(
                packageName = "com.psiphon3",
                appName = "Psiphon",
                family = FAMILY_PSIPHON,
                kind = VpnAppKind.GENERIC_VPN,
            ),
            VpnAppSignature(
                packageName = "org.getlantern.lantern",
                appName = "Lantern",
                family = FAMILY_LANTERN,
                kind = VpnAppKind.GENERIC_VPN,
            ),
            VpnAppSignature(
                packageName = "com.wireguard.android",
                appName = "WireGuard",
                family = FAMILY_WIREGUARD,
                kind = VpnAppKind.GENERIC_VPN,
            ),
            VpnAppSignature(
                packageName = "com.strongswan.android",
                appName = "strongSwan",
                family = FAMILY_IPSEC,
                kind = VpnAppKind.GENERIC_VPN,
            ),
            VpnAppSignature(
                packageName = "org.torproject.android",
                appName = "Tor Browser",
                family = FAMILY_TOR,
                kind = VpnAppKind.GENERIC_VPN,
            ),
            VpnAppSignature(
                packageName = "info.guardianproject.orfox",
                appName = "Orbot",
                family = FAMILY_TOR,
                kind = VpnAppKind.GENERIC_VPN,
            ),
            VpnAppSignature(
                packageName = "org.torproject.torbrowser",
                appName = "Tor Browser (official)",
                family = FAMILY_TOR,
                kind = VpnAppKind.GENERIC_VPN,
            ),
            VpnAppSignature(
                packageName = "org.amnezia.vpn",
                appName = "AmneziaVPN",
                family = FAMILY_AMNEZIA,
                kind = VpnAppKind.GENERIC_VPN,
            ),
            VpnAppSignature(
                packageName = "org.amnezia.awg",
                appName = "AmneziaWG",
                family = FAMILY_AMNEZIA,
                kind = VpnAppKind.GENERIC_VPN,
            ),
        )

    val knownPackageNames: Set<String> = signatures.mapTo(linkedSetOf()) { it.packageName }

    val localhostProxyPorts: List<Int> =
        signatures
            .flatMapTo(linkedSetOf()) { it.defaultPorts }
            .sorted()

    fun findByPackageName(packageName: String): VpnAppSignature? =
        signatures.firstOrNull { it.packageName == packageName }

    fun familiesForPort(port: Int): Set<String> =
        signatures.filter { port in it.defaultPorts }.mapTo(linkedSetOf()) { it.family }
}
