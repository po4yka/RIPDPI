//! Mirror of the Kotlin `TransportPolicy` type family in `core/data/model`.
//!
//! Provides the per-host source-of-truth descriptor used by the direct-mode
//! engine together with a versioned serialization envelope.

use serde::{Deserialize, Serialize};

pub const CURRENT_TRANSPORT_POLICY_ENVELOPE_VERSION: u32 = 1;

/// Controls whether QUIC/UDP is attempted for this host.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum QuicMode {
    #[default]
    Allow,
    SoftDisable,
    HardDisable,
}

/// The preferred HTTP stack tier for this host.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PreferredStack {
    #[default]
    H3,
    H2,
    H1,
}

/// The DNS resolver mode selected for this host.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DnsMode {
    #[default]
    System,
    DohPrimary,
    DohSecondary,
}

/// The TCP desync / fragmentation family selected for this host.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TcpFamily {
    #[default]
    None,
    SegPreSni,
    SegMidSni,
    RecPreSni,
    RecMidSni,
}

/// The high-level policy outcome for this host.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PolicyOutcome {
    #[default]
    TransparentOk,
    OwnedStackOnly,
    NoDirectSolution,
}

/// Per-host transport policy — the direct-mode engine's source of truth.
///
/// Default value represents an unknown host (optimistic / permissive).
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct TransportPolicy {
    pub quic_mode: QuicMode,
    pub preferred_stack: PreferredStack,
    pub dns_mode: DnsMode,
    pub tcp_family: TcpFamily,
    pub outcome: PolicyOutcome,
}

impl TransportPolicy {
    /// Constructs the default "unknown host" policy.
    pub fn unknown_host() -> Self {
        Self::default()
    }
}

/// Versioned wrapper that carries a [`TransportPolicy`] together with its
/// serialization schema version so that future wire-format changes can be
/// detected and migrated at read time.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TransportPolicyEnvelope {
    pub schema_version: u32,
    pub policy: TransportPolicy,
}

impl Default for TransportPolicyEnvelope {
    fn default() -> Self {
        Self { schema_version: CURRENT_TRANSPORT_POLICY_ENVELOPE_VERSION, policy: TransportPolicy::default() }
    }
}

