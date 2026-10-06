package com.poyka.ripdpi.ui.screens.profiles

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileUtilityCatalog
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.data.ProfileUtilityState
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

internal fun profileUtilityItems(
    catalog: ProfileUtilityCatalog,
    utility: ProfileUtilityState,
    applications: Map<Mode, RuntimeConfigurationApplication>,
    measurements: Map<ProfileUtilityReference, ProfileMeasurementUiState>,
): ImmutableList<ProfileUtilityItem> {
    if (!utility.catalogReady || catalog.generation != utility.catalogGeneration) return persistentListOf()

    val applied =
        applications.values
            .filterIsInstance<RuntimeConfigurationApplication.Applied>()
            .mapNotNull { it.configuration.effectiveSelection.utilityReference() }
            .toSet()
    val recents = utility.recents.associateBy { it.reference }
    return catalog.entries
        .asSequence()
        .filter { it.reference in utility.catalog }
        .map { entry ->
            val recent = recents[entry.reference]
            ProfileUtilityItem(
                reference = entry.reference,
                label = entry.label,
                groupLabel = entry.groupLabel,
                kind = entry.kind,
                favorite = entry.reference in utility.favorites,
                applied = entry.reference in applied,
                lastUsedAtMillis = recent?.appliedAtMillis,
                recentSequence = recent?.sequence,
                measurement = measurements[entry.reference] ?: ProfileMeasurementUiState.NotChecked,
            )
        }.toPersistentList()
}

private fun RuntimeConfigurationSelection.utilityReference(): ProfileUtilityReference? {
    val group = selectorGroupId
    val member = selectorMemberId
    val profile = profileId
    return when {
        !group.isNullOrBlank() && !member.isNullOrBlank() -> ProfileUtilityReference.SelectorMember(group, member)

        profile.isNullOrBlank() -> null

        provider.equals("xray", ignoreCase = true) -> ProfileUtilityReference.Xray(profile)

        provider.equals(
            "native",
            ignoreCase = true,
        ) && relayKind != null -> ProfileUtilityReference.NativeRelay(profile)

        else -> null
    }
}
