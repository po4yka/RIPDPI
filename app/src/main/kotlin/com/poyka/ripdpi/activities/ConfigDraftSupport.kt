package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DefaultRelayLocalSocksPort
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.DefaultSnowflakeBrokerUrl
import com.poyka.ripdpi.data.DefaultSnowflakeFrontDomain
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayCloudflareTunnelModeConsumeExisting
import com.poyka.ripdpi.data.RelayCongestionControlBbr
import com.poyka.ripdpi.data.RelayFinalmaskTypeOff
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindOff
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayTrustDomainWarning
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayXhttpModeAuto
import com.poyka.ripdpi.data.StrategyChainSet
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.formatChainSummary
import com.poyka.ripdpi.data.formatStrategyChainDsl
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.primaryDesyncMethod
import com.poyka.ripdpi.data.toRelaySettingsModel
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.MasquePrivacyPassBuildStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

internal const val defaultTtlMax = 255
internal const val defaultRelayPort = 443
internal const val bufferSizeDiv = 4
internal const val relayFinalmaskFragmentPacketsMin = 1
internal const val relayFinalmaskFragmentPacketsMax = 16
internal const val relayFinalmaskFragmentBytesMin = 1
internal const val relayFinalmaskFragmentBytesMax = 65535

private val DefaultConfigDnsSeed = canonicalDefaultEncryptedDnsSettings()

