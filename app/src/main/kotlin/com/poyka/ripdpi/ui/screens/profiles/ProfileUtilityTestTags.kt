package com.poyka.ripdpi.ui.screens.profiles

import com.poyka.ripdpi.data.ProfileUtilityReference

internal object ProfileUtilityTestTags {
    const val Screen = "profile_utility-screen"
    const val Search = "profile_utility_search"
    const val ProbeUrl = "profile_utility_probe_url"
    const val Fastest = "profile_utility_fastest"
    const val Cleanup = "profile_utility_cleanup"

    fun action(
        reference: ProfileUtilityReference,
        action: String,
    ): String = "profile_utility_${reference.key()}_$action"

    private fun ProfileUtilityReference.key(): String =
        when (this) {
            is ProfileUtilityReference.NativeRelay -> "native:$profileId"
            is ProfileUtilityReference.Xray -> "xray:$profileId"
            is ProfileUtilityReference.SelectorMember -> "selector:$groupId:$memberId"
        }
}
