//! Native enforcement of the strategy-pack "fixed-config protocol" hint.
//!
//! Mirrors the Kotlin `StrategyPackCatalog` semantics (`isFixedConfigProtocol`
//! / `validateCandidateArm`): AmneziaWG profiles are server-coordinated fixed
//! config, so the strategy learner / candidate generator must never emit an
//! arm that mutates their params (`Jc`/`Jmin`/`Jmax`/`S1`-`S4`/`H1`-`H4`/
//! `I1`-`I5`). It may still pick between profiles, so a selection-only arm
//! (empty `mutated_params`) is always valid.

use thiserror::Error;

/// Stable identifier for the AmneziaWG fixed-config protocol.
pub const FIXED_CONFIG_PROTOCOL_AMNEZIAWG: &str = "amneziawg";

/// A single candidate arm the strategy learner / candidate generator proposes.
///
/// `mutated_params` names the profile params this arm would rewrite; an arm
/// that only selects between existing profiles leaves it empty.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct CandidateArm {
    /// Stable arm identifier, surfaced in violation messages.
    pub id: String,
    /// Protocol the arm targets (matched case- and trim-insensitively).
    pub protocol: String,
    /// Profile params this arm would rewrite; empty means selection-only.
    pub mutated_params: Vec<String>,
}

/// Error returned when a candidate arm violates a fixed-config protocol hint.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
#[error("{0}")]
pub struct CandidateArmViolation(String);

impl CandidateArmViolation {
    /// The human-readable reason the arm was rejected.
    pub fn reason(&self) -> &str {
        &self.0
    }
}

/// The set of protocol identifiers whose runtime params are server-coordinated
/// fixed config and must not be varied client-side.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FixedConfigProtocols {
    protocols: Vec<String>,
}

impl Default for FixedConfigProtocols {
    /// Mirrors Kotlin `DefaultStrategyPackFixedConfigProtocols` (`["amneziawg"]`).
    fn default() -> Self {
        Self { protocols: vec![FIXED_CONFIG_PROTOCOL_AMNEZIAWG.to_owned()] }
    }
}

fn normalize(protocol: &str) -> String {
    protocol.trim().to_lowercase()
}

impl FixedConfigProtocols {
    /// Builds a set from arbitrary protocol identifiers (normalized on access).
    pub fn new(protocols: impl IntoIterator<Item = String>) -> Self {
        Self { protocols: protocols.into_iter().collect() }
    }

    /// True when `protocol` is a server-coordinated fixed-config protocol.
    ///
    /// Matching is case-insensitive and trim-tolerant, like Kotlin
    /// `isFixedConfigProtocol`; an empty/blank protocol is never fixed-config.
    pub fn is_fixed_config(&self, protocol: &str) -> bool {
        let normalized = normalize(protocol);
        if normalized.is_empty() {
            return false;
        }
        self.protocols.iter().any(|candidate| normalize(candidate) == normalized)
    }

    /// Validates `arm` against the fixed-config hints.
    ///
    /// Mirrors Kotlin `validateCandidateArm`: a non-fixed-config protocol or a
    /// selection-only arm (empty `mutated_params`) is valid; an arm mutating a
    /// fixed-config protocol's params is rejected with a reason string.
    pub fn validate_candidate_arm(&self, arm: &CandidateArm) -> Result<(), CandidateArmViolation> {
        if !self.is_fixed_config(&arm.protocol) {
            return Ok(());
        }
        if arm.mutated_params.is_empty() {
            return Ok(());
        }
        let normalized_protocol = normalize(&arm.protocol);
        Err(CandidateArmViolation(format!(
            "Candidate arm '{}' may not mutate params {:?} of fixed-config protocol '{}'",
            arm.id, arm.mutated_params, normalized_protocol,
        )))
    }

    /// Returns only the valid arms from `arms`, preserving their order.
    ///
    /// This is the native candidate-generator enforcement: the generator's
    /// output is filtered so no arm mutating a fixed-config protocol escapes.
    pub fn filter_candidate_arms(&self, arms: &[CandidateArm]) -> Vec<CandidateArm> {
        arms.iter().filter(|arm| self.validate_candidate_arm(arm).is_ok()).cloned().collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_protocol_is_never_fixed_config() {
        assert!(!FixedConfigProtocols::default().is_fixed_config("   "));
    }

    #[test]
    fn custom_set_overrides_default() {
        let protocols = FixedConfigProtocols::new(["custom-fixed".to_owned()]);
        assert!(protocols.is_fixed_config("custom-fixed"));
        assert!(!protocols.is_fixed_config("amneziawg"));
    }
}