@Serializable
data class ConfigDraft(
    val mode: Mode = Mode.VPN,
    val dnsIp: String = DefaultConfigDnsSeed.dnsIp,
    val dnsSummary: String = DefaultConfigDnsSeed.summary(),
    val proxyIp: String = "127.0.0.1",
    val proxyPort: String = "1080",
    val maxConnections: String = "512",
    val bufferSize: String = "16384",
    val useCommandLineSettings: Boolean = false,
    val commandLineArgs: String = "",
    @Serializable(with = TcpChainStepImmutableListSerializer::class)
    val tcpChainSteps: ImmutableList<TcpChainStepModel> = persistentListOf(),
    @Serializable(with = UdpChainStepImmutableListSerializer::class)
    val udpChainSteps: ImmutableList<UdpChainStepModel> = persistentListOf(),
    val chainDsl: String = "",
    val desyncMethod: String = "split",
    val defaultTtl: String = "",
    val relayEnabled: Boolean = false,
    val relayKind: String = RelayKindOff,
    val relayProfileId: String = DefaultRelayProfileId,
    val relayPresetId: String = "",
    val relayServer: String = "",
    val relayServerPort: String = "443",
    val relayServerName: String = "",
    val relayRealityPublicKey: String = "",
    val relayRealityShortId: String = "",
    val relayVlessTransport: String = RelayVlessTransportRealityTcp,
    val relayXhttpPath: String = "",
    val relayXhttpHost: String = "",
    val relayXhttpMode: String = RelayXhttpModeAuto,
    val relayCloudflareTunnelMode: String = RelayCloudflareTunnelModeConsumeExisting,
    val relayCloudflarePublishLocalOriginUrl: String = "",
    val relayCloudflareCredentialsRef: String = "",
    val relayCloudflareTunnelToken: String = "",
    val relayCloudflareTunnelCredentialsJson: String = "",
    val relayVlessUuid: String = "",
    val relayHysteriaPassword: String = "",
    val relayHysteriaSalamanderKey: String = "",
    val relayChainEntryServer: String = "",
    val relayChainEntryPort: String = "443",
    val relayChainEntryServerName: String = "",
    val relayChainEntryPublicKey: String = "",
    val relayChainEntryShortId: String = "",
    val relayChainEntryUuid: String = "",
    val relayChainEntryProfileId: String = "",
    val relayChainExitServer: String = "",
    val relayChainExitPort: String = "443",
    val relayChainExitServerName: String = "",
    val relayChainExitPublicKey: String = "",
    val relayChainExitShortId: String = "",
    val relayChainExitUuid: String = "",
    val relayChainExitProfileId: String = "",
    // Ordered intermediate chain hops (positions strictly between entry and exit). Entry
    // (`relayChainEntryProfileId`) is hop 0 and exit (`relayChainExitProfileId`) is the last
    // hop; this list carries only the middle hops. Persisted to the relay profile store via
    // the `relay_chain_middle_profile_ids` proto field, resolved into the ordered N-hop chain,
    // and carried over the JNI wire as `chainHops` for 3-/4-hop chains.
    @Serializable(with = StringImmutableListSerializer::class)
    val relayChainMiddleProfileIds: ImmutableList<String> = persistentListOf(),
    val relayMasqueUrl: String = "",
    val relayMasqueAuthMode: String = RelayMasqueAuthModeBearer,
    val relayMasqueAuthToken: String = "",
    val relayMasqueClientCertificateChainPem: String = "",
    val relayMasqueClientPrivateKeyPem: String = "",
    val relayMasqueUseHttp2Fallback: Boolean = true,
    val relayMasqueCloudflareGeohashEnabled: Boolean = false,
    val relayTuicUuid: String = "",
    val relayTuicPassword: String = "",
    val relayTuicZeroRtt: Boolean = false,
    val relayTuicCongestionControl: String = RelayCongestionControlBbr,
    val relayShadowTlsPassword: String = "",
    val relayShadowTlsInnerProfileId: String = "",
    val relayTrojanPassword: String = "",
    val relayNaiveUsername: String = "",
    val relayNaivePassword: String = "",
    val relayNaivePath: String = "",
    val relayPtBridgeLine: String = "",
    val relayWebTunnelUrl: String = "",
    val relaySnowflakeBrokerUrl: String = DefaultSnowflakeBrokerUrl,
    val relaySnowflakeFrontDomain: String = DefaultSnowflakeFrontDomain,
    val relayUdpEnabled: Boolean = false,
    val relayLocalSocksPort: String = DefaultRelayLocalSocksPort.toString(),
    val relayFinalmaskType: String = RelayFinalmaskTypeOff,
    val relayFinalmaskHeaderHex: String = "",
    val relayFinalmaskTrailerHex: String = "",
    val relayFinalmaskRandRange: String = "",
    val relayFinalmaskSudokuSeed: String = "",
    val relayFinalmaskFragmentPackets: String = "",
    val relayFinalmaskFragmentMinBytes: String = "",
    val relayFinalmaskFragmentMaxBytes: String = "",
) {
    val chainSummary: String
        get() = resolvedChainSet().let { formatChainSummary(it.tcpSteps, it.udpSteps) }

    val relaySummary: String
        get() =
            when {
                !relayEnabled || relayKind == RelayKindOff -> "Disabled"
                relayKind == RelayKindChainRelay -> "Chain relay"
                relayKind == RelayKindMasque -> "MASQUE"
                relayKind == RelayKindHysteria2 -> "Hysteria2"
                relayKind == RelayKindCloudflareTunnel -> "Cloudflare Tunnel"
                relayKind == RelayKindTuicV5 -> "TUIC v5"
                relayKind == RelayKindShadowTlsV3 -> "ShadowTLS v3"
                relayKind == RelayKindTrojan -> "Trojan"
                relayKind == RelayKindNaiveProxy -> "NaiveProxy"
                relayKind == RelayKindSnowflake -> "Snowflake"
                relayKind == RelayKindWebTunnel -> "WebTunnel"
                relayKind == RelayKindObfs4 -> "obfs4"
                else -> "VLESS + Reality"
            }

    fun resolvedChainSet(): StrategyChainSet =
        parseStrategyChainDsl(chainDsl).getOrNull()
            ?: StrategyChainSet(tcpSteps = tcpChainSteps, udpSteps = udpChainSteps)

    fun withChainDsl(value: String): ConfigDraft {
        val parsed = parseStrategyChainDsl(value).getOrNull()
        return copy(
            chainDsl = value,
            tcpChainSteps = parsed?.tcpSteps?.toImmutableList() ?: tcpChainSteps,
            udpChainSteps = parsed?.udpSteps?.toImmutableList() ?: udpChainSteps,
            desyncMethod = parsed?.let { primaryDesyncMethod(it.tcpSteps) } ?: desyncMethod,
        )
    }

    fun withStrategyChain(
        tcpSteps: List<TcpChainStepModel>,
        udpSteps: List<UdpChainStepModel>,
    ): ConfigDraft =
        copy(
            tcpChainSteps = tcpSteps.toImmutableList(),
            udpChainSteps = udpSteps.toImmutableList(),
            chainDsl = formatStrategyChainDsl(tcpSteps, udpSteps),
            desyncMethod = primaryDesyncMethod(tcpSteps),
        )
}

internal object TcpChainStepImmutableListSerializer : KSerializer<ImmutableList<TcpChainStepModel>> {
    private val delegate = ListSerializer(TcpChainStepModel.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: ImmutableList<TcpChainStepModel>,
    ) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): ImmutableList<TcpChainStepModel> =
        delegate.deserialize(decoder).toImmutableList()
}

internal object UdpChainStepImmutableListSerializer : KSerializer<ImmutableList<UdpChainStepModel>> {
    private val delegate = ListSerializer(UdpChainStepModel.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: ImmutableList<UdpChainStepModel>,
    ) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): ImmutableList<UdpChainStepModel> =
        delegate.deserialize(decoder).toImmutableList()
}

