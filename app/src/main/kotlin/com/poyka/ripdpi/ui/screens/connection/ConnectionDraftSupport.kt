package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DefaultRelayLocalSocksPort
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.DefaultSnowflakeBrokerUrl
import com.poyka.ripdpi.data.DefaultSnowflakeFrontDomain
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayCloudflareTunnelModeConsumeExisting
import com.poyka.ripdpi.data.RelayCongestionControlBbr
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayFinalmaskTypeOff
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindGoogleAppsScript
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindMieru
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindOff
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindShadowsocks
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindSsh
import com.poyka.ripdpi.data.RelayKindTor
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayProfileRecord
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
data class ConnectionDraft(
    val mode: Mode = Mode.VPN,
    val dnsIp: String = DefaultConfigDnsSeed.dnsIp,
    // Inputs of the localized DNS summary (dnsSummaryLabel); the draft carries no display text.
    val dnsMode: String = DefaultConfigDnsSeed.mode,
    val dnsProviderId: String = DefaultConfigDnsSeed.providerId,
    val encryptedDnsProtocol: String = DefaultConfigDnsSeed.encryptedDnsProtocol,
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
    val editingRelayProfileId: String = "",
    val editingRelayProfileAtOpen: RelayProfileRecord? = null,
    val editingRelayCredentialsAtOpen: RelayCredentialRecord? = null,
    val relayPresetId: String = "",
    val sourceRelayProfile: RelayProfileRecord? = null,
    val sourceRelayCredentials: RelayCredentialRecord? = null,
    val relayServer: String = "",
    val relayServerPort: String = "443",
    val relayServerName: String = "",
    val relayRealityPublicKey: String = "",
    val relayRealityShortId: String = "",
    val relayVlessTransport: String = RelayVlessTransportRealityTcp,
    val relayVlessFlow: String? = null,
    val relayXhttpPath: String = "",
    val relayXhttpHost: String = "",
    val relayXhttpMode: String = RelayXhttpModeAuto,
    val relayCloudflareTunnelMode: String = RelayCloudflareTunnelModeConsumeExisting,
    val relayCloudflarePublishLocalOriginUrl: String = "",
    val relayCloudflareCredentialsRef: String = "",
    val relayCloudflareTunnelToken: String = "",
    val relayCloudflareTunnelCredentialsJson: String = "",
    val relayVlessUuid: String = "",
    val relayAnyTlsPassword: String = "",
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
    val relayShadowsocksMethod: String = "",
    val relayShadowsocksPassword: String = "",
    val relayAppsScriptScriptIds: String = "",
    val relayAppsScriptGoogleIp: String = "",
    val relayAppsScriptFrontDomain: String = "",
    val relayAppsScriptSniHosts: String = "",
    val relayAppsScriptVerifySsl: Boolean = true,
    val relayAppsScriptParallelRelay: Boolean = false,
    val relayAppsScriptDirectHosts: String = "",
    val relayAppsScriptAuthKey: String = "",
    val relayMieruProtocol: String = "tcp",
    val relayMieruMultiplexing: String = "middle",
    val relayMieruMtu: String = "1400",
    val relayMieruUsername: String = "",
    val relayMieruPassword: String = "",
    val relaySshAuthType: String = "password",
    val relaySshHostKeyFingerprint: String = "",
    val relaySshStrictHostKey: Boolean = false,
    val relaySshUsername: String = "",
    val relaySshPassword: String = "",
    val relaySshPrivateKey: String = "",
    val relaySshPrivateKeyPassphrase: String = "",
    val relayNaiveUsername: String = "",
    val relayNaivePassword: String = "",
    val relayNaivePath: String = "",
    val relayNaiveExtraHeaders: String = "",
    val relayNaiveServerPinSha256: String = "",
    val relayNaiveQuic: Boolean = false,
    val relayNaiveConcurrency: String = "1",
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
    override fun toString(): String = "ConfigDraft(mode=$mode, relayEnabled=$relayEnabled, fields=<redacted>)"

    val chainSummary: String
        get() = resolvedChainSet().let { formatChainSummary(it.tcpSteps, it.udpSteps) }

    val relaySummary: String
        get() =
            when {
                !relayEnabled || relayKind == RelayKindOff -> "Disabled"
                relayKind == RelayKindChainRelay -> "Chain relay"
                relayKind == RelayKindMasque -> "MASQUE"
                relayKind == RelayKindHysteria2 -> "Hysteria2"
                relayKind == RelayKindAnyTls -> "AnyTLS"
                relayKind == RelayKindCloudflareTunnel -> "Cloudflare Tunnel"
                relayKind == RelayKindTuicV5 -> "TUIC v5"
                relayKind == RelayKindShadowTlsV3 -> "ShadowTLS v3"
                relayKind == RelayKindTrojan -> "Trojan"
                relayKind == RelayKindVless -> "VLESS TLS"
                relayKind == RelayKindShadowsocks -> "Shadowsocks"
                relayKind == RelayKindGoogleAppsScript -> "Google Apps Script"
                relayKind == RelayKindMieru -> "Mieru"
                relayKind == RelayKindSsh -> "SSH"
                relayKind == RelayKindNaiveProxy -> "NaiveProxy"
                relayKind == RelayKindSnowflake -> "Snowflake"
                relayKind == RelayKindWebTunnel -> "WebTunnel"
                relayKind == RelayKindObfs4 -> "obfs4"
                relayKind == RelayKindTor -> "Tor"
                else -> relayKind.relayKindLabel()
            }

    fun resolvedChainSet(): StrategyChainSet =
        parseStrategyChainDsl(chainDsl).getOrNull()
            ?: StrategyChainSet(tcpSteps = tcpChainSteps, udpSteps = udpChainSteps)

    fun withChainDsl(value: String): ConnectionDraft {
        val parsed = parseStrategyChainDsl(value).getOrNull()
        return copy(
            chainDsl = value,
            tcpChainSteps = parsed?.tcpSteps?.toImmutableList() ?: tcpChainSteps,
            udpChainSteps = parsed?.udpSteps?.toImmutableList() ?: udpChainSteps,
            desyncMethod = parsed?.let { primaryDesyncMethod(it.tcpSteps) } ?: desyncMethod,
        )
    }
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

enum class ConnectionPresetKind {
    Recommended,
    Custom,
}

data class ConnectionPreset(
    val id: String,
    val kind: ConnectionPresetKind,
    val draft: ConnectionDraft,
    val isSelected: Boolean = false,
)

data class ConnectionUiState(
    val activeMode: Mode = Mode.VPN,
    val activeRelayProfileId: String = DefaultRelayProfileId,
    val activeRelayEnabled: Boolean = false,
    val runningMode: Mode? = null,
    val runtimeOperation: ConnectionRuntimeOperation? = null,
    val runtimeError: String? = null,
    val uiPersona: String = "simple",
    val presets: ImmutableList<ConnectionPreset> =
        buildConfigPresets(AppSettingsSerializer.defaultValue.toConfigDraft()),
    val editingPreset: ConnectionPreset? = null,
    val draft: ConnectionDraft = AppSettingsSerializer.defaultValue.toConfigDraft(),
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

sealed interface ConnectionEffect {
    data object SaveSuccess : ConnectionEffect

    data object ValidationFailed : ConnectionEffect

    data class EditorHydrationFailed(
        val sessionId: Long,
    ) : ConnectionEffect

    data class Message(
        val text: String,
    ) : ConnectionEffect
}

internal enum class ConnectionEditorExitDecision {
    Blocked,
    ConfirmDiscard,
    Exit,
}

internal data class ConnectionEditorSession(
    val sessionId: Long = 0L,
    val presetId: String? = null,
    val baselineDraft: ConnectionDraft? = null,
    val draft: ConnectionDraft? = null,
    val hydrationPending: Boolean = false,
    val draftRevision: Long = 0L,
    val relayBindingRevision: Long = 0L,
    val savePending: Boolean = false,
    val suppressSaveSuccess: Boolean = false,
) {
    val isDirty: Boolean
        get() = !hydrationPending && baselineDraft != null && draft != baselineDraft

    fun completeHydration(
        expectedSessionId: Long,
        hydratedDraft: ConnectionDraft,
    ): ConnectionEditorSession =
        if (sessionId == expectedSessionId && hydrationPending) {
            copy(
                baselineDraft = hydratedDraft,
                draft = hydratedDraft,
                hydrationPending = false,
            )
        } else {
            this
        }

    fun withEditedDraft(updatedDraft: ConnectionDraft): ConnectionEditorSession {
        val previousDraft = requireNotNull(draft)
        val bindingChanged =
            previousDraft.relayProfileId != updatedDraft.relayProfileId ||
                previousDraft.relayKind != updatedDraft.relayKind
        return copy(
            draft = updatedDraft,
            draftRevision = draftRevision + 1,
            relayBindingRevision = relayBindingRevision + if (bindingChanged) 1 else 0,
        )
    }
}

internal fun MutableStateFlow<ConnectionEditorSession>.updateDraftForSession(
    expectedSessionId: Long,
    transform: ConnectionDraft.() -> ConnectionDraft,
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
        val updated = current.withEditedDraft(requireNotNull(current.draft).applyRelayDraftEdit(transform))
        if (compareAndSet(current, updated)) return true
    }
}

internal class ConnectionEditorHydrationFailureHandler(
    private val editorSession: MutableStateFlow<ConnectionEditorSession>,
) {
    fun abort(expectedSessionId: Long): Boolean {
        val current = editorSession.value
        return current.sessionId == expectedSessionId &&
            current.hydrationPending &&
            editorSession.compareAndSet(current, ConnectionEditorSession())
    }
}

internal const val ConnectionFieldDnsIp = "dnsIp"
internal const val ConnectionFieldProxyIp = "proxyIp"
internal const val ConnectionFieldProxyPort = "proxyPort"
internal const val ConnectionFieldMaxConnections = "maxConnections"
internal const val ConnectionFieldBufferSize = "bufferSize"
internal const val ConnectionFieldDefaultTtl = "defaultTtl"
internal const val ConnectionFieldStrategyChain = "strategyChain"
internal const val ConnectionFieldRelayServerPort = "relayServerPort"
internal const val ConnectionFieldRelayProfileId = "relayProfileId"
internal const val ConnectionFieldRelayLocalSocksPort = "relayLocalSocksPort"
internal const val ConnectionFieldRelayServer = "relayServer"
internal const val ConnectionFieldRelayChain = "relayChain"
internal const val ConnectionFieldRelayCredentials = "relayCredentials"
internal const val ConnectionFieldRelayNaivePath = "relayNaivePath"
internal const val ConnectionFieldRelayNaiveExtraHeaders = "relayNaiveExtraHeaders"
internal const val ConnectionFieldRelayNaiveServerPin = "relayNaiveServerPinSha256"
internal const val ConnectionFieldRelayNaiveConcurrency = "relayNaiveConcurrency"
internal const val ConnectionFieldRelayCloudflarePublishOrigin = "relayCloudflarePublishOrigin"
internal const val ConnectionFieldRelayFinalmask = "relayFinalmask"

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

internal fun AppSettings.toConfigDraft(): ConnectionDraft {
    val dns = activeDnsSettings()
    return ConnectionDraft(
        mode = Mode.fromString(ripdpiMode.ifEmpty { "vpn" }),
        dnsIp = dns.dnsIp,
        dnsMode = dns.mode,
        dnsProviderId = dns.providerId,
        encryptedDnsProtocol = dns.encryptedDnsProtocol,
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
}

internal fun buildConfigPresets(currentDraft: ConnectionDraft): ImmutableList<ConnectionPreset> {
    // Scope stays the user's choice, so applying the recommended settings never changes it.
    val recommendedDraft = AppSettingsSerializer.defaultValue.toConfigDraft().copy(mode = currentDraft.mode)
    val isRecommended = !currentDraft.relayEnabled && currentDraft.directSettings() == recommendedDraft.directSettings()

    return persistentListOf(
        ConnectionPreset(
            id = "recommended",
            kind = ConnectionPresetKind.Recommended,
            draft = recommendedDraft,
            isSelected = isRecommended,
        ),
        ConnectionPreset(
            id = "custom",
            kind = ConnectionPresetKind.Custom,
            draft = currentDraft,
            isSelected = !isRecommended,
        ),
    )
}

// A deactivated relay keeps its profile fields for reactivation, so they do not define a starter profile.
private fun ConnectionDraft.directSettings(): List<Any> =
    listOf(
        dnsIp,
        dnsMode,
        dnsProviderId,
        encryptedDnsProtocol,
        proxyIp,
        proxyPort,
        maxConnections,
        bufferSize,
        useCommandLineSettings,
        commandLineArgs,
        tcpChainSteps,
        udpChainSteps,
        chainDsl,
        desyncMethod,
        defaultTtl,
    )

internal fun sanitizeMasqueAuthModeForCurrentBuild(
    draft: ConnectionDraft,
    supportsMasquePrivacyPass: Boolean,
): ConnectionDraft =
    if (!supportsMasquePrivacyPass && draft.relayMasqueAuthMode == RelayMasqueAuthModePrivacyPass) {
        draft.copy(
            relayMasqueAuthMode = RelayMasqueAuthModeBearer,
        )
    } else {
        draft
    }
