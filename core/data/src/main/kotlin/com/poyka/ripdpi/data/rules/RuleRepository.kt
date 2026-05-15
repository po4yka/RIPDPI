package com.poyka.ripdpi.data.rules

import kotlinx.coroutines.flow.Flow
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

        suspend fun insert(rule: RuleEntity): Long = dao.insert(rule)

        suspend fun update(rule: RuleEntity) = dao.update(rule)

        suspend fun delete(rule: RuleEntity) = dao.delete(rule)

        /**
         * Re-assigns [RuleEntity.userOrder] so that the rule at position `i` in [orderedIds]
         * receives `userOrder = i`.
         */
        suspend fun reorder(orderedIds: List<Long>) {
            orderedIds.forEachIndexed { index, id ->
                dao.updateOrder(id = id, order = index)
            }
        }

        /**
         * Resets [RuleEntity.outboundTag] to [OutboundTag.Proxy] for every rule that currently
         * routes to the profile identified by [profileId].
         */
        suspend fun resetOutboundTagForProfile(
            profileId: Long,
            rules: List<RuleEntity>,
        ) {
            rules
                .filter { it.outboundTag == OutboundTag.Profile(profileId) }
                .forEach { dao.update(it.copy(outboundTag = OutboundTag.Proxy)) }
        }

        /**
         * Resets [RuleEntity.outboundTag] to [OutboundTag.Proxy] for every rule that currently
         * routes to the group identified by [groupId].
         */
        suspend fun resetOutboundTagForGroup(
            groupId: Long,
            rules: List<RuleEntity>,
        ) {
            rules
                .filter { it.outboundTag == OutboundTag.Group(groupId) }
                .forEach { dao.update(it.copy(outboundTag = OutboundTag.Proxy)) }
        }
    }
