package com.poyka.ripdpi.core.codec

import com.poyka.ripdpi.data.EnvironmentKind
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire payload model exchanged with the Rust core.
 *
 * The serialized shape of these classes is a hard contract: `kotlinx.serialization`
 * emits fields in declaration order, so field order, names, types, default values,
 * and the `@EncodeDefault` / `@SerialName` annotations must never change without a
 * matching change on the Rust side. See [RipDpiProxyJsonCodec] for the `Json`
 * instance configuration (`classDiscriminator = "kind"`, `encodeDefaults = true`).
 */
@Serializable
internal sealed interface NativeProxyConfig {
    @Serializable
    @SerialName("command_line")
    data class CommandLine(
        val args: List<String>,
        val hostAutolearnStorePath: String? = null,
        val runtimeContext: NativeRuntimeContext? = null,
        val logContext: NativeLogContext? = null,
        val sessionOverrides: NativeSessionLocalProxyOverrides? = null,
        @Required
        val schemaVersion: Int = NativeProxyConfigSchemaVersion,
    ) : NativeProxyConfig

    @Serializable
    @SerialName("ui")
    data class Ui(
        val strategyPreset: String? = null,
        val listen: NativeListenConfig = NativeListenConfig(),
        val protocols: NativeProtocolConfig = NativeProtocolConfig(),
        val chains: NativeChainConfig = NativeChainConfig(),
        val fakePackets: NativeFakePacketConfig = NativeFakePacketConfig(),
        val parserEvasions: NativeParserEvasionConfig = NativeParserEvasionConfig(),
        val adaptiveFallback: NativeAdaptiveFallbackConfig = NativeAdaptiveFallbackConfig(),
        val quic: NativeQuicConfig = NativeQuicConfig(),
        val hosts: NativeHostsConfig = NativeHostsConfig(),
        val upstreamRelay: NativeRelayConfig = NativeRelayConfig(),
        val warp: NativeWarpConfig = NativeWarpConfig(),
        val hostAutolearn: NativeHostAutolearnConfig = NativeHostAutolearnConfig(),
        val wsTunnel: NativeWsTunnelConfig = NativeWsTunnelConfig(),
        val destinationRouting: NativeDestinationRoutingConfig = NativeDestinationRoutingConfig(),
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val nativeLogLevel: String? = null,
        val rootMode: Boolean = false,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val rootHelperSocketPath: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val geoipDbPath: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val geositeDbPath: String? = null,
        // Environment classification supplied by the platform-side
        // EnvironmentDetector. Wire form is the
        // EnvironmentKind variant name ("Field" / "Emulator" /
        // "Unknown"); Rust parses it back into ripdpi_config::EnvironmentKind.
        val environmentKind: String = "Unknown",
        val runtimeContext: NativeRuntimeContext? = null,
        val logContext: NativeLogContext? = null,
        val sessionOverrides: NativeSessionLocalProxyOverrides? = null,
        @Required
        val schemaVersion: Int = NativeProxyConfigSchemaVersion,
    ) : NativeProxyConfig
}

/**
 * Current native-config wire schema version. Every [NativeProxyConfig] variant
 * must carry `schemaVersion`; missing and non-current versions are rejected.
 * Bumped only on a genuinely breaking shape change. See
 * `docs/architecture/CONFIG_CONTRACTS.md` §8.
 */
internal const val NativeProxyConfigSchemaVersion: Int = 2

/**
 * Parses the wire form of [NativeProxyConfig.Ui.environmentKind] back into an
 * [EnvironmentKind] variant, falling back to [EnvironmentKind.Unknown] for any
 * unrecognized value.
 */
internal fun decodeEnvironmentKind(value: String): EnvironmentKind {
    for (kind in enumValues<EnvironmentKind>()) {
        if (kind.name == value) return kind
    }
    return EnvironmentKind.Unknown
}