internal object StringImmutableListSerializer : KSerializer<ImmutableList<String>> {
    private val delegate = ListSerializer(String.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: ImmutableList<String>,
    ) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): ImmutableList<String> = delegate.deserialize(decoder).toImmutableList()
}

enum class ConfigPresetKind {
    Recommended,
    Proxy,
    Custom,
}

data class ConfigPreset(
    val id: String,
    val kind: ConfigPresetKind,
    val draft: ConfigDraft,
    val isSelected: Boolean = false,
)

data class ConfigUiState(
    val activeMode: Mode = Mode.VPN,
    val runningMode: Mode? = null,
    val uiPersona: String = "simple",
    val presets: ImmutableList<ConfigPreset> = buildConfigPresets(AppSettingsSerializer.defaultValue.toConfigDraft()),
    val editingPreset: ConfigPreset? = null,
    val draft: ConfigDraft = AppSettingsSerializer.defaultValue.toConfigDraft(),
    val validationErrors: ImmutableMap<String, String> = persistentMapOf(),
    val relayProfiles: ImmutableList<RelayProfileUiState> = persistentListOf(),
    val vpnProfiles: ImmutableList<RelayProfileUiState> = persistentListOf(),
    val relayChainTrustWarning: RelayTrustDomainWarning? = null,
    val relayChainHopStatus: RelayChainHopStatusUiState = RelayChainHopStatusUiState(),
    val relayPresets: ImmutableList<RelayPresetUiState> = persistentListOf(),
    val relayPresetSuggestion: RelayPresetSuggestionUiState? = null,
    val supportsMasquePrivacyPass: Boolean = false,
    val masquePrivacyPassBuildStatus: MasquePrivacyPassBuildStatus = MasquePrivacyPassBuildStatus.MissingProviderUrl,
    val isEditorDirty: Boolean = false,
    val isEditorLoading: Boolean = false,
    val isEditorSaving: Boolean = false,
    val isEditorImporting: Boolean = false,
    val hasEditorRecoveryPersistenceError: Boolean = false,
    val textFieldReplacementRevision: Long = 0,
    val masqueCredentialReplacementRevision: Long = 0,
    val isLoading: Boolean = false,
)

data class RelayPresetUiState(
    val id: String,
    val title: String,
    val selected: Boolean,
)

data class RelayPresetSuggestionUiState(
    val presetId: String,
    val title: String,
    val reason: String,
)

sealed interface ConfigEffect {
    data object SaveSuccess : ConfigEffect

    data object ValidationFailed : ConfigEffect

    data class EditorHydrationFailed(
        val sessionId: Long,
    ) : ConfigEffect

    data class Message(
        val text: String,
    ) : ConfigEffect
}

internal enum class ConfigEditorExitDecision {
    Blocked,
    ConfirmDiscard,
    Exit,
}

internal data class ConfigEditorSession(
    val sessionId: Long = 0L,
    val presetId: String? = null,
    val baselineDraft: ConfigDraft? = null,
    val draft: ConfigDraft? = null,
    val hydrationPending: Boolean = false,
    val draftRevision: Long = 0L,
    val textFieldReplacementRevision: Long = 0L,
    val masqueCredentialReplacementRevision: Long = 0L,
    val savePending: Boolean = false,
    val suppressSaveSuccess: Boolean = false,
) {
    val isDirty: Boolean
        get() = !hydrationPending && baselineDraft != null && draft != baselineDraft

    fun completeHydration(
        expectedSessionId: Long,
        hydratedDraft: ConfigDraft,
    ): ConfigEditorSession =
        if (sessionId == expectedSessionId && hydrationPending) {
            copy(
                baselineDraft = hydratedDraft,
                draft = hydratedDraft,
                hydrationPending = false,
                textFieldReplacementRevision = textFieldReplacementRevision + 1,
            )
        } else {
            this
        }
}

internal fun MutableStateFlow<ConfigEditorSession>.updateDraftForSession(
    expectedSessionId: Long,
    transform: ConfigDraft.() -> ConfigDraft,
): Boolean {
    while (true) {
        val current = value
        if (
            current.sessionId != expectedSessionId ||
            current.presetId == null ||
            current.hydrationPending
        ) {
            return false
        }
        val updated =
            current.copy(
                draft = requireNotNull(current.draft).transform(),
                draftRevision = current.draftRevision + 1,
            )
        if (compareAndSet(current, updated)) return true
    }
}

internal class ConfigEditorHydrationFailureHandler(
    private val editorSession: MutableStateFlow<ConfigEditorSession>,
) {
    fun abort(expectedSessionId: Long): Boolean {
        val current = editorSession.value
        return current.sessionId == expectedSessionId &&
            current.hydrationPending &&
            editorSession.compareAndSet(current, ConfigEditorSession())
    }
}

