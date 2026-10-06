package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.relayConfigOrNull
import com.poyka.ripdpi.core.warpConfigOrNull
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.xray.DurableXrayProfileStore
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayConfigRenderer
import com.poyka.ripdpi.data.xray.XrayProviderBuildInfo
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** Secret-bearing temporary canonical material never leaves service ownership or the HMAC input. */
internal class RuntimeConfigurationCatalogMaterial(
    val selection: RuntimeConfigurationSelection,
    val material: List<String>,
    val warpMaterial: List<String>?,
    val warpReference: com.poyka.ripdpi.service.warp.RequestedWarpRuntimeReference?,
) {
    override fun toString(): String = "RuntimeConfigurationCatalogMaterial([REDACTED])"
}

@Singleton
internal class RuntimeConfigurationCatalogCapture
    @Inject
    constructor(
        private val mutations: ProfileMutationCoordinator,
        private val relayProfiles: RelayProfileStore,
        private val relayCredentials: RelayCredentialStore,
        private val warpCredentials: WarpCredentialStore,
        private val xrayProfiles: DurableXrayProfileStore,
        private val xraySelection: XrayProviderSelectionStore,
        private val selectorProfiles: SelectorRelayRuntimeProfileResolver,
    ) {
        suspend fun capture(
            mode: Mode,
            settings: AppSettings,
            preferences: RipDpiProxyPreferences,
            awg: AwgActivationRequest?,
        ): RuntimeConfigurationCatalogMaterial =
            mutations.readRecovered {
                val selection = xraySelection.current()
                if (mode == Mode.VPN && selection.kind == VpnProviderKind.Xray) {
                    captureXray(selection.activeProfileId)
                } else {
                    captureNative(settings, preferences, awg)
                }
            }

        private suspend fun captureXray(profileId: String): RuntimeConfigurationCatalogMaterial {
            val profile = xrayProfiles.load(profileId)
            val rendered = profile?.let { XrayConfigRenderer().render(it, XrayProviderBuildInfo.upstreamTag) }
            val config = (rendered as? XrayConfigRenderer.Result.Success)?.config
            return RuntimeConfigurationCatalogMaterial(
                RuntimeConfigurationSelection(
                    provider = "xray",
                    transport =
                        profile
                            ?.outbound
                            ?.network
                            ?.name
                            ?.lowercase(),
                    profileId = profileId,
                ),
                listOf(
                    config?.let { RipDpiEncodeDefaultsJson.encodeToString(JsonObject.serializer(), it) }
                        ?: "unavailable",
                ),
                null,
                null,
            )
        }

        private suspend fun captureNative(
            settings: AppSettings,
            preferences: RipDpiProxyPreferences,
            awg: AwgActivationRequest?,
        ): RuntimeConfigurationCatalogMaterial {
            val selected = if (awg == null) selectorProfiles.resolve() else null
            if (selected != null) {
                return RuntimeConfigurationCatalogMaterial(
                    RuntimeConfigurationSelection(
                        "native",
                        selected.profile.vlessTransport,
                        selected.profile.kind,
                        selected.memberId,
                        selected.groupId,
                        selected.memberId,
                    ),
                    listOf(
                        RipDpiEncodeDefaultsJson.encodeToString(RelayProfileRecord.serializer(), selected.profile),
                        RipDpiEncodeDefaultsJson.encodeToString(
                            RelayCredentialRecord.serializer(),
                            selected.credentials,
                        ),
                    ),
                    null,
                    null,
                )
            }
            val relay = preferences.relayConfigOrNull()
            val warp = preferences.warpConfigOrNull()
            val material = captureRelayMaterial(relay?.profileId)
            val warpMaterial =
                if (settings.warpEnabled) {
                    warpCredentials
                        .load(
                            settings.warpProfileId,
                        )?.let(::requestedWarpCredentialMaterial)
                } else {
                    null
                }
            return RuntimeConfigurationCatalogMaterial(
                RuntimeConfigurationSelection(
                    provider = "native",
                    relayKind = awg?.let { "amneziawg" } ?: relay?.kind ?: warp?.let { "warp" },
                    transport = awg?.carrier ?: relay?.vlessTransport ?: warp?.let { "wireguard" },
                    profileId = awg?.profileId ?: relay?.profileId ?: warp?.let { settings.warpProfileId },
                ),
                material,
                warpMaterial,
                warpMaterial?.let {
                    com.poyka.ripdpi.service.warp.RequestedWarpRuntimeReference(
                        settings.warpProfileId,
                        mutations.warpRuntimeRevision(settings.warpProfileId),
                    )
                },
            )
        }

        private suspend fun captureRelayMaterial(profileId: String?): List<String> {
            val material = mutableListOf<String>()
            val pending = ArrayDeque<String>()
            profileId?.let(pending::add)
            val seen = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val id = pending.removeFirst()
                if (id.isBlank() || !seen.add(id)) continue
                val profile = relayProfiles.load(id)
                val credential = relayCredentials.load(id)
                material += profile?.let {
                    RipDpiEncodeDefaultsJson.encodeToString(
                        RelayProfileRecord.serializer(),
                        it.copy(presetId = "", jurisdiction = "", operatorName = ""),
                    )
                } ?: "missing-profile:$id"
                material += credential?.let {
                    RipDpiEncodeDefaultsJson.encodeToString(RelayCredentialRecord.serializer(), it)
                } ?: "missing-credentials:$id"
                profile?.let {
                    pending.addAll(
                        listOf(it.chainEntryProfileId, it.chainExitProfileId, it.shadowTlsInnerProfileId),
                    )
                    pending.addAll(it.chainMiddleProfileIds)
                }
            }
            return material
        }
    }
