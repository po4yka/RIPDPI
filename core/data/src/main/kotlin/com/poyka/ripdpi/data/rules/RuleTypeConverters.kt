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
            is OutboundTag.Proxy -> "$SentinelProxy:$OutboundKindNormal"
            is OutboundTag.Bypass -> "$SentinelBypass:$OutboundKindNormal"
            is OutboundTag.Block -> "$SentinelBlock:$OutboundKindNormal"
            is OutboundTag.Profile -> "${tag.profileId}:$OutboundKindNormal"
            is OutboundTag.Group -> "${tag.groupId}:$OutboundKindGroup"
        }

    @TypeConverter
    @JvmStatic
    fun toOutboundTag(value: String): OutboundTag {
        val parts = value.split(":")
        val sentinel = parts[0].toLong()
        val kind = if (parts.size > 1) parts[1].toLong() else OutboundKindNormal
        return when {
            kind == OutboundKindGroup -> OutboundTag.Group(sentinel)
            sentinel == SentinelProxy -> OutboundTag.Proxy
            sentinel == SentinelBypass -> OutboundTag.Bypass
            sentinel == SentinelBlock -> OutboundTag.Block
            else -> OutboundTag.Profile(sentinel)
        }
    }
}
