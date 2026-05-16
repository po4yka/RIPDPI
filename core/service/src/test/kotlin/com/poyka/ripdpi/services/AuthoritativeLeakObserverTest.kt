package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD: [AuthoritativeLeakObserver] records resolver source and a coarse 5-minute time bucket.
 * The observation type must not carry user-identifying fields (token, profile id).
 */
class AuthoritativeLeakObserverTest {
    private val observer = AuthoritativeLeakObserver()

    @Test
    fun observationRecordsResolverSource() {
        observer.record(ResolverSource.Proxy, timestampMs = 1_000L)
        val logs = observer.observations()
        assertEquals(1, logs.size)
        assertEquals(ResolverSource.Proxy, logs.first().resolverSource)
    }

    @Test
    fun observationBucketsTimestampToFiveMinuteInterval() {
        // 7 minutes into epoch → bucket should be the 5-minute bucket (300_000 ms)
        val sevenMinutesMs = 7L * 60L * 1_000L
        observer.record(ResolverSource.Direct, timestampMs = sevenMinutesMs)
        val bucket = observer.observations().first().coarseTimeBucketMs
        val expectedBucket = (sevenMinutesMs / (5L * 60L * 1_000L)) * (5L * 60L * 1_000L)
        assertEquals(expectedBucket, bucket)
    }

    @Test
    fun twoTimestampsInSameBucketYieldSameBucketValue() {
        val base = 5L * 60L * 1_000L // exactly 5 min
        observer.record(ResolverSource.Proxy, timestampMs = base)
        observer.record(ResolverSource.Proxy, timestampMs = base + 90_000L) // +1.5 min, still same bucket
        val buckets = observer.observations().map { it.coarseTimeBucketMs }
        assertEquals(buckets[0], buckets[1])
    }

    @Test
    fun observationDoesNotExposeTokenField() {
        // Verify at compile time via absence: AuthoritativeLeakObservation must not have a token property
        val obs =
            AuthoritativeLeakObservation(
                resolverSource = ResolverSource.Proxy,
                coarseTimeBucketMs = 0L,
            )
        // If the class had a `token` field this line would not compile — confirmed by construction above
        val fields = obs.javaClass.declaredFields.map { it.name }
        assertTrue("token field must not exist", "token" !in fields)
    }

    @Test
    fun observationDoesNotExposeProfileIdField() {
        val obs =
            AuthoritativeLeakObservation(
                resolverSource = ResolverSource.Direct,
                coarseTimeBucketMs = 0L,
            )
        val fields = obs.javaClass.declaredFields.map { it.name }
        assertTrue("profileId field must not exist", "profileId" !in fields)
    }

    @Test
    fun multipleObservationsAreAllRetained() {
        observer.record(ResolverSource.Proxy, timestampMs = 1_000L)
        observer.record(ResolverSource.Direct, timestampMs = 2_000L)
        observer.record(ResolverSource.Ipv6, timestampMs = 3_000L)
        assertEquals(3, observer.observations().size)
    }

    @Test
    fun resetClearsAllObservations() {
        observer.record(ResolverSource.Proxy, timestampMs = 1_000L)
        observer.reset()
        assertTrue(observer.observations().isEmpty())
    }
}