internal const val ConfigFieldDnsIp = "dnsIp"
internal const val ConfigFieldProxyIp = "proxyIp"
internal const val ConfigFieldProxyPort = "proxyPort"
internal const val ConfigFieldMaxConnections = "maxConnections"
internal const val ConfigFieldBufferSize = "bufferSize"
internal const val ConfigFieldDefaultTtl = "defaultTtl"
internal const val ConfigFieldStrategyChain = "strategyChain"
internal const val ConfigFieldRelayServerPort = "relayServerPort"
internal const val ConfigFieldRelayLocalSocksPort = "relayLocalSocksPort"
internal const val ConfigFieldRelayServer = "relayServer"
internal const val ConfigFieldRelayChain = "relayChain"
internal const val ConfigFieldRelayCredentials = "relayCredentials"
internal const val ConfigFieldRelayNaivePath = "relayNaivePath"
internal const val ConfigFieldRelayCloudflarePublishOrigin = "relayCloudflarePublishOrigin"
internal const val ConfigFieldRelayFinalmask = "relayFinalmask"

internal const val LegacyChainEntryProfileSuffix = "__ripdpi_chain_entry"
internal const val LegacyChainExitProfileSuffix = "__ripdpi_chain_exit"

/**
 * App-side mirror of the engine-api chain-relay hop bounds
 * (`RelayChainMinHops` / `RelayChainMaxHops`). The engine-api constants are not on the
 * `:app` compile classpath (`:core:engine` is forbidden and `:core:engine-api` is not a
 * direct dependency), so the UI editor mirrors them here.
 * Keep these in sync with `core/engine-api/.../RelayNativeConfig.kt` §RelayChain*Hops.
 */
internal const val RelayChainMinHopsUi = 2
internal const val RelayChainMaxHopsUi = 4

internal fun AppSettings.toConfigDraft(): ConfigDraft =
    ConfigDraft(
        mode = Mode.fromString(ripdpiMode.ifEmpty { "vpn" }),
        dnsIp = activeDnsSettings().dnsIp,
        dnsSummary = activeDnsSettings().summary(),
        proxyIp = proxyIp.ifEmpty { "127.0.0.1" },
        proxyPort = (proxyPort.takeIf { it > 0 } ?: 1080).toString(),
        maxConnections = (maxConnections.takeIf { it > 0 } ?: 512).toString(),
        bufferSize = (bufferSize.takeIf { it > 0 } ?: 16_384).toString(),
        useCommandLineSettings = enableCmdSettings,
        commandLineArgs = cmdArgs,
        tcpChainSteps = effectiveTcpChainSteps().toImmutableList(),
        udpChainSteps = effectiveUdpChainSteps().toImmutableList(),
        chainDsl = formatStrategyChainDsl(effectiveTcpChainSteps(), effectiveUdpChainSteps()),
        desyncMethod = primaryDesyncMethod(effectiveTcpChainSteps()).ifEmpty { "none" },
        defaultTtl = if (customTtl && defaultTtl > 0) defaultTtl.toString() else "",
    ).withRelaySettings(toRelaySettingsModel())

internal fun buildConfigPresets(currentDraft: ConfigDraft): ImmutableList<ConfigPreset> {
    val recommendedDraft = AppSettingsSerializer.defaultValue.toConfigDraft()
    val proxyDraft = recommendedDraft.copy(mode = Mode.Proxy)
    val selectedId =
        when (currentDraft) {
            recommendedDraft -> "recommended"
            proxyDraft -> "proxy"
            else -> "custom"
        }

    return persistentListOf(
        ConfigPreset(
            id = "recommended",
            kind = ConfigPresetKind.Recommended,
            draft = recommendedDraft,
            isSelected = selectedId == "recommended",
        ),
        ConfigPreset(
            id = "proxy",
            kind = ConfigPresetKind.Proxy,
            draft = proxyDraft,
            isSelected = selectedId == "proxy",
        ),
        ConfigPreset(
            id = "custom",
            kind = ConfigPresetKind.Custom,
            draft = currentDraft,
            isSelected = selectedId == "custom",
        ),
    )
}

internal fun sanitizeMasqueAuthModeForCurrentBuild(
    draft: ConfigDraft,
    supportsMasquePrivacyPass: Boolean,
): ConfigDraft =
    if (!supportsMasquePrivacyPass && draft.relayMasqueAuthMode == RelayMasqueAuthModePrivacyPass) {
        draft.copy(
            relayMasqueAuthMode = RelayMasqueAuthModeBearer,
        )
    } else {
        draft
    }
