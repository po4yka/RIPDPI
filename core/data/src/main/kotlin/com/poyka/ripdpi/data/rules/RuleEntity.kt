package com.poyka.ripdpi.data.rules

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters

/**
 * A user-editable routing rule that maps traffic matchers to an outbound action.
 *
 * Matcher fields ([domains], [ipCidrs], [ports], [sourcePorts], [processName], [packages]) are
 * stored as newline-delimited strings; semantic evaluation lives in the Rust engine.
 *
 * [outboundTag] is serialised via [RuleTypeConverters.fromOutboundTag] / [RuleTypeConverters.toOutboundTag].
 * See [OutboundTag] for the encoding and CASCADE-deletion policy documentation.
 */
@Entity(tableName = "routing_rules")
@TypeConverters(RuleTypeConverters::class)
data class RuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String = "",
    val userOrder: Int = 0,
    val enabled: Boolean = true,
    /** Newline-delimited domain patterns, e.g. "example.com\ngoogle.com". */
    val domains: String = "",
    /** Newline-delimited IP CIDR ranges, e.g. "192.168.0.0/16\n10.0.0.0/8". */
    val ipCidrs: String = "",
    /** Newline-delimited destination port expressions, e.g. "80\n443\n8000-8999". */
    val ports: String = "",
    /** Newline-delimited source port expressions. */
    val sourcePorts: String = "",
    val network: RuleNetwork = RuleNetwork.BOTH,
    val processName: String = "",
    /** Set of Android package names whose traffic this rule matches. */
    val packages: Set<String> = emptySet(),
    val outboundTag: OutboundTag = OutboundTag.Proxy,
)
