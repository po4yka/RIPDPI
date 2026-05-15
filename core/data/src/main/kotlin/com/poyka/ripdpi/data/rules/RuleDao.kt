package com.poyka.ripdpi.data.rules

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    /** Returns all rules ordered by [RuleEntity.userOrder]. Emits on every change. */
    @Query("SELECT * FROM routing_rules ORDER BY userOrder ASC")
    fun allRules(): Flow<List<RuleEntity>>

    /** Returns only enabled rules ordered by [RuleEntity.userOrder]. Emits on every change. */
    @Query("SELECT * FROM routing_rules WHERE enabled = 1 ORDER BY userOrder ASC")
    fun enabledRules(): Flow<List<RuleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: RuleEntity): Long

    @Update
    suspend fun update(rule: RuleEntity)

    @Delete
    suspend fun delete(rule: RuleEntity)

    /** Updates [RuleEntity.userOrder] for a single rule identified by [id]. */
    @Query("UPDATE routing_rules SET userOrder = :order WHERE id = :id")
    suspend fun updateOrder(
        id: Long,
        order: Int,
    )
}
