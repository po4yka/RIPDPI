package com.poyka.ripdpi.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.poyka.ripdpi.data.rules.RuleEntity

/** Bulk routing access shares the profile backup transaction. */
@Dao
interface ProfileRoutingDao {
    @Query("SELECT * FROM routing_rules ORDER BY userOrder ASC")
    fun all(): List<RuleEntity>

    @Query("DELETE FROM routing_rules")
    fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(rules: List<RuleEntity>)
}
