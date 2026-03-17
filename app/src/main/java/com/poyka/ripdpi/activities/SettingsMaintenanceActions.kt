package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
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
import com.poyka.ripdpi.platform.HostAutolearnStoreController
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class SettingsMaintenanceActions(
    private val stringResolver: StringResolver,
    private val hostAutolearnStoreController: HostAutolearnStoreController,
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
                            title = stringResolver.getString(R.string.notice_host_packs_refreshed_title),
                            message = stringResolver.getString(R.string.notice_host_packs_refreshed_message),
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
                            -> {
                                SettingsEffect.Notice(
                                    title =
                                        stringResolver.getString(
                                            R.string.notice_host_pack_verification_failed_title,
                                        ),
                                    message =
                                        stringResolver.getString(
                                            R.string.notice_host_pack_verification_failed_message,
                                        ),
                                    tone = SettingsNoticeTone.Error,
                                )
                            }

                            is HostPackCatalogParseException,
                            is HostPackCatalogBuildException,
                            -> {
                                SettingsEffect.Notice(
                                    title = stringResolver.getString(R.string.notice_host_pack_refresh_failed_title),
                                    message =
                                        stringResolver.getString(
                                            R.string.notice_host_pack_refresh_failed_message,
                                        ),
                                    tone = SettingsNoticeTone.Error,
                                )
                            }

                            else -> {
                                SettingsEffect.Notice(
                                    title = stringResolver.getString(R.string.notice_host_pack_download_failed_title),
                                    message =
                                        stringResolver.getString(
                                            R.string.notice_host_pack_download_failed_message,
                                        ),
                                    tone = SettingsNoticeTone.Warning,
                                )
                            }
                        }
                    },
                )
            emit(effect)
        }
    }

    fun forgetLearnedHosts() {
        mutations.launch {
            val cleared = hostAutolearnStoreController.clearStore()
            hostAutolearnStoreRefresh.update { it + 1 }
            val effect =
                when {
                    !cleared -> {
                        SettingsEffect.Notice(
                            title = stringResolver.getString(R.string.notice_learned_hosts_clear_failed_title),
                            message = stringResolver.getString(R.string.notice_learned_hosts_clear_failed_message),
                            tone = SettingsNoticeTone.Error,
                        )
                    }

                    currentServiceStatus() == AppStatus.Running -> {
                        SettingsEffect.Notice(
                            title = stringResolver.getString(R.string.notice_learned_hosts_cleared_next_start_title),
                            message =
                                stringResolver.getString(
                                    R.string.notice_learned_hosts_cleared_next_start_message,
                                ),
                            tone = SettingsNoticeTone.Info,
                        )
                    }

                    else -> {
                        SettingsEffect.Notice(
                            title = stringResolver.getString(R.string.notice_learned_hosts_cleared_title),
                            message = stringResolver.getString(R.string.notice_learned_hosts_cleared_message),
                            tone = SettingsNoticeTone.Info,
                        )
                    }
                }
            emit(effect)
        }
    }

    fun clearRememberedNetworks() {
        mutations.launch {
            rememberedNetworkPolicyStore.clearAll()
            emit(
                SettingsEffect.Notice(
                    title = stringResolver.getString(R.string.notice_remembered_networks_cleared_title),
                    message = stringResolver.getString(R.string.notice_remembered_networks_cleared_message),
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
                        title = stringResolver.getString(R.string.notice_fake_tls_reset_next_start_title),
                        message = stringResolver.getString(R.string.notice_fake_tls_reset_next_start_message),
                        tone = SettingsNoticeTone.Info,
                    )
                } else {
                    SettingsEffect.Notice(
                        title = stringResolver.getString(R.string.notice_fake_tls_reset_title),
                        message = stringResolver.getString(R.string.notice_fake_tls_reset_message),
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
                        title = stringResolver.getString(R.string.notice_adaptive_ttl_reset_next_start_title),
                        message = stringResolver.getString(R.string.notice_adaptive_ttl_reset_next_start_message),
                        tone = SettingsNoticeTone.Info,
                    )
                } else {
                    SettingsEffect.Notice(
                        title = stringResolver.getString(R.string.notice_adaptive_ttl_reset_title),
                        message = stringResolver.getString(R.string.notice_adaptive_ttl_reset_message),
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
                        title = stringResolver.getString(R.string.notice_fake_payload_reset_next_start_title),
                        message = stringResolver.getString(R.string.notice_fake_payload_reset_next_start_message),
                        tone = SettingsNoticeTone.Info,
                    )
                } else {
                    SettingsEffect.Notice(
                        title = stringResolver.getString(R.string.notice_fake_payload_reset_title),
                        message = stringResolver.getString(R.string.notice_fake_payload_reset_message),
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
                        title = stringResolver.getString(R.string.notice_http_parser_reset_next_start_title),
                        message = stringResolver.getString(R.string.notice_http_parser_reset_next_start_message),
                        tone = SettingsNoticeTone.Info,
                    )
                } else {
                    SettingsEffect.Notice(
                        title = stringResolver.getString(R.string.notice_http_parser_reset_title),
                        message = stringResolver.getString(R.string.notice_http_parser_reset_message),
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
                        title = stringResolver.getString(R.string.notice_activation_window_reset_next_start_title),
                        message = stringResolver.getString(R.string.notice_activation_window_reset_next_start_message),
                        tone = SettingsNoticeTone.Info,
                    )
                } else {
                    SettingsEffect.Notice(
                        title = stringResolver.getString(R.string.notice_activation_window_reset_title),
                        message = stringResolver.getString(R.string.notice_activation_window_reset_message),
                        tone = SettingsNoticeTone.Info,
                    )
                },
            )
        }
    }
}
