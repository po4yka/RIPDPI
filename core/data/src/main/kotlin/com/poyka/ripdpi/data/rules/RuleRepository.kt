package com.poyka.ripdpi.data.rules

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository over the user's routing [RuleEntity] collection.
 *
 * **CASCADE deletion policy:** when a proxy profile or proxy group that is referenced by a rule's
 * [OutboundTag.Profile] or [OutboundTag.Group] is deleted, callers should invoke
 * [resetOutboundTagForProfile] or [resetOutboundTagForGroup] respectively. The affected rules'
 * [RuleEntity.outboundTag] is reset to [OutboundTag.Proxy] so the rule remains valid and traffic
 * continues to flow rather than being silently dropped. The user can later edit the rule to choose
 * a different outbound. This is simpler than blocking deletion behind a confirmation dialog and
 * never leaves the database in a corrupt state.
 */
@Singleton
class RuleRepository
    @Inject
    constructor(
        private val dao: RuleDao,
    ) {
        /** Returns all rules ordered by [RuleEntity.userOrder]. Emits on every mutation. */
        fun allRules(): Flow<List<RuleEntity>> = dao.allRules()

        /** Returns only enabled rules. Emits on every mutation. */
        fun enabledRules(): Flow<List<RuleEntity>> = dao.enabledRules()

        /**
         * Returns the user-editable rules shown in the full rule editor — every rule except the
         * managed domain-bypass rule (see [DomainBypassList]). Ordered by [RuleEntity.userOrder].
         */
        fun userRules(): Flow<List<RuleEntity>> = dao.rulesExcludingName(DomainBypassList.ManagedBypassRuleName)

        /** Observes the single managed domain-bypass rule, or `null` when the bypass list is empty. */
        fun domainBypassRule(): Flow<RuleEntity?> =
            dao.rulesByName(DomainBypassList.ManagedBypassRuleName).map { it.firstOrNull() }

        /**
         * Upserts the managed domain-bypass rule from raw editor [rawText]. When [rawText] yields no
         * clean entries, any existing managed rule is deleted (an empty list means "no rule"). User
         * rules are never reordered. Returns the [DomainBypassList.CompileResult] so callers can
         * surface per-line validation errors.
         */
        suspend fun saveDomainBypassList(rawText: String): DomainBypassList.CompileResult {
            val result = DomainBypassList.compile(rawText)
            val existing = dao.findByName(DomainBypassList.ManagedBypassRuleName)
            val compiled = DomainBypassList.compileToRule(rawText, existingId = existing?.id ?: 0L)
            when {
                compiled == null && existing != null -> dao.delete(existing)
                compiled == null -> Unit
                existing == null -> dao.insert(compiled)
                else -> dao.update(compiled)
            }
            return result
        }

        /**
         * Promotes the managed domain-bypass rule into a normal, editable user rule by renaming it
         * to [displayName] and appending it to the end of the user-rule order. Returns `false` when
         * there is no bypass rule to move. Other user rules keep their order.
         */
        suspend fun moveDomainBypassListToEditor(displayName: String): Boolean {
            val existing = dao.findByName(DomainBypassList.ManagedBypassRuleName) ?: return false
            val maxOrder = dao.maxUserOrder(DomainBypassList.ManagedBypassRuleName) ?: -1
            dao.update(existing.copy(name = displayName, userOrder = maxOrder + 1))
            return true
        }

        suspend fun insert(rule: RuleEntity): Long = dao.insert(rule)

        suspend fun update(rule: RuleEntity) = dao.update(rule)

        suspend fun delete(rule: RuleEntity) = dao.delete(rule)

        /**
         * Re-assigns [RuleEntity.userOrder] so that the rule at position `i` in [orderedIds]
         * receives `userOrder = i`.
         */
        suspend fun reorder(orderedIds: List<Long>) = dao.updateOrders(orderedIds)

        /**
         * Resets [RuleEntity.outboundTag] to [OutboundTag.Proxy] for every rule that currently
         * routes to the profile identified by [profileId].
         */
        suspend fun resetOutboundTagForProfile(
            profileId: Long,
            rules: List<RuleEntity>,
        ) {
            resetOutboundTags(
                rules = rules,
                targetTag = OutboundTag.Profile(profileId),
            )
        }

        /**
         * Resets [RuleEntity.outboundTag] to [OutboundTag.Proxy] for every rule that currently
         * routes to the group identified by [groupId].
         */
        suspend fun resetOutboundTagForGroup(
            groupId: Long,
            rules: List<RuleEntity>,
        ) {
            resetOutboundTags(
                rules = rules,
                targetTag = OutboundTag.Group(groupId),
            )
        }

        private suspend fun resetOutboundTags(
            rules: List<RuleEntity>,
            targetTag: OutboundTag,
        ) {
            if (rules.isEmpty()) return

            dao.resetOutboundTags(
                ruleIds = rules.map { it.id },
                targetTag = targetTag,
                replacementTag = OutboundTag.Proxy,
            )
        }
    }
