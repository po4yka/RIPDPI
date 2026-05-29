package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Revalidation-trigger coverage for the direct-mode policy freshness gate:
 * TTL anchored on confirmation (so Phase 6 rotation does not extend it),
 * HTTPS/SVCB TTL expiry, and ASN / ECH environmental invalidation.
 */
class ServerCapabilityDirectPolicyTest {
    private val now = 1_000_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    private fun confirmedRecord(
        confirmedAt: Long = now,
        updatedAt: Long = now,
        observedAsn: Int? = null,
        echCapable: Boolean? = null,
        httpsRrExpiresAt: Long? = null,
        outcome: DirectModeOutcome = DirectModeOutcome.TRANSPARENT_OK,
        cooldownUntil: Long? = null,
        policyFailureCount: Int = 0,
    ): ServerCapabilityRecord =
        ServerCapabilityRecord(
            scope = ServerCapabilityScope.DirectPath.wireValue,
            fingerprintHash = "fp",
            authority = "example.org:443",
            transportPolicyEnvelope =
                TransportPolicyEnvelope(
                    policy = TransportPolicy(outcome = outcome),
                    cooldownUntil = cooldownUntil,
                ),
            policyConfirmedAt = confirmedAt,
            policyFailureCount = policyFailureCount,
            observedAsn = observedAsn,
            echCapable = echCapable,
            httpsRrExpiresAt = httpsRrExpiresAt,
            updatedAt = updatedAt,
        )

    // ---- TTL anchored on confirmation (Phase 6 rotation must not reset it) ----

    @Test
    fun `a freshly confirmed policy is runtime usable`() {
        assertTrue(confirmedRecord(confirmedAt = now - day).isRuntimeUsableDirectPolicy(now))
    }

    @Test
    fun `rotation that bumps updatedAt does not extend the TTL past confirmation`() {
        // Confirmed 8 days ago, "rotated" (updatedAt = now). TTL is 7 days from
        // confirmation, so the entry is stale despite the recent write.
        val rotated = confirmedRecord(confirmedAt = now - 8 * day, updatedAt = now)

        assertFalse(rotated.isFreshDirectPolicy(now))
        assertFalse(rotated.isRuntimeUsableDirectPolicy(now))
    }

    @Test
    fun `unconfirmed record falls back to updatedAt for TTL`() {
        val unconfirmed =
            confirmedRecord(confirmedAt = 0L, updatedAt = now - 8 * day).copy(policyConfirmedAt = null)

        assertFalse(unconfirmed.isFreshDirectPolicy(now))
    }

    // ---- HTTPS / SVCB TTL expiry ----

    @Test
    fun `policy whose https-rr ttl has expired is not fresh`() {
        val expired = confirmedRecord(httpsRrExpiresAt = now - 1)

        assertFalse(expired.isFreshDirectPolicy(now))
    }

    @Test
    fun `policy whose https-rr ttl is still in the future stays fresh`() {
        val live = confirmedRecord(httpsRrExpiresAt = now + day)

        assertTrue(live.isFreshDirectPolicy(now))
    }

    // ---- ASN-change invalidation ----

    @Test
    fun `asn change invalidates the policy`() {
        val record = confirmedRecord(observedAsn = 64_500)
        val environment = DirectPolicyEnvironment(currentAsn = 64_999)

        assertTrue(record.isInvalidatedByEnvironment(environment, now))
        assertFalse(record.isRuntimeUsableDirectPolicy(now, environment))
    }

    @Test
    fun `same asn does not invalidate the policy`() {
        val record = confirmedRecord(observedAsn = 64_500)
        val environment = DirectPolicyEnvironment(currentAsn = 64_500)

        assertFalse(record.isInvalidatedByEnvironment(environment, now))
        assertTrue(record.isRuntimeUsableDirectPolicy(now, environment))
    }

    @Test
    fun `unknown current asn never invalidates`() {
        val record = confirmedRecord(observedAsn = 64_500)

        assertFalse(record.isInvalidatedByEnvironment(DirectPolicyEnvironment(), now))
    }

    // ---- ECH-capability-change invalidation ----

    @Test
    fun `ech capability flip invalidates the policy`() {
        val record = confirmedRecord(echCapable = true)
        val environment = DirectPolicyEnvironment(echCapable = false)

        assertTrue(record.isInvalidatedByEnvironment(environment, now))
    }

    @Test
    fun `unchanged ech capability does not invalidate the policy`() {
        val record = confirmedRecord(echCapable = true)
        val environment = DirectPolicyEnvironment(echCapable = true)

        assertFalse(record.isInvalidatedByEnvironment(environment, now))
    }

    @Test
    fun `empty environment matches the single-argument usability gate`() {
        val record = confirmedRecord(observedAsn = 64_500, echCapable = true)

        assertEquals(
            record.isRuntimeUsableDirectPolicy(now),
            record.isRuntimeUsableDirectPolicy(now, DirectPolicyEnvironment()),
        )
    }

    // ---- Merge carries the new fields ----

    @Test
    fun `merge carries observation environment fields and falls back to existing`() {
        val existing =
            confirmedRecord(observedAsn = 64_500, echCapable = true, httpsRrExpiresAt = now + day)
        val observation =
            ServerCapabilityObservation(
                transportPolicy = TransportPolicy(outcome = DirectModeOutcome.TRANSPARENT_OK),
                observedAsn = 64_999,
            )

        val merged =
            mergeCapabilityRecord(
                existing = existing,
                scope = ServerCapabilityScope.DirectPath,
                fingerprintHash = "fp",
                authority = "example.org:443",
                relayProfileId = null,
                observation = observation,
                source = "runtime",
                recordedAt = now + day,
            )

        assertEquals(64_999, merged.observedAsn)
        // Not present in the new observation -> preserved from the existing record.
        assertEquals(true, merged.echCapable)
        assertEquals(now + day, merged.httpsRrExpiresAt)
    }
}
