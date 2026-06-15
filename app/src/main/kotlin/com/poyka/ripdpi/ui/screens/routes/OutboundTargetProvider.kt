package com.poyka.ripdpi.ui.screens.routes

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** A selectable outbound action shown in the rule editor, paired with a human-readable [label]. */
data class OutboundTarget(
    val tag: OutboundTag,
    val label: String,
)

/**
 * Builds the list of outbound targets the rule editor can route to: the three built-ins
 * ([OutboundTag.Proxy] / [OutboundTag.Bypass] / [OutboundTag.Block]) plus every user-defined proxy
 * group and relay profile.
 *
 * ## Interim String→Long id mapping (documented contract decision)
 *
 * [OutboundTag.Profile] and [OutboundTag.Group] carry `Long` ids, but [ProxyGroup] and
 * [RelayProfileRecord] are keyed by `String`. There is no persisted String↔Long mapping table in
 * the shipped schema, so this catalog derives a **stable, deterministic** `Long` from the `String`
 * id via [stableId]: a 64-bit FNV-1a hash, masked to a positive non-zero value. The mask guarantees
 * the result never collides with the reserved sentinels (`0` = Proxy, `-1` = Bypass, `-2` = Block —
 * see [OutboundTag]).
 *
 * To DISPLAY a selected target's name we cannot reverse the hash, so [labelFor] re-hashes the live
 * group/profile lists and matches by id. When no live entity matches (the referenced group/profile
 * was deleted) it falls back to a generic `"Profile #<id>"` / `"Group #<id>"` label.
 *
 * This whole hashing scheme is **interim**, pending the rules→native bridge task that will give
 * routing rules a real, persisted id space. Do not rely on the hash being stable across that change.
 */
@Singleton
class OutboundTargetCatalog
    @Inject
    constructor(
        private val proxyGroupRepository: ProxyGroupRepository,
        private val relayProfileStore: RelayProfileStore,
        private val stringResolver: StringResolver,
    ) {
        /** Returns built-in actions first, then groups, then profiles, each with a display label. */
        suspend fun targets(): List<OutboundTarget> {
            val groups = proxyGroupRepository.groups().first()
            val profiles = relayProfileStore.list()
            return buildList {
                add(OutboundTarget(OutboundTag.Proxy, builtInProxyLabel()))
                add(OutboundTarget(OutboundTag.Bypass, builtInBypassLabel()))
                add(OutboundTarget(OutboundTag.Block, builtInBlockLabel()))
                groups.forEach { group ->
                    add(OutboundTarget(OutboundTag.Group(stableId(group.id)), group.name))
                }
                profiles.forEach { profile ->
                    add(OutboundTarget(OutboundTag.Profile(stableId(profile.id)), profileLabel(profile)))
                }
            }
        }

        /**
         * Resolves a display label for an arbitrary [tag] against the live [groups] / [profiles]
         * lists. Built-ins return their fixed label; a profile/group id that no longer resolves
         * falls back to the generic `"Profile #<id>"` / `"Group #<id>"` form.
         */
        fun labelFor(
            tag: OutboundTag,
            groups: List<ProxyGroup>,
            profiles: List<RelayProfileRecord>,
        ): String =
            when (tag) {
                OutboundTag.Proxy -> {
                    builtInProxyLabel()
                }

                OutboundTag.Bypass -> {
                    builtInBypassLabel()
                }

                OutboundTag.Block -> {
                    builtInBlockLabel()
                }

                is OutboundTag.Group -> {
                    groups
                        .firstOrNull { stableId(it.id) == tag.groupId }
                        ?.name
                        ?: stringResolver.getString(R.string.outbound_group_fallback, tag.groupId)
                }

                is OutboundTag.Profile -> {
                    profiles
                        .firstOrNull { stableId(it.id) == tag.profileId }
                        ?.let(::profileLabel)
                        ?: stringResolver.getString(R.string.outbound_profile_fallback, tag.profileId)
                }
            }

        private fun builtInProxyLabel(): String = stringResolver.getString(R.string.rule_editor_outbound_proxy)

        private fun builtInBypassLabel(): String = stringResolver.getString(R.string.rule_editor_outbound_bypass)

        private fun builtInBlockLabel(): String = stringResolver.getString(R.string.rule_editor_outbound_block)

        private fun profileLabel(profile: RelayProfileRecord): String =
            profile.operatorName.ifBlank { profile.server }.ifBlank { profile.id }

        /**
         * Maps a `String` group/profile id to a stable, positive, non-zero `Long` via 64-bit FNV-1a.
         * The final mask `(hash and Long.MAX_VALUE) or 1L` clears the sign bit (positivity) and sets
         * the low bit, guaranteeing the value can never equal the `0` / `-1` / `-2` sentinels.
         *
         * Interim — see the class KDoc for the rules→native bridge follow-up.
         */
        fun stableId(stringId: String): Long {
            var hash = FnvOffsetBasis
            for (byte in stringId.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (byte.toLong() and ByteMask)
                hash *= FnvPrime
            }
            return (hash and Long.MAX_VALUE) or 1L
        }

        private companion object {
            // 64-bit FNV-1a constants (offset basis interpreted as a signed Long via toLong()).
            const val FnvOffsetBasis: Long = -3750763034362895579L // 0xCBF29CE484222325
            const val FnvPrime: Long = 0x100000001B3

            // Low-byte mask for folding each UTF-8 byte into the running hash.
            const val ByteMask: Long = 0xFF
        }
    }