impl TransportPolicyEnvelope {
    pub fn new(policy: TransportPolicy) -> Self {
        Self { schema_version: CURRENT_TRANSPORT_POLICY_ENVELOPE_VERSION, policy }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---- Enum exhaustiveness ---------------------------------------------------

    #[test]
    fn quic_mode_default_is_allow() {
        assert_eq!(QuicMode::default(), QuicMode::Allow);
    }

    #[test]
    fn preferred_stack_default_is_h3() {
        assert_eq!(PreferredStack::default(), PreferredStack::H3);
    }

    #[test]
    fn dns_mode_default_is_system() {
        assert_eq!(DnsMode::default(), DnsMode::System);
    }

    #[test]
    fn tcp_family_default_is_none() {
        assert_eq!(TcpFamily::default(), TcpFamily::None);
    }

    #[test]
    fn policy_outcome_default_is_transparent_ok() {
        assert_eq!(PolicyOutcome::default(), PolicyOutcome::TransparentOk);
    }

    // ---- Default constructor ---------------------------------------------------

    #[test]
    fn unknown_host_policy_has_permissive_defaults() {
        let p = TransportPolicy::unknown_host();
        assert_eq!(p.quic_mode, QuicMode::Allow);
        assert_eq!(p.preferred_stack, PreferredStack::H3);
        assert_eq!(p.dns_mode, DnsMode::System);
        assert_eq!(p.tcp_family, TcpFamily::None);
        assert_eq!(p.outcome, PolicyOutcome::TransparentOk);
    }

    #[test]
    fn envelope_default_has_current_version() {
        let env = TransportPolicyEnvelope::default();
        assert_eq!(env.schema_version, CURRENT_TRANSPORT_POLICY_ENVELOPE_VERSION);
        assert_eq!(env.policy, TransportPolicy::default());
    }

    // ---- Versioned envelope round-trip ----------------------------------------

    #[test]
    fn envelope_round_trips_via_serde_json() {
        let original = TransportPolicyEnvelope::new(TransportPolicy {
            quic_mode: QuicMode::SoftDisable,
            preferred_stack: PreferredStack::H2,
            dns_mode: DnsMode::DohPrimary,
            tcp_family: TcpFamily::SegPreSni,
            outcome: PolicyOutcome::OwnedStackOnly,
        });
        let json = serde_json::to_string(&original).expect("serialize");
        let decoded: TransportPolicyEnvelope = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(original, decoded);
    }

    #[test]
    fn serialized_envelope_contains_schema_version_field() {
        let env = TransportPolicyEnvelope::default();
        let json = serde_json::to_string(&env).expect("serialize");
        assert!(json.contains("schema_version"), "expected 'schema_version' in JSON: {json}",);
    }

    // ---- State transitions ----------------------------------------------------

    #[test]
    fn quic_mode_degrades_allow_soft_hard() {
        let allow = TransportPolicy::unknown_host();
        assert_eq!(allow.quic_mode, QuicMode::Allow);

        let soft = TransportPolicy { quic_mode: QuicMode::SoftDisable, ..allow.clone() };
        assert_eq!(soft.quic_mode, QuicMode::SoftDisable);

        let hard = TransportPolicy { quic_mode: QuicMode::HardDisable, ..soft.clone() };
        assert_eq!(hard.quic_mode, QuicMode::HardDisable);
        assert_ne!(allow, soft);
        assert_ne!(soft, hard);
    }

    #[test]
    fn preferred_stack_downgrades_h3_h2_h1() {
        let h3 = TransportPolicy::unknown_host();
        assert_eq!(h3.preferred_stack, PreferredStack::H3);

        let h2 = TransportPolicy { preferred_stack: PreferredStack::H2, ..h3.clone() };
        assert_eq!(h2.preferred_stack, PreferredStack::H2);

        let h1 = TransportPolicy { preferred_stack: PreferredStack::H1, ..h2.clone() };
        assert_eq!(h1.preferred_stack, PreferredStack::H1);
        assert_ne!(h3, h2);
        assert_ne!(h2, h1);
    }

    #[test]
    fn envelope_new_sets_current_version() {
        let env = TransportPolicyEnvelope::new(TransportPolicy::unknown_host());
        assert_eq!(env.schema_version, CURRENT_TRANSPORT_POLICY_ENVELOPE_VERSION);
    }

    #[test]
    fn all_quic_mode_variants_match_exhaustively() {
        for mode in [QuicMode::Allow, QuicMode::SoftDisable, QuicMode::HardDisable] {
            let label = match mode {
                QuicMode::Allow => "allow",
                QuicMode::SoftDisable => "soft",
                QuicMode::HardDisable => "hard",
            };
            assert!(!label.is_empty());
        }
    }

    #[test]
    fn all_preferred_stack_variants_match_exhaustively() {
        for stack in [PreferredStack::H3, PreferredStack::H2, PreferredStack::H1] {
            let label = match stack {
                PreferredStack::H3 => "h3",
                PreferredStack::H2 => "h2",
                PreferredStack::H1 => "h1",
            };
            assert!(!label.is_empty());
        }
    }

    #[test]
    fn all_policy_outcome_variants_match_exhaustively() {
        for outcome in [PolicyOutcome::TransparentOk, PolicyOutcome::OwnedStackOnly, PolicyOutcome::NoDirectSolution] {
            let label = match outcome {
                PolicyOutcome::TransparentOk => "ok",
                PolicyOutcome::OwnedStackOnly => "owned",
                PolicyOutcome::NoDirectSolution => "none",
            };
            assert!(!label.is_empty());
        }
    }
}
