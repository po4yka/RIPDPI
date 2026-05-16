package com.poyka.ripdpi.services

// ---------------------------------------------------------------------------
// Scenario enum
// ---------------------------------------------------------------------------

/** Scenario tags for a DNS leak-test run. */
internal enum class DnsLeakScenario {
    Proxy,
    Direct,
    Ipv6,
    Captive,
}

// ---------------------------------------------------------------------------
// Test domain generator
// ---------------------------------------------------------------------------

/**
 * Carries a unique per-run domain string and the scenario that produced it.
 *
 * The domain is generated under a non-routable test zone (.leak-test.invalid)
 * so it can never accidentally resolve on the public internet.
 */
internal data class DnsLeakTestDomain(
    val value: String,
    val scenario: DnsLeakScenario,
)

/**
 * Generates unique, per-run DNS query domains for DNS leak testing.
 *
 * Each domain is deterministic for a given (scenario, runSalt) pair, so a test
 * run can reproduce its domain set without state, while different salts produce
 * different subdomains to prevent caching collisions.
 */
internal class DnsLeakTestDomainGenerator {
    private val testZone = ".leak-test.invalid"

    /**
     * Generates a [DnsLeakTestDomain] for [scenario] using [runSalt] to
     * derive a unique subdomain prefix.
     */
    fun generate(
        scenario: DnsLeakScenario,
        runSalt: String,
    ): DnsLeakTestDomain {
        val tag = scenario.name.lowercase()
        val hash = (runSalt + tag).hashCode().toUInt().toString(hexRadix)
        val subdomain = "$hash-$tag$testZone"
        return DnsLeakTestDomain(value = subdomain, scenario = scenario)
    }

    private companion object {
        const val hexRadix = 16
    }
}

// ---------------------------------------------------------------------------
// Resolver source
// ---------------------------------------------------------------------------

/** Which resolver path actually handled the DNS query. */
internal enum class ResolverSource {
    Proxy,
    Direct,
    Ipv6,
    Captive,
}

// ---------------------------------------------------------------------------
// Authoritative observation
// ---------------------------------------------------------------------------

/**
 * A single authoritative-side leak observation.
 *
 * Deliberately minimal: only resolver path and a coarse time bucket are stored.
 * No token, profile id, or device identifier fields are permitted by design.
 */
internal data class AuthoritativeLeakObservation(
    val resolverSource: ResolverSource,
    /** Timestamp floored to the nearest 5-minute boundary, in milliseconds since epoch. */
    val coarseTimeBucketMs: Long,
)

/**
 * Records authoritative-side DNS leak observations, coarsening timestamps to
 * 5-minute buckets to avoid storing user-identifying timing data.
 */
internal class AuthoritativeLeakObserver {
    private val log = mutableListOf<AuthoritativeLeakObservation>()

    private val bucketMs = 5L * 60L * 1_000L

    /** Records a resolver observation at [timestampMs], flooring it to a 5-minute bucket. */
    fun record(
        source: ResolverSource,
        timestampMs: Long,
    ) {
        val bucket = (timestampMs / bucketMs) * bucketMs
        log += AuthoritativeLeakObservation(resolverSource = source, coarseTimeBucketMs = bucket)
    }

    /** Returns all recorded observations in insertion order. */
    fun observations(): List<AuthoritativeLeakObservation> = log.toList()

    /** Clears all recorded observations. */
    fun reset() {
        log.clear()
    }
}

// ---------------------------------------------------------------------------
// Leak report
// ---------------------------------------------------------------------------

/** Verdict from comparing expected vs observed resolver path. */
internal sealed interface DnsLeakVerdict {
    /** Expected and observed resolver paths match — no leak. */
    data object Pass : DnsLeakVerdict

    /** Resolver paths diverged — a DNS leak is confirmed. */
    data class Leak(
        val expected: ResolverSource,
        val observed: ResolverSource,
    ) : DnsLeakVerdict
}

/**
 * Compares the expected resolver path with the observed one and produces a [DnsLeakVerdict].
 */
internal data class DnsLeakReport(
    val expected: ResolverSource,
    val observed: ResolverSource,
) {
    fun verdict(): DnsLeakVerdict =
        if (expected == observed) {
            DnsLeakVerdict.Pass
        } else {
            DnsLeakVerdict.Leak(expected = expected, observed = observed)
        }
}

// ---------------------------------------------------------------------------
// Failure classification
// ---------------------------------------------------------------------------

/**
 * Typed failure classes for the 7 DNS leak failure scenarios.
 */
internal sealed interface DnsLeakFailureClass {
    data object RemoteResolverOutage : DnsLeakFailureClass

    data object BootstrapResolverFailure : DnsLeakFailureClass

    data object ProxyOutboundFailure : DnsLeakFailureClass

    data object AndroidPrivateDnsEnabled : DnsLeakFailureClass

    data object NetworkSwitch : DnsLeakFailureClass

    data object CaptivePortal : DnsLeakFailureClass

    data object CoreCrash : DnsLeakFailureClass
}

/**
 * Symptom bag passed to [DnsLeakFailureClassifier].
 *
 * Every field defaults to false so callers only set the flags that apply.
 */
internal data class DnsLeakSymptoms(
    val remoteResolverUnreachable: Boolean = false,
    val bootstrapFailed: Boolean = false,
    val proxyOutboundFailed: Boolean = false,
    val androidPrivateDnsEnabled: Boolean = false,
    val networkSwitchOccurred: Boolean = false,
    val captivePortalDetected: Boolean = false,
    val coreCrashDetected: Boolean = false,
)

/**
 * Classifies a [DnsLeakSymptoms] bag into one of the seven [DnsLeakFailureClass] variants.
 *
 * Priority order (highest first):
 *   1. RemoteResolverOutage (or no symptoms — fail-closed default)
 *   2. BootstrapResolverFailure
 *   3. ProxyOutboundFailure
 *   4. AndroidPrivateDnsEnabled
 *   5. NetworkSwitch
 *   6. CaptivePortal
 *   7. CoreCrash
 */
internal class DnsLeakFailureClassifier {
    fun classify(symptoms: DnsLeakSymptoms): DnsLeakFailureClass =
        when {
            symptoms.remoteResolverUnreachable || noSymptoms(symptoms) -> {
                DnsLeakFailureClass.RemoteResolverOutage
            }

            symptoms.bootstrapFailed -> {
                DnsLeakFailureClass.BootstrapResolverFailure
            }

            symptoms.proxyOutboundFailed -> {
                DnsLeakFailureClass.ProxyOutboundFailure
            }

            symptoms.androidPrivateDnsEnabled -> {
                DnsLeakFailureClass.AndroidPrivateDnsEnabled
            }

            symptoms.networkSwitchOccurred -> {
                DnsLeakFailureClass.NetworkSwitch
            }

            symptoms.captivePortalDetected -> {
                DnsLeakFailureClass.CaptivePortal
            }

            else -> {
                DnsLeakFailureClass.CoreCrash
            }
        }

    private fun noSymptoms(symptoms: DnsLeakSymptoms): Boolean =
        !symptoms.remoteResolverUnreachable &&
            !symptoms.bootstrapFailed &&
            !symptoms.proxyOutboundFailed &&
            !symptoms.androidPrivateDnsEnabled &&
            !symptoms.networkSwitchOccurred &&
            !symptoms.captivePortalDetected &&
            !symptoms.coreCrashDetected
}
