package com.poyka.ripdpi.data.rules

import androidx.room.TypeConverter

/** Room type converters for [RuleEntity] non-primitive fields. */
object RuleTypeConverters {
    private const val PACKAGE_DELIMITER = "\n"

    @TypeConverter
    @JvmStatic
    fun fromPackageSet(packages: Set<String>): String = packages.sorted().joinToString(PACKAGE_DELIMITER)

    @TypeConverter
    @JvmStatic
    fun toPackageSet(value: String): Set<String> =
        if (value.isEmpty()) emptySet() else value.split(PACKAGE_DELIMITER).toSet()

    @TypeConverter
    @JvmStatic
    fun fromRuleNetwork(network: RuleNetwork): String = network.name

    @TypeConverter
    @JvmStatic
    fun toRuleNetwork(value: String): RuleNetwork = RuleNetwork.valueOf(value)

    /**
     * Encodes [OutboundTag] into two Long columns packed as a single String "sentinel:kind".
     *
     * sentinel: 0=PROXY, -1=BYPASS, -2=BLOCK, positive=profile/group id
     * kind:     0=normal (PROXY/BYPASS/BLOCK/PROFILE), 1=GROUP
     */
    @TypeConverter
    @JvmStatic
    fun fromOutboundTag(tag: OutboundTag): String =
        when (tag) {
            is OutboundTag.Proxy -> "$SENTINEL_PROXY:$OUTBOUND_KIND_NORMAL"
            is OutboundTag.Bypass -> "$SENTINEL_BYPASS:$OUTBOUND_KIND_NORMAL"
            is OutboundTag.Block -> "$SENTINEL_BLOCK:$OUTBOUND_KIND_NORMAL"
            is OutboundTag.Profile -> "${tag.profileId}:$OUTBOUND_KIND_NORMAL"
            is OutboundTag.Group -> "${tag.groupId}:$OUTBOUND_KIND_GROUP"
        }

    @TypeConverter
    @JvmStatic
    fun toOutboundTag(value: String): OutboundTag {
        val parts = value.split(":")
        val sentinel = parts[0].toLong()
        val kind = if (parts.size > 1) parts[1].toLong() else OUTBOUND_KIND_NORMAL
        return when {
            kind == OUTBOUND_KIND_GROUP -> OutboundTag.Group(sentinel)
            sentinel == SENTINEL_PROXY -> OutboundTag.Proxy
            sentinel == SENTINEL_BYPASS -> OutboundTag.Bypass
            sentinel == SENTINEL_BLOCK -> OutboundTag.Block
            else -> OutboundTag.Profile(sentinel)
        }
    }
}
