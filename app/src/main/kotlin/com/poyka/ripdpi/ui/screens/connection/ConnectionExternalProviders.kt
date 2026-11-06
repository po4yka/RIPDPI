package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProfileSelectionStore
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.services.StandaloneAmneziaWgActivator
import com.poyka.ripdpi.ui.screens.xray.XrayNativeProviderSelection
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** A VPN egress that replaces both Direct and the relay profiles. The name is a product name. */
enum class ConnectionExternalProvider(
    val displayName: String,
) {
    Xray("Xray"),
    AmneziaWg("AmneziaWG"),
}

interface ConnectionExternalProviders {
    suspend fun current(mode: Mode): ConnectionExternalProvider?

    /** Turns off every external egress. Returns true when that also stopped the VPN session. */
    suspend fun deactivate(mode: Mode): Boolean
}

internal class DefaultConnectionExternalProviders
    @Inject
    constructor(
        private val profileSelections: ProfileSelectionStore,
        private val xrayNativeSelection: XrayNativeProviderSelection,
        private val amneziaWg: StandaloneAmneziaWgActivator,
    ) : ConnectionExternalProviders {
        override suspend fun current(mode: Mode): ConnectionExternalProvider? {
            if (mode != Mode.VPN) return null
            val selection = profileSelections.snapshot()
            return when {
                selection.xray.kind == VpnProviderKind.Xray -> ConnectionExternalProvider.Xray
                selection.awgProfileId != null -> ConnectionExternalProvider.AmneziaWg
                else -> null
            }
        }

        override suspend fun deactivate(mode: Mode): Boolean {
            if (current(mode) == ConnectionExternalProvider.Xray) {
                xrayNativeSelection.selectNativeMode(ProfileMutationOrigin.ExplicitActivation, mode)
            }
            // Selecting Xray keeps the AmneziaWG pointer, and the pointer takes over once Xray is off.
            if (current(mode) != ConnectionExternalProvider.AmneziaWg) return false
            amneziaWg.deactivateCurrentSelection()
            return true
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ConnectionExternalProvidersModule {
    @Binds
    abstract fun bindConnectionExternalProviders(
        providers: DefaultConnectionExternalProviders,
    ): ConnectionExternalProviders
}
