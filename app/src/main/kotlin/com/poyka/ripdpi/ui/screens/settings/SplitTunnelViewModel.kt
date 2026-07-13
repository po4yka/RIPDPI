package com.poyka.ripdpi.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.services.SplitTunnelMode
import com.poyka.ripdpi.ui.screens.routes.InstalledAppCatalog
import com.poyka.ripdpi.ui.screens.routes.InstalledAppItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Render state for the per-app split-tunnel picker. [fullTunnelMode] short-circuits the surface:
 * when on, the split-tunnel settings are ignored by the VPN builder, so the screen surfaces a
 * notice instead of editable controls.
 */
data class SplitTunnelUiState(
    val mode: String = SplitTunnelMode.Off,
    val selectedPackages: ImmutableSet<String> = persistentSetOf(),
    val fullTunnelMode: Boolean = false,
)

/**
 * Backs the "Split tunneling" screen. Persistence is the settings store (`split_tunnel_mode` /
 * `split_tunnel_packages`), NOT the `routing_rules` Room table, so editing split tunnel never
 * reorders the user's routing rules. Installed apps are resolved by [InstalledAppCatalog] (which
 * owns the `PackageManager`); the selection set is never logged or exported.
 */
@HiltViewModel
class SplitTunnelViewModel
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val installedAppCatalog: InstalledAppCatalog,
    ) : ViewModel() {
        val uiState: StateFlow<SplitTunnelUiState> =
            appSettingsRepository.settings
                .map { settings ->
                    SplitTunnelUiState(
                        mode = settings.splitTunnelMode.ifBlank { SplitTunnelMode.Off },
                        selectedPackages = settings.splitTunnelPackagesList.toImmutableSet(),
                        fullTunnelMode = settings.fullTunnelMode,
                    )
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = SplitTunnelUiState(),
                )

        private val installedAppsState = MutableStateFlow<List<InstalledAppItem>>(emptyList())
        val installedApps: StateFlow<List<InstalledAppItem>> = installedAppsState

        init {
            viewModelScope.launch {
                installedAppsState.value = installedAppCatalog.installedApps()
            }
        }

        /** Persists the active split-tunnel [mode] ("off" | "include" | "exclude"). */
        fun setMode(mode: String) {
            viewModelScope.launch {
                appSettingsRepository.update { splitTunnelMode = mode }
            }
        }

        /** Replaces the selected package set for the active mode. */
        fun setSelectedPackages(packages: Set<String>) {
            viewModelScope.launch {
                appSettingsRepository.update {
                    clearSplitTunnelPackages()
                    addAllSplitTunnelPackages(packages)
                }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
