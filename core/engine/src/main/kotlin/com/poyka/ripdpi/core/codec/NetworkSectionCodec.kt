package com.poyka.ripdpi.core.codec

import com.poyka.ripdpi.core.RipDpiHostAutolearnConfig
import com.poyka.ripdpi.core.RipDpiHostsConfig
import com.poyka.ripdpi.core.RipDpiListenConfig
import com.poyka.ripdpi.core.RipDpiProtocolConfig
import com.poyka.ripdpi.core.RipDpiQuicConfig
import kotlinx.serialization.Serializable

@Serializable
internal data class NativeListenConfig(
    val ip: String = "127.0.0.1",
    val port: Int = 1080,
    val maxConnections: Int = 512,
    val bufferSize: Int = 16384,
    val tcpFastOpen: Boolean = false,
    val defaultTtl: Int = 0,
    val customTtl: Boolean = false,
    val freezeDetectionEnabled: Boolean = false,
    val authToken: String? = null,
)

@Serializable
internal data class NativeProtocolConfig(
    val resolveDomains: Boolean = true,
    val desyncHttp: Boolean = true,
    val desyncHttps: Boolean = true,
    val desyncUdp: Boolean = false,
)

@Serializable
internal data class NativeQuicConfig(
    val initialMode: String = "route_and_cache",
    val supportV1: Boolean = true,
    val supportV2: Boolean = true,
    val fakeProfile: String = "disabled",
    val fakeHost: String = "",
)

@Serializable
internal data class NativeHostsConfig(
    val mode: String = "disable",
    val entries: String? = null,
)

@Serializable
internal data class NativeHostAutolearnConfig(
    val enabled: Boolean = false,
    val penaltyTtlHours: Int = 6,
    val maxHosts: Int = 512,
    val storePath: String? = null,
    val networkScopeKey: String? = null,
)

internal object NetworkSectionCodec {
    fun toModel(value: NativeListenConfig): RipDpiListenConfig =
        RipDpiListenConfig(
            ip = value.ip,
            port = value.port,
            maxConnections = value.maxConnections,
            bufferSize = value.bufferSize,
            tcpFastOpen = value.tcpFastOpen,
            defaultTtl = value.defaultTtl,
            customTtl = value.customTtl,
            freezeDetectionEnabled = value.freezeDetectionEnabled,
        )

    fun toNative(value: RipDpiListenConfig): NativeListenConfig =
        NativeListenConfig(
            ip = value.ip,
            port = value.port,
            maxConnections = value.maxConnections,
            bufferSize = value.bufferSize,
            tcpFastOpen = value.tcpFastOpen,
            defaultTtl = value.defaultTtl,
            customTtl = value.customTtl,
            freezeDetectionEnabled = value.freezeDetectionEnabled,
        )

    fun toModel(value: NativeProtocolConfig): RipDpiProtocolConfig =
        RipDpiProtocolConfig(
            resolveDomains = value.resolveDomains,
            desyncHttp = value.desyncHttp,
            desyncHttps = value.desyncHttps,
            desyncUdp = value.desyncUdp,
        )

    fun toNative(value: RipDpiProtocolConfig): NativeProtocolConfig =
        NativeProtocolConfig(
            resolveDomains = value.resolveDomains,
            desyncHttp = value.desyncHttp,
            desyncHttps = value.desyncHttps,
            desyncUdp = value.desyncUdp,
        )

    fun toModel(value: NativeQuicConfig): RipDpiQuicConfig =
        RipDpiQuicConfig(
            initialMode = value.initialMode,
            supportV1 = value.supportV1,
            supportV2 = value.supportV2,
            fakeProfile = value.fakeProfile,
            fakeHost = value.fakeHost,
        )

    fun toNative(value: RipDpiQuicConfig): NativeQuicConfig =
        NativeQuicConfig(
            initialMode = value.initialMode,
            supportV1 = value.supportV1,
            supportV2 = value.supportV2,
            fakeProfile = value.fakeProfile,
            fakeHost = value.fakeHost,
        )

    fun toModel(value: NativeHostsConfig): RipDpiHostsConfig =
        RipDpiHostsConfig(
            mode = RipDpiHostsConfig.Mode.fromWireName(value.mode),
            entries = value.entries,
        )

    fun toNative(value: RipDpiHostsConfig): NativeHostsConfig =
        NativeHostsConfig(
            mode = value.mode.wireName,
            entries = value.entries,
        )

    fun toModel(value: NativeHostAutolearnConfig): RipDpiHostAutolearnConfig =
        RipDpiHostAutolearnConfig(
            enabled = value.enabled,
            penaltyTtlHours = value.penaltyTtlHours,
            maxHosts = value.maxHosts,
            storePath = value.storePath,
            networkScopeKey = value.networkScopeKey,
        )

    fun toNative(value: RipDpiHostAutolearnConfig): NativeHostAutolearnConfig =
        NativeHostAutolearnConfig(
            enabled = value.enabled,
            penaltyTtlHours = value.penaltyTtlHours,
            maxHosts = value.maxHosts,
            storePath = value.storePath,
            networkScopeKey = value.networkScopeKey,
        )
}
