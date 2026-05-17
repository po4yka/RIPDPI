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
