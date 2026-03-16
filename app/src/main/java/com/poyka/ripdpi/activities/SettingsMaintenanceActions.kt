package com.poyka.ripdpi.activities

import android.content.Context
import com.poyka.ripdpi.core.clearHostAutolearnStore
import com.poyka.ripdpi.data.ActivationFilterModel
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlDelta
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlMax
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlMin
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.FakePayloadProfileCompatDefault
import com.poyka.ripdpi.data.FakeTlsSniModeFixed
import com.poyka.ripdpi.data.HostPackPreset
import com.poyka.ripdpi.data.applyCuratedHostPack
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.data.setGroupActivationFilterCompat
import com.poyka.ripdpi.hosts.HostPackCatalogBuildException
import com.poyka.ripdpi.hosts.HostPackCatalogParseException
import com.poyka.ripdpi.hosts.HostPackCatalogRepository
import com.poyka.ripdpi.hosts.HostPackChecksumFormatException
import com.poyka.ripdpi.hosts.HostPackChecksumMismatchException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class SettingsMaintenanceActions(
    private val appContext: Context,
    private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
    private val hostPackCatalogRepository: HostPackCatalogRepository,
    private val mutations: SettingsMutationRunner,
    private val hostAutolearnStoreRefresh: MutableStateFlow<Int>,
    private val hostPackCatalogState: MutableStateFlow<HostPackCatalogUiState>,
    private val currentUiState: () -> SettingsUiState,
    private val currentServiceStatus: () -> AppStatus,
) {
    fun loadInitialHostPackCatalog() {
        mutations.launch {
            hostPackCatalogState.value =
                HostPackCatalogUiState(
                    snapshot = hostPackCatalogRepository.loadSnapshot(),
                )
        }
    }

    fun resetSettings() {
        mutations.launch {
            replace(
                settings = AppSettingsSerializer.defaultValue,
                effect = SettingsEffect.SettingChanged(key = "settings", value = "reset"),
            )
        }
    }

    fun applyHostPackPreset(
        preset: HostPackPreset,
        targetMode: String,
        applyMode: String,
    ) {
        val currentState = currentUiState()
        val result =
            applyCuratedHostPack(
                currentBlacklist = currentState.hostsBlacklist,
                currentWhitelist = currentState.hostsWhitelist,
                presetHosts = preset.hosts,
                targetMode = targetMode,
                applyMode = applyMode,
            )

        mutations.launch {
            updateDirect {
                setHostsMode(result.hostsMode)
                setHostsBlacklist(result.hostsBlacklist)
                setHostsWhitelist(result.hostsWhitelist)
            }
            emit(SettingsEffect.SettingChanged(key = "hostPackPreset", value = preset.id))
        }
    }

    fun refreshHostPackCatalog() {
        val previousSnapshot = hostPackCatalogState.value.snapshot
        hostPackCatalogState.update { current ->
            current.copy(isRefreshing = true)
        }

        mutations.launch {
            val effect =
                runCatching {
                    hostPackCatalogRepository.refreshSnapshot()
                }.fold(
                    onSuccess = { snapshot ->
                        hostPackCatalogState.value =
                            HostPackCatalogUiState(
                                snapshot = snapshot,
                                isRefreshing = false,
                            )
                        SettingsEffect.Notice(
                            title = "Host packs refreshed",
                            message = "RIPDPI downloaded geosite.dat, verified its SHA-256 checksum, and updated the on-device curated host packs.",
                            tone = SettingsNoticeTone.Info,
                        )
                    },
                    onFailure = { error ->
                        hostPackCatalogState.value =
                            HostPackCatalogUiState(
                                snapshot = previousSnapshot,
                                isRefreshing = false,
                            )
                        when (error) {
                            is HostPackChecksumMismatchException,
                            is HostPackChecksumFormatException,
                                ->
                                SettingsEffect.Notice(
                                    title = "Host pack verification failed",
                                    message = "The downloaded geosite.dat checksum did not match the published SHA-256 digest. RIPDPI kept the current curated host packs.",
                                    tone = SettingsNoticeTone.Error,
                                )

                            is HostPackCatalogParseException,
                            is HostPackCatalogBuildException,
                                ->
                                SettingsEffect.Notice(
                                    title = "Host pack refresh failed",
                                    message = "RIPDPI verified the download, but the remote geosite.dat did not produce a complete YouTube, Telegram, and Discord catalog. The current packs remain in use.",
                                    tone = SettingsNoticeTone.Error,
                                )

                            else ->
                                SettingsEffect.Notice(
                                    title = "Couldn’t refresh host packs",
                                    message = "RIPDPI could not download the latest geosite.dat or checksum file. The current curated host packs remain in use.",
                                    tone = SettingsNoticeTone.Warning,
                                )
                        }
                    },
                )
            emit(effect)
        }
    }

    fun forgetLearnedHosts() {
        mutations.launch {
            val cleared = clearHostAutolearnStore(appContext)
            hostAutolearnStoreRefresh.update { it + 1 }
            val effect =
                when {
                    !cleared ->
                        SettingsEffect.Notice(
                            title = "Couldn’t clear learned hosts",
                            message = "RIPDPI could not delete the stored host learning file on this device.",
                            tone = SettingsNoticeTone.Error,
                        )

                    currentServiceStatus() == AppStatus.Running ->
                        SettingsEffect.Notice(
                            title = "Learned hosts cleared for next start",
                            message = "The stored host learning file was removed. The running proxy keeps its in-memory routes until RIPDPI restarts.",
                            tone = SettingsNoticeTone.Info,
                        )

                    else ->
                        SettingsEffect.Notice(
                            title = "Learned hosts cleared",
                            message = "Stored host learning was removed and the next RIPDPI start will rebuild it from scratch.",
                            tone = SettingsNoticeTone.Info,
                        )
                }
            emit(effect)
        }
    }

    fun clearRememberedNetworks() {
        mutations.launch {
            rememberedNetworkPolicyStore.clearAll()
            emit(
                SettingsEffect.Notice(
                    title = "Remembered networks cleared",
                    message = "RIPDPI removed all stored per-network bypass policies. Current settings remain unchanged.",
                    tone = SettingsNoticeTone.Info,
                ),
            )
        }
    }

    fun resetFakeTlsProfile() {
        mutations.launch {
            updateDirect {
                setFakeTlsUseOriginal(false)
                setFakeTlsRandomize(false)
                setFakeTlsDupSessionId(false)
                setFakeTlsPadEncap(false)
                setFakeTlsSize(0)
                setFakeTlsSniMode(FakeTlsSniModeFixed)
                setFakeSni(DefaultFakeSni)
            }
            emit(
                if (currentServiceStatus() == AppStatus.Running) {
                    SettingsEffect.Notice(
                        title = "Fake TLS profile reset for next start",
                        message = "The profile is back to the default fake ClientHello. Restart RIPDPI to apply it to the active session.",
                        tone = SettingsNoticeTone.Info,
                    )
                } else {
                    SettingsEffect.Notice(
                        title = "Fake TLS profile reset",
                        message = "RIPDPI will use the default fake ClientHello, fixed SNI, and input-sized payload on the next start.",
                        tone = SettingsNoticeTone.Info,
                    )
                },
            )
        }
    }

    fun resetAdaptiveFakeTtlProfile() {
        mutations.launch {
            val currentState = currentUiState()
            updateDirect {
                setAdaptiveFakeTtlEnabled(false)
                setAdaptiveFakeTtlDelta(DefaultAdaptiveFakeTtlDelta)
                setAdaptiveFakeTtlMin(DefaultAdaptiveFakeTtlMin)
                setAdaptiveFakeTtlMax(DefaultAdaptiveFakeTtlMax)
                setAdaptiveFakeTtlFallback(
                    currentState.fakeTtl.takeIf { it > 0 } ?: DefaultAdaptiveFakeTtlFallback,
                )
            }
            emit(
                if (currentServiceStatus() == AppStatus.Running) {
                    SettingsEffect.Notice(
                        title = "Adaptive fake TTL reset for next start",
                        message = "RIPDPI will go back to a fixed fake TTL for the active group after the next restart or reconnect.",
                        tone = SettingsNoticeTone.Info,
                    )
                } else {
                    SettingsEffect.Notice(
                        title = "Adaptive fake TTL reset",
                        message = "RIPDPI will stop learning fake TTL values and use the fixed fake TTL on the next start.",
                        tone = SettingsNoticeTone.Info,
                    )
                },
            )
        }
    }

    fun resetFakePayloadLibrary() {
        mutations.launch {
            updateDirect {
                setHttpFakeProfile(FakePayloadProfileCompatDefault)
                setTlsFakeProfile(FakePayloadProfileCompatDefault)
                setUdpFakeProfile(FakePayloadProfileCompatDefault)
            }
            emit(
                if (currentServiceStatus() == AppStatus.Running) {
                    SettingsEffect.Notice(
                        title = "Fake payload presets reset for next start",
                        message = "HTTP, TLS, and UDP fake payloads are back to compatibility defaults. Restart RIPDPI to apply them to the active session.",
                        tone = SettingsNoticeTone.Info,
                    )
                } else {
                    SettingsEffect.Notice(
                        title = "Fake payload presets reset",
                        message = "RIPDPI will use the compatibility fake payload defaults for HTTP, TLS, and UDP on the next start.",
                        tone = SettingsNoticeTone.Info,
                    )
                },
            )
        }
    }

    fun resetHttpParserEvasions() {
        mutations.launch {
            updateDirect {
                setHostMixedCase(false)
                setDomainMixedCase(false)
                setHostRemoveSpaces(false)
                setHttpMethodEol(false)
                setHttpUnixEol(false)
            }
            emit(
                if (currentServiceStatus() == AppStatus.Running) {
                    SettingsEffect.Notice(
                        title = "HTTP parser evasions reset for next start",
                        message = "HTTP parser tweaks are back to their defaults. Restart RIPDPI to remove the saved parser evasions from the active session.",
                        tone = SettingsNoticeTone.Info,
                    )
                } else {
                    SettingsEffect.Notice(
                        title = "HTTP parser evasions reset",
                        message = "RIPDPI will stop applying saved HTTP parser tweaks and EOL evasions on the next start.",
                        tone = SettingsNoticeTone.Info,
                    )
                },
            )
        }
    }

    fun resetActivationWindow() {
        mutations.launch {
            updateDirect {
                setGroupActivationFilterCompat(ActivationFilterModel())
            }
            emit(
                if (currentServiceStatus() == AppStatus.Running) {
                    SettingsEffect.Notice(
                        title = "Activation window reset for next start",
                        message = "The active desync group is back to always-on gating. Restart RIPDPI to remove the saved activation window from the running session.",
                        tone = SettingsNoticeTone.Info,
                    )
                } else {
                    SettingsEffect.Notice(
                        title = "Activation window reset",
                        message = "RIPDPI will stop gating the active desync group by round, payload size, or stream-byte position on the next start.",
                        tone = SettingsNoticeTone.Info,
                    )
                },
            )
        }
    }
}
