package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.backup.BackupRestoreDecoder
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RuleTypeConverters
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the backup [OutboundTag] contract to the durable, Room-shared
 * `"sentinel:kind"` encoding ([RuleTypeConverters]) rather than the fragile Kotlin
 * data-class `toString()` form. Every variant must survive an export→restore round
 * trip, and an unparseable value must degrade to [OutboundTag.Proxy] (never throw).
 */
class BackupOutboundTagRoundTripTest {
    private val allVariants =
        listOf(
            OutboundTag.Proxy,
            OutboundTag.Bypass,
            OutboundTag.Block,
            OutboundTag.Profile(7),
            OutboundTag.Group(42),
        )

    @Test
    fun everyOutboundTagVariantRoundTripsThroughTheDurableEncoding() {
        allVariants.forEach { tag ->
            // The exporter records RuleTypeConverters.fromOutboundTag(tag); the decoder
            // parses it back. They must be exact inverses for every variant.
            val encoded = RuleTypeConverters.fromOutboundTag(tag)
            assertEquals(tag, BackupRestoreDecoder.parseOutboundTag(encoded))
        }
    }

    @Test
    fun legacyToStringFormNoLongerDictatesTheContract() {
        // The durable encoding is the packed "sentinel:kind" form, not "Profile(profileId=7)".
        assertEquals("7:0", RuleTypeConverters.fromOutboundTag(OutboundTag.Profile(7)))
        assertEquals("42:1", RuleTypeConverters.fromOutboundTag(OutboundTag.Group(42)))
    }

    @Test
    fun unparseableOutboundTagDegradesToProxy() {
        listOf("", "garbage", "Profile(profileId=7)", "not:a:number").forEach { malformed ->
            assertEquals(OutboundTag.Proxy, BackupRestoreDecoder.parseOutboundTag(malformed))
        }
    }
}
