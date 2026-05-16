package com.poyka.ripdpi.diagnostics.orchestrator

import com.poyka.ripdpi.diagnostics.shared.DnsClassification

/**
 * Classifies the DNS environment for a target authority.
 *
 * Delegates to the Encrypted-DNS and HTTPS-SVCB classifier epic.
 * The orchestrator calls this in Phase 1 and must NOT contain any DNS
 * detection logic itself.
 */
interface DnsClassifier {
    /** Returns the DNS classification for the current network context. */
    suspend fun classify(): DnsClassification
}

/**
 * Classifies the transport-layer blocking pattern.
 *
 * Delegates to the Direct-mode transport-policy and verdicts epic.
 * The orchestrator calls this in Phase 2 and must NOT contain any TLS or
 * QUIC detection logic itself.
 */
interface TransportPolicyClassifier {
    /** Returns the transport class observed on the current path. */
    suspend fun classify(): TransportClass
}

/**
 * Neutral transport-class signal produced by the transport-policy classifier.
 *
 * Maps 1:1 to [DiagnosticClass] but lives here so neither the classifier
 * nor the orchestrator depends on the other's internal representation.
 */
enum class TransportClass {
    SniTlsSuspect,
    QuicBlockSuspect,
    IpBlockSuspect,
    Transparent,
    Unknown,
}

/**
 * Re-ranks the candidate arm list using the privacy-preserving posterior
 * learner.
 *
 * Delegates to the Privacy-preserving strategy-learner epic.
 * The orchestrator must NOT contain any scoring or Bayesian logic itself.
 */
interface ArmRanker {
    /**
     * Returns [arms] reordered by posterior success probability, highest
     * first.  Arms not present in the learner's prior are kept in their
     * original relative order at the tail of the result.
     */
    fun rank(arms: List<CandidateArm>): List<CandidateArm>
}

/**
 * Executes a single candidate arm and reports whether it achieved a stable
 * success.
 *
 * Delegates to the Semantic-TLS-first-flight-family engine (TLS arms) or
 * the Owned-stack epic (owned-stack arms).  The orchestrator must NOT
 * contain arm execution logic itself.
 */
interface ArmExecutor {
    /**
     * Attempts [arm] and returns a result.
     *
     * [probeBytesSoFar] is provided so the executor can honour the caller's
     * byte budget, but enforcement remains the orchestrator's responsibility.
     */
    suspend fun execute(
        arm: CandidateArm,
        probeBytesSoFar: Long,
    ): ArmExecutionResult
}

/** Result returned by a single arm execution attempt. */
data class ArmExecutionResult(
    /** True when the arm produced a stable, usable direct-mode path. */
    val stableSuccess: Boolean,
    /** Bytes consumed by this arm attempt. */
    val probeBytes: Long,
    /**
     * True when this arm requires owned-stack pinning and the user has not
     * yet confirmed the pin.  The orchestrator applies confirm_once gating
     * when this flag is set.
     */
    val requiresOwnedStackPin: Boolean = false,
)

/**
 * Guards the owned-stack pinning path with confirm_once semantics.
 *
 * Delegates to the Owned-stack epic.  The orchestrator must NOT contain
 * pinning or confirmation logic itself.
 */
interface OwnedStackPinGuard {
    /**
     * Returns true when a prior confirmation exists (corroborating evidence
     * or a matching prior), allowing the pin to proceed without prompting.
     * Returns false when a fresh user confirmation is still required.
     */
    fun isConfirmed(): Boolean

    /** Records that the user has confirmed the owned-stack pin. */
    fun confirm()
}
