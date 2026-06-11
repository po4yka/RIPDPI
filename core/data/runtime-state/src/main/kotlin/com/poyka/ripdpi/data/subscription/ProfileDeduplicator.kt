package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile

/**
 * Collapses [ProxyProfile] entries that are byte-equal except for their
 * [ProxyProfile.displayName].
 *
 * On a subscription refresh the remote provider may re-emit the same node with
 * a tweaked label (`Node`, `Node copy`, ...) and a freshly minted profile id.
 * NekoBox drives this with Kryo binary equality that ignores `name`; RIPDPI's
 * equivalent normalizes the per-import identity fields ([ProxyProfile.id] and
 * [ProxyProfile.displayName]) to a constant and uses structural data-class
 * equality as the dedup key — every other field still participates in
 * identity, so a changed server / port / UUID / groupId keeps the entries
 * distinct.
 *
 * Pure function, no I/O.
 */
object ProfileDeduplicator {
    private const val ID_PLACEHOLDER = ""
    private const val NAME_PLACEHOLDER = ""

    /**
     * Returns [profiles] with byte-equal-modulo-name duplicates collapsed.
     *
     * Group order and first-occurrence position are preserved. Within a group
     * of duplicates the kept entry is the first occurrence, except that a
     * profile whose [ProxyProfile.displayName] is in [userEditedNames] always
     * wins the kept display name — a user-edited label survives the refresh
     * even when it is not the first occurrence.
     *
     * @param profiles incoming profiles from a subscription refresh.
     * @param userEditedNames display names the user has hand-edited; these are
     *     preserved across the merge.
     */
    fun deduplicate(
        profiles: List<ProxyProfile>,
        userEditedNames: Set<String> = emptySet(),
    ): List<ProxyProfile> {
        if (profiles.isEmpty()) return emptyList()

        // Identity key -> index into [result], so the first occurrence keeps its slot.
        val indexByKey = LinkedHashMap<ProxyProfile, Int>()
        val result = ArrayList<ProxyProfile>()

        for (profile in profiles) {
            val key = identityKey(profile)
            val existingIndex = indexByKey[key]
            if (existingIndex == null) {
                indexByKey[key] = result.size
                result.add(profile)
            } else if (profile.displayName in userEditedNames) {
                // A user-edited name always wins, regardless of occurrence order.
                result[existingIndex] = withName(result[existingIndex], profile.displayName)
            }
        }
        return result
    }

    /**
     * Returns a copy of [profile] with the per-import identity fields ([id] and
     * [displayName]) normalized to placeholders. Every [ProxyProfile] subtype
     * is a data class, so structural equality of the returned value ignores
     * only those two fields — two profiles compare equal iff they are
     * byte-equal apart from id and display name.
     */
    private fun identityKey(profile: ProxyProfile): ProxyProfile =
        when (profile) {
            is ProxyProfile.Vless -> profile.copy(id = ID_PLACEHOLDER, displayName = NAME_PLACEHOLDER)
            is ProxyProfile.VlessReality -> profile.copy(id = ID_PLACEHOLDER, displayName = NAME_PLACEHOLDER)
            is ProxyProfile.Shadowsocks -> profile.copy(id = ID_PLACEHOLDER, displayName = NAME_PLACEHOLDER)
            is ProxyProfile.Trojan -> profile.copy(id = ID_PLACEHOLDER, displayName = NAME_PLACEHOLDER)
            is ProxyProfile.Hysteria2 -> profile.copy(id = ID_PLACEHOLDER, displayName = NAME_PLACEHOLDER)
            is ProxyProfile.AnyTls -> profile.copy(id = ID_PLACEHOLDER, displayName = NAME_PLACEHOLDER)
            is ProxyProfile.Mieru -> profile.copy(id = ID_PLACEHOLDER, displayName = NAME_PLACEHOLDER)
            is ProxyProfile.Ssh -> profile.copy(id = ID_PLACEHOLDER, displayName = NAME_PLACEHOLDER)
            is ProxyProfile.RawConfig -> profile.copy(id = ID_PLACEHOLDER, displayName = NAME_PLACEHOLDER)
        }

    /** Returns a copy of [profile] with [displayName] replaced; id is left intact. */
    private fun withName(
        profile: ProxyProfile,
        displayName: String,
    ): ProxyProfile =
        when (profile) {
            is ProxyProfile.Vless -> profile.copy(displayName = displayName)
            is ProxyProfile.VlessReality -> profile.copy(displayName = displayName)
            is ProxyProfile.Shadowsocks -> profile.copy(displayName = displayName)
            is ProxyProfile.Trojan -> profile.copy(displayName = displayName)
            is ProxyProfile.Hysteria2 -> profile.copy(displayName = displayName)
            is ProxyProfile.AnyTls -> profile.copy(displayName = displayName)
            is ProxyProfile.Mieru -> profile.copy(displayName = displayName)
            is ProxyProfile.Ssh -> profile.copy(displayName = displayName)
            is ProxyProfile.RawConfig -> profile.copy(displayName = displayName)
        }
}
