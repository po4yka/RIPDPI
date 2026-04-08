package com.poyka.ripdpi.core.detection.probe

enum class ScanMode {
    AUTO,
    MANUAL,
}

enum class ScanPhase {
    POPULAR_PORTS,
    FULL_RANGE,
}

enum class ProxyType {
    SOCKS5,
    HTTP,
}

data class ProxyEndpoint(
    val host: String,
    val port: Int,
    val type: ProxyType,
)

data class ScanProgress(
    val phase: ScanPhase,
    val scanned: Int,
    val total: Int,
    val currentPort: Int,
)

data class XrayApiEndpoint(
    val host: String,
    val port: Int,
)

data class XrayOutboundSummary(
    val tag: String,
    val protocolName: String?,
    val address: String?,
    val port: Int?,
    val uuid: String?,
    val sni: String?,
    val publicKey: String?,
    val senderSettingsType: String?,
    val proxySettingsType: String?,
)

data class XrayApiScanResult(
    val endpoint: XrayApiEndpoint,
    val outbounds: List<XrayOutboundSummary>,
)

data class XrayScanProgress(
    val host: String,
    val scanned: Int,
    val total: Int,
    val currentPort: Int,
)
