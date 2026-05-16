package com.poyka.ripdpi.services.redaction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseLogPolicyTest {
    @Test
    fun `all sensitive tags are suppressed in release builds`() {
        SensitiveTag.entries.forEach { tag ->
            assertFalse(
                "tag $tag must be suppressed in release",
                ReleaseLogPolicy.shouldEmit(tag, BuildType.Release),
            )
        }
    }

    @Test
    fun `all sensitive tags are emitted in debug builds`() {
        SensitiveTag.entries.forEach { tag ->
            assertTrue(
                "tag $tag must be emitted in debug",
                ReleaseLogPolicy.shouldEmit(tag, BuildType.Debug),
            )
        }
    }

    @Test
    fun `uuid tag is suppressed in release`() {
        assertFalse(ReleaseLogPolicy.shouldEmit(SensitiveTag.Uuid, BuildType.Release))
    }

    @Test
    fun `password tag is suppressed in release`() {
        assertFalse(ReleaseLogPolicy.shouldEmit(SensitiveTag.Password, BuildType.Release))
    }

    @Test
    fun `subscription token tag is suppressed in release`() {
        assertFalse(ReleaseLogPolicy.shouldEmit(SensitiveTag.SubscriptionToken, BuildType.Release))
    }

    @Test
    fun `endpoint tag is suppressed in release`() {
        assertFalse(ReleaseLogPolicy.shouldEmit(SensitiveTag.Endpoint, BuildType.Release))
    }

    @Test
    fun `short id tag is suppressed in release`() {
        assertFalse(ReleaseLogPolicy.shouldEmit(SensitiveTag.ShortId, BuildType.Release))
    }
}
