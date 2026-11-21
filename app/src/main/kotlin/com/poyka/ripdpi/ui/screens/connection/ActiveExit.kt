package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileSelections
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.awg.SavedAwgProfile
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.ui.screens.proxyimport.profileKindLabel

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
    return if (running != null) running.exit(groups, awgProfiles) else saved.exit(groups, awgProfiles)
}

private fun RuntimeConfigurationApplication.runningConfiguration(): AppliedRuntimeConfiguration? =
    when (this) {
        is RuntimeConfigurationApplication.Applied -> {
            configuration
        }

        // The previous configuration carries traffic only while the same runtime applies a new one; a new session
        // starting after a stop must not show the last session's exit.
        is RuntimeConfigurationApplication.Applying -> {
            previous?.takeIf { it.runtimeId == attempt.runtimeId }
        }

        else -> {
            null
        }
    }

private fun AppliedRuntimeConfiguration.exit(
    groups: List<ProxyGroup>,
    awgProfiles: List<SavedAwgProfile>,
): ActiveExit? =
    effectiveSelection.profileId?.let { profileId ->
        if (effectiveSelection.relayKind == AmneziaWgRelayKind) {
            awgExit(profileId, awgProfiles)
        } else {
            requestedSelection.selectorGroupId
                ?.takeIf { requestedSelection.profileId == profileId }
                ?.let { memberExit(it, profileId, groups) }
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
    val kind = profileKindLabel(member)
    val host = member.host()
    val remark = member.displayName.takeUnless { it.isBlank() || (host != null && host in it) }
    return ActiveExit(remark ?: kind, kind)
}

private fun ProxyProfile.host(): String? =
    when (this) {
        is ProxyProfile.Vless -> server
        is ProxyProfile.VlessReality -> server
        is ProxyProfile.Shadowsocks -> server
        is ProxyProfile.Trojan -> server
        is ProxyProfile.Tuic -> server
        is ProxyProfile.Hysteria2 -> server
        is ProxyProfile.AnyTls -> server
        is ProxyProfile.Snell -> server
        is ProxyProfile.Mieru -> server
        is ProxyProfile.Naive -> server
        is ProxyProfile.Ssh -> server
        is ProxyProfile.RawConfig -> null
    }.takeUnless { it.isNullOrBlank() }

private const val AmneziaWgRelayKind = "amneziawg"
