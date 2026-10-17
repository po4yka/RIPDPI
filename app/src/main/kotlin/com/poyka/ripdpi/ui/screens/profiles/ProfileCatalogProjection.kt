package com.poyka.ripdpi.ui.screens.profiles

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.data.ProfileUtilityReference
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Immutable
internal data class ProfileCatalogProjection(
    val visible: ImmutableList<ProfileUtilityItem>,
    val sections: ImmutableList<ProfileCatalogSection>,
)

@Immutable
internal data class ProfileCatalogSection(
    val key: String,
    val titleProfile: ProfileUtilityItem,
    val profiles: ImmutableList<ProfileUtilityItem>,
)

internal fun projectProfileCatalog(
    profiles: ImmutableList<ProfileUtilityItem>,
    query: String,
    filter: String?,
): ProfileCatalogProjection {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    val matching =
        profiles.filter { profile ->
            terms.all { term ->
                profile.label.contains(term, ignoreCase = true) ||
                    profile.groupLabel.orEmpty().contains(term, ignoreCase = true) ||
                    profile.kind.contains(term, ignoreCase = true)
            } &&
                when (filter) {
                    "favorites" -> profile.favorite
                    "recent" -> profile.recentSequence != null
                    else -> true
                }
        }
    val visible =
        (if (filter == "recent") matching.sortedByDescending { it.recentSequence } else matching).toImmutableList()
    val sections =
        if (filter == "recent") {
            emptyList()
        } else {
            visible.groupBy { it.sectionKey() }.map { (key, members) ->
                ProfileCatalogSection(
                    key = key,
                    titleProfile = members.first(),
                    profiles = members.sortedByDescending { it.applied }.toImmutableList(),
                )
            }
        }
    return ProfileCatalogProjection(visible, sections.toImmutableList())
}

private fun ProfileUtilityItem.sectionKey(): String =
    when (val ref = reference) {
        is ProfileUtilityReference.NativeRelay -> "native"
        is ProfileUtilityReference.Xray -> "xray"
        is ProfileUtilityReference.SelectorMember -> "selector:${ref.groupId}"
    }
