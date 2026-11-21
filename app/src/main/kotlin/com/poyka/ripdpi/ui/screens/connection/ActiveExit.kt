package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileSelections
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.awg.SavedAwgProfile
import com.poyka.ripdpi.data.exitName
import com.poyka.ripdpi.data.kindLabel
import com.poyka.ripdpi.data.runningConfiguration
import com.poyka.ripdpi.data.xray.VpnProviderKind

/** The saved subscription member or AmneziaWG profile that traffic leaves through. */
data class ActiveExit(
    val name: String,
    val kind: String,
)

/** What the next start reads: an AmneziaWG pointer (VPN only) wins over the selector group, as in the runtime. */
internal data class SavedExitSelection(
    val mode: Mode,
    val selections: ProfileSelections,
    val selectorMemberId: String?,
)

/**
 * One precedence for every screen: a running session shows the exit its confirmed configuration uses (none means
 * Direct); with no session running, the saved selection the next start will use. The saved relay flag is not read:
 * a selector member and an AmneziaWG profile replace the relay without turning it on.
 */
internal fun exitOf(
    applications: Map<Mode, RuntimeConfigurationApplication>,
    saved: SavedExitSelection,
    groups: List<ProxyGroup>,
    awgProfiles: List<SavedAwgProfile>,
): ActiveExit? {
    val running = applications.values.firstNotNullOfOrNull { it.runningConfiguration() }
    return if (running != null) running.exit(awgProfiles) else saved.exit(groups, awgProfiles)
}

// A member's label comes from the configuration that carries traffic: a refresh that removes or renames the member
// cannot turn the shown exit into Direct or into a name the runtime has not applied.
private fun AppliedRuntimeConfiguration.exit(awgProfiles: List<SavedAwgProfile>): ActiveExit? {
    val profileId = effectiveSelection.profileId ?: return null
    val member = requestedSelection.takeIf { it.selectorGroupId != null && it.profileId == profileId }
    val kind = member?.selectorMemberKind
    return when {
        effectiveSelection.relayKind == AmneziaWgRelayKind -> awgExit(profileId, awgProfiles)
        member == null || kind == null -> null
        else -> ActiveExit(member.selectorMemberName ?: kind, kind)
    }
}

private fun SavedExitSelection.exit(
    groups: List<ProxyGroup>,
    awgProfiles: List<SavedAwgProfile>,
): ActiveExit? {
    val awgId = selections.awgProfileId?.takeIf { mode == Mode.VPN }
    val groupId = selections.selectorGroupId
    return when {
        selections.xray.kind == VpnProviderKind.Xray -> null
        awgId != null -> awgExit(awgId, awgProfiles)
        groupId != null -> selectorMemberId?.let { memberExit(groupId, it, groups) }
        else -> null
    }
}

private fun awgExit(
    profileId: String,
    awgProfiles: List<SavedAwgProfile>,
): ActiveExit? =
    awgProfiles
        .firstOrNull { it.id == profileId }
        ?.let { ActiveExit(it.name, ConnectionExternalProvider.AmneziaWg.displayName) }

// A member without a saved remark is named by its kind, never by its host or address.
private fun memberExit(
    groupId: String,
    memberId: String,
    groups: List<ProxyGroup>,
): ActiveExit? {
    val member = groups.firstOrNull { it.id == groupId }?.members?.firstOrNull { it.id == memberId } ?: return null
    return ActiveExit(member.exitName(), member.kindLabel())
}

private const val AmneziaWgRelayKind = "amneziawg"
