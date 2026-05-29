package com.poyka.ripdpi.data.rules

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RuleDao {
    /** Returns all rules ordered by [RuleEntity.userOrder]. Emits on every change. */
    @Query("SELECT * FROM routing_rules ORDER BY userOrder ASC")
    abstract fun allRules(): Flow<List<RuleEntity>>

    /** Returns only enabled rules ordered by [RuleEntity.userOrder]. Emits on every change. */
    @Query("SELECT * FROM routing_rules WHERE enabled = 1 ORDER BY userOrder ASC")
    abstract fun enabledRules(): Flow<List<RuleEntity>>

    /** Returns rules whose name is not [excludedName] (used to hide the managed bypass rule). */
    @Query("SELECT * FROM routing_rules WHERE name <> :excludedName ORDER BY userOrder ASC")
    abstract fun rulesExcludingName(excludedName: String): Flow<List<RuleEntity>>

    /** Returns rules whose name equals [name] (used to observe the single managed bypass rule). */
    @Query("SELECT * FROM routing_rules WHERE name = :name ORDER BY userOrder ASC")
    abstract fun rulesByName(name: String): Flow<List<RuleEntity>>

    /** Returns the first rule whose name equals [name], or `null`. */
    @Query("SELECT * FROM routing_rules WHERE name = :name LIMIT 1")
    abstract suspend fun findByName(name: String): RuleEntity?

    /** Highest [RuleEntity.userOrder] among rules whose name is not [excludedName], or `null`. */
    @Query("SELECT MAX(userOrder) FROM routing_rules WHERE name <> :excludedName")
    abstract suspend fun maxUserOrder(excludedName: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(rule: RuleEntity): Long

    @Update
    abstract suspend fun update(rule: RuleEntity)

    @Delete
    abstract suspend fun delete(rule: RuleEntity)

    /** Updates all supplied rule orders in one Room transaction. */
    @Transaction
    open suspend fun updateOrders(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id ->
            updateOrder(id = id, order = index)
        }
    }

    /** Resets [RuleEntity.outboundTag] for matching scoped rules in one SQL update. */
    @Query(
        """
        UPDATE routing_rules
        SET outboundTag = :replacementTag
        WHERE id IN (:ruleIds) AND outboundTag = :targetTag
        """,
    )
    abstract suspend fun resetOutboundTags(
        ruleIds: List<Long>,
        targetTag: OutboundTag,
        replacementTag: OutboundTag,
    ): Int

    /** Updates [RuleEntity.userOrder] for a single rule identified by [id]. */
    @Query("UPDATE routing_rules SET userOrder = :order WHERE id = :id")
    abstract suspend fun updateOrder(
        id: Long,
        order: Int,
    )
}
