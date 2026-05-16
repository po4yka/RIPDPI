//! Pure-function classifier for the `IP_BLOCK_SUSPECT` verdict.
//!
//! Fires when:
//! - all DoH-provided IPv4 SYNs failed,
//! - all alternate-family (IPv6) SYNs failed,
//! - no CDN variant succeeded within the attempt budget, and
//! - the false-positive guard (second-flow confirmation) has already passed.
//!
//! On `IpBlockSuspect` the caller must set `arm_gate = OwnedStackOnly` and
//! skip all TLS-family arms, jumping straight to owned-stack arms (A10/A9).

use ripdpi_failure_classifier::{ArmGate, IpBlockSuspectVerdict, IpBlockVerdict};

/// Inputs to the IP-block-suspect classifier.
///
/// All counts are SYN-layer only (connect-phase failures before any
/// handshake byte is exchanged). A SYN that succeeded but whose TLS
/// handshake later failed does **not** count as a SYN failure.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct IpBlockSuspectInput {
    /// Number of IPv4 addresses tried at SYN where every attempt failed.
    pub v4_syn_failures: u32,
    /// Total number of IPv4 addresses attempted at SYN.
    pub v4_syn_attempts: u32,
    /// Number of IPv6 addresses tried at SYN where every attempt failed.
    pub v6_syn_failures: u32,
    /// Total number of IPv6 addresses attempted at SYN.
    pub v6_syn_attempts: u32,
    /// Number of CDN variant probes that succeeded (any IP family).
    pub cdn_successes: u32,
    /// Total CDN variant probes attempted (used to enforce the attempt budget).
    pub cdn_attempts: u32,
    /// Maximum CDN attempts allowed before we accept the "no CDN success"
    /// conclusion. If `cdn_attempts < attempt_budget` the budget has not been
    /// exhausted and we cannot conclude a CDN block.
    pub attempt_budget: u32,
    /// Whether the false-positive guard (second-flow confirmation) has already
    /// passed for this host. When `false`, we return `PendingSecondFlow`
    /// rather than committing to `IpBlockSuspect`.
    pub second_flow_guard_passed: bool,
}

/// Classify a host as `IP_BLOCK_SUSPECT` when all SYN-layer conditions are met.
///
/// # Decision table
///
/// | v4 all-fail | v6 all-fail | cdn_successes | budget met | guard | result              |
/// |-------------|-------------|---------------|------------|-------|---------------------|
/// | yes         | yes         | 0             | yes        | yes   | IpBlockSuspect      |
/// | yes         | yes         | 0             | yes        | no    | PendingSecondFlow   |
/// | no          | *           | *             | *          | *     | NotIpBlock          |
/// | *           | no          | *             | *          | *     | NotIpBlock          |
/// | *           | *           | >0            | *          | *     | NotIpBlock          |
/// | *           | *           | *             | no         | *     | NotIpBlock          |
pub fn classify_ip_block_suspect(input: IpBlockSuspectInput) -> IpBlockSuspectVerdict {
    let all_v4_failed = input.v4_syn_attempts > 0 && input.v4_syn_failures >= input.v4_syn_attempts;
    let all_v6_failed = input.v6_syn_attempts > 0 && input.v6_syn_failures >= input.v6_syn_attempts;
    let no_cdn_success = input.cdn_successes == 0;
    let budget_met = input.cdn_attempts >= input.attempt_budget;

    if all_v4_failed && all_v6_failed && no_cdn_success && budget_met {
        if input.second_flow_guard_passed {
            return IpBlockSuspectVerdict {
                verdict: IpBlockVerdict::IpBlockSuspect,
                arm_gate: ArmGate::OwnedStackOnly,
            };
        } else {
            return IpBlockSuspectVerdict {
                verdict: IpBlockVerdict::PendingSecondFlow,
                arm_gate: ArmGate::Unrestricted,
            };
        }
    }

    IpBlockSuspectVerdict { verdict: IpBlockVerdict::NotIpBlock, arm_gate: ArmGate::Unrestricted }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn base_input() -> IpBlockSuspectInput {
        IpBlockSuspectInput {
            v4_syn_failures: 3,
            v4_syn_attempts: 3,
            v6_syn_failures: 2,
            v6_syn_attempts: 2,
            cdn_successes: 0,
            cdn_attempts: 4,
            attempt_budget: 4,
            second_flow_guard_passed: true,
        }
    }

    // Test 1: all conditions met → IpBlockSuspect with OwnedStackOnly gate
    #[test]
    fn all_syn_fail_no_cdn_guard_passed_returns_ip_block_suspect() {
        let result = classify_ip_block_suspect(base_input());
        assert_eq!(result.verdict, IpBlockVerdict::IpBlockSuspect);
        assert_eq!(result.arm_gate, ArmGate::OwnedStackOnly);
    }

    // Test 2: same conditions but guard NOT passed → PendingSecondFlow
    #[test]
    fn all_syn_fail_no_cdn_guard_not_passed_returns_pending() {
        let input = IpBlockSuspectInput { second_flow_guard_passed: false, ..base_input() };
        let result = classify_ip_block_suspect(input);
        assert_eq!(result.verdict, IpBlockVerdict::PendingSecondFlow);
        assert_eq!(result.arm_gate, ArmGate::Unrestricted);
    }

    // Test 3: at least one CDN success → NotIpBlock
    #[test]
    fn cdn_success_prevents_ip_block_suspect() {
        let input = IpBlockSuspectInput { cdn_successes: 1, ..base_input() };
        let result = classify_ip_block_suspect(input);
        assert_eq!(result.verdict, IpBlockVerdict::NotIpBlock);
        assert_eq!(result.arm_gate, ArmGate::Unrestricted);
    }

    // Test 4: v4 SYN succeeded (failed later at TLS) → not a SYN failure → NotIpBlock
    #[test]
    fn v4_syn_succeeded_handshake_failed_is_not_syn_failure() {
        // v4_syn_failures < v4_syn_attempts means at least one SYN got through
        let input = IpBlockSuspectInput {
            v4_syn_failures: 2,
            v4_syn_attempts: 3, // one SYN succeeded even if TLS later failed
            ..base_input()
        };
        let result = classify_ip_block_suspect(input);
        assert_eq!(result.verdict, IpBlockVerdict::NotIpBlock);
    }

    // Test 5: attempt budget not exhausted with mixed signals → NotIpBlock
    #[test]
    fn budget_not_met_with_mixed_signals_defers_to_existing_rules() {
        let input = IpBlockSuspectInput {
            cdn_attempts: 2,   // fewer attempts than budget
            attempt_budget: 4, // budget not met
            cdn_successes: 0,
            ..base_input()
        };
        let result = classify_ip_block_suspect(input);
        // Budget exhaustion check failed — cannot conclude IP block
        assert_eq!(result.verdict, IpBlockVerdict::NotIpBlock);
    }

    // Additional: only v4 fails (no v6 attempted) → NotIpBlock (need both families)
    #[test]
    fn only_v4_fails_no_v6_attempts_is_not_ip_block() {
        let input = IpBlockSuspectInput {
            v6_syn_failures: 0,
            v6_syn_attempts: 0, // v6 not attempted — can't conclude both families blocked
            ..base_input()
        };
        let result = classify_ip_block_suspect(input);
        assert_eq!(result.verdict, IpBlockVerdict::NotIpBlock);
    }
}
