package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.awg.SavedAwgProfile
import com.poyka.ripdpi.ui.screens.proxyimport.profileKindLabel

/** The saved subscription member or AmneziaWG profile that the running session sends traffic through. */
data class ActiveExit(
    val name: String,
    val kind: String,
)

/**
 * Reads the exit from the runtime's confirmed configuration, not from the saved relay flag: a selector member
 * and an AmneziaWG profile replace the relay without turning it on. Other relays keep their own rows.
 */
internal fun RuntimeConfigurationApplication.activeExit(
    groups: List<ProxyGroup>,
    awgProfiles: List<SavedAwgProfile>,
): ActiveExit? {
    val confirmed =
        when (this) {
            is RuntimeConfigurationApplication.Applied -> configuration

            // The previous configuration still carries traffic while a new one is applied.
            is RuntimeConfigurationApplication.Applying -> previous

            else -> null
        } ?: return null
    val effective = confirmed.effectiveSelection
    val profileId = effective.profileId ?: return null
    if (effective.relayKind == AmneziaWgRelayKind) {
        val profile = awgProfiles.firstOrNull { it.id == profileId } ?: return null
        return ActiveExit(profile.name, ConnectionExternalProvider.AmneziaWg.displayName)
    }
    val requested = confirmed.requestedSelection
    val groupId = requested.selectorGroupId?.takeIf { requested.profileId == profileId } ?: return null
    val member =
        groups.firstOrNull { it.id == groupId }?.members?.firstOrNull { it.id == profileId } ?: return null
    return ActiveExit(member.displayName, profileKindLabel(member))
}

private const val AmneziaWgRelayKind = "amneziawg"
