use std::collections::{HashSet, VecDeque};

use serde::{Deserialize, Serialize};

use crate::{ClassifiedFailure, FailureAction, FailureClass, FailureStage};

pub const CONFIRM_GOOD_EVIDENCE_WINDOW_MS: u64 = 5 * 60 * 1_000;
pub const CONFIRM_GOOD_MAX_OBSERVATIONS: usize = 256;
const REQUIRED_DISTINCT_TARGETS: usize = 2;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ConfirmGoodEvidenceSource {
    Active,
    Passive,
    Mixed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConfirmGoodFlowSource {
    Active,
    Passive,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConfirmGoodTerminalReason {
    ActiveDeadline,
    PassiveTimeout,
    PassiveStall,
    GracefulClose,
    UserCancelled,
    ServiceShutdown,
    IoError,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConfirmGoodFlowObservation {
    pub network_scope: String,
    pub target_digest: String,
    pub observed_at_ms: u64,
    pub source: ConfirmGoodFlowSource,
    pub classic_vless_reality: bool,
    pub profile_catalog_validated: bool,
    pub reality_handshake_completed: bool,
    pub application_bytes_sent: u64,
    pub application_response_bytes: u64,
    pub terminal_reason: ConfirmGoodTerminalReason,
}

impl ConfirmGoodFlowObservation {
    fn is_qualifying_stall(&self) -> bool {
        let terminal_qualifies = matches!(
            self.terminal_reason,
            ConfirmGoodTerminalReason::ActiveDeadline
                | ConfirmGoodTerminalReason::PassiveTimeout
                | ConfirmGoodTerminalReason::PassiveStall
        );
        self.classic_vless_reality
            && self.profile_catalog_validated
            && self.reality_handshake_completed
            && self.application_bytes_sent > 0
            && self.application_response_bytes == 0
            && terminal_qualifies
            && !self.network_scope.is_empty()
            && !self.target_digest.is_empty()
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ConfirmGoodDpiEvidence {
    pub source: ConfirmGoodEvidenceSource,
    pub stalled_flow_count: usize,
    pub distinct_target_count: usize,
    pub catalog_profile_validated: bool,
    pub reality_handshake_confirmed: bool,
    pub application_response_bytes: u64,
    pub quic_control_succeeded: bool,
}

#[derive(Debug, Default)]
pub struct ConfirmGoodDpiAccumulator {
    network_scope: Option<String>,
    observations: VecDeque<ConfirmGoodFlowObservation>,
}

impl ConfirmGoodDpiAccumulator {
    pub fn record(&mut self, observation: ConfirmGoodFlowObservation, now_ms: u64) {
        if self.network_scope.as_deref() != Some(observation.network_scope.as_str()) {
            self.network_scope = Some(observation.network_scope.clone());
            self.observations.clear();
        }
        self.prune(now_ms);
        if observation.is_qualifying_stall() {
            self.observations.push_back(observation);
        }
        while self.observations.len() > CONFIRM_GOOD_MAX_OBSERVATIONS {
            self.observations.pop_front();
        }
    }

    pub fn candidate_evidence(&mut self, now_ms: u64) -> Option<ConfirmGoodDpiEvidence> {
        self.prune(now_ms);
        let distinct_targets = self.observations.iter().map(|item| item.target_digest.as_str()).collect::<HashSet<_>>();
        if distinct_targets.len() < REQUIRED_DISTINCT_TARGETS {
            return None;
        }
        let active = self.observations.iter().any(|item| item.source == ConfirmGoodFlowSource::Active);
        let passive = self.observations.iter().any(|item| item.source == ConfirmGoodFlowSource::Passive);
        let source = match (active, passive) {
            (true, true) => ConfirmGoodEvidenceSource::Mixed,
            (true, false) => ConfirmGoodEvidenceSource::Active,
            (false, true) => ConfirmGoodEvidenceSource::Passive,
            (false, false) => return None,
        };
        Some(ConfirmGoodDpiEvidence {
            source,
            stalled_flow_count: self.observations.len(),
            distinct_target_count: distinct_targets.len(),
            catalog_profile_validated: true,
            reality_handshake_confirmed: true,
            application_response_bytes: 0,
            quic_control_succeeded: false,
        })
    }

    pub fn classify(&mut self, now_ms: u64, quic_control_succeeded: bool) -> Option<ClassifiedFailure> {
        if !quic_control_succeeded {
            return None;
        }
        let mut evidence = self.candidate_evidence(now_ms)?;
        evidence.quic_control_succeeded = true;
        Some(
            ClassifiedFailure::new(
                FailureClass::ConfirmGoodDpiSuspected,
                FailureStage::PostHandshake,
                FailureAction::PivotTransportFamily,
                "Reality handshakes completed without application responses while a same-network QUIC control succeeded",
            )
            .with_tag("stalledFlowCount", evidence.stalled_flow_count.to_string())
            .with_tag("distinctTargetCount", evidence.distinct_target_count.to_string())
            .with_tag("preferredTransportFamily", "udp_quic"),
        )
    }

    fn prune(&mut self, now_ms: u64) {
        let cutoff = now_ms.saturating_sub(CONFIRM_GOOD_EVIDENCE_WINDOW_MS);
        while self.observations.front().is_some_and(|item| item.observed_at_ms < cutoff) {
            self.observations.pop_front();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn observation(scope: &str, target: &str, source: ConfirmGoodFlowSource) -> ConfirmGoodFlowObservation {
        ConfirmGoodFlowObservation {
            network_scope: scope.to_string(),
            target_digest: target.to_string(),
            observed_at_ms: 1_000,
            source,
            classic_vless_reality: true,
            profile_catalog_validated: true,
            reality_handshake_completed: true,
            application_bytes_sent: 64,
            application_response_bytes: 0,
            terminal_reason: ConfirmGoodTerminalReason::PassiveTimeout,
        }
    }

    #[test]
    fn two_distinct_stalls_and_quic_success_classify() {
        let mut accumulator = ConfirmGoodDpiAccumulator::default();
        accumulator.record(observation("wifi-a", "target-a", ConfirmGoodFlowSource::Active), 1_000);
        accumulator.record(observation("wifi-a", "target-b", ConfirmGoodFlowSource::Passive), 1_000);
        let failure = accumulator.classify(1_000, true).expect("two corroborated flows classify");
        assert_eq!(failure.class, FailureClass::ConfirmGoodDpiSuspected);
        assert_eq!(failure.stage, FailureStage::PostHandshake);
        assert_eq!(failure.action, FailureAction::PivotTransportFamily);
    }

    #[test]
    fn duplicate_target_or_missing_quic_does_not_classify() {
        let mut accumulator = ConfirmGoodDpiAccumulator::default();
        accumulator.record(observation("wifi-a", "target-a", ConfirmGoodFlowSource::Passive), 1_000);
        accumulator.record(observation("wifi-a", "target-a", ConfirmGoodFlowSource::Passive), 1_000);
        assert!(accumulator.classify(1_000, true).is_none());
        accumulator.record(observation("wifi-a", "target-b", ConfirmGoodFlowSource::Passive), 1_000);
        assert!(accumulator.classify(1_000, false).is_none());
    }

    #[test]
    fn invalid_flow_shapes_do_not_count() {
        let mut accumulator = ConfirmGoodDpiAccumulator::default();
        let mut cases = vec![
            observation("wifi-a", "profile", ConfirmGoodFlowSource::Passive),
            observation("wifi-a", "handshake", ConfirmGoodFlowSource::Passive),
            observation("wifi-a", "response", ConfirmGoodFlowSource::Passive),
            observation("wifi-a", "cancel", ConfirmGoodFlowSource::Passive),
            observation("wifi-a", "xhttp", ConfirmGoodFlowSource::Passive),
        ];
        cases[0].profile_catalog_validated = false;
        cases[1].reality_handshake_completed = false;
        cases[2].application_response_bytes = 1;
        cases[3].terminal_reason = ConfirmGoodTerminalReason::UserCancelled;
        cases[4].classic_vless_reality = false;
        for item in cases {
            accumulator.record(item, 1_000);
        }
        assert!(accumulator.candidate_evidence(1_000).is_none());
    }

    #[test]
    fn evidence_expires_and_network_scope_changes_reset_it() {
        let mut accumulator = ConfirmGoodDpiAccumulator::default();
        accumulator.record(observation("wifi-a", "target-a", ConfirmGoodFlowSource::Passive), 1_000);
        accumulator.record(observation("wifi-a", "target-b", ConfirmGoodFlowSource::Passive), 1_000);
        assert!(accumulator.candidate_evidence(1_000).is_some());
        assert!(accumulator.candidate_evidence(1_000 + CONFIRM_GOOD_EVIDENCE_WINDOW_MS + 1).is_none());
        accumulator.record(observation("wifi-a", "target-a", ConfirmGoodFlowSource::Passive), 2_000_000);
        accumulator.record(observation("wifi-b", "target-b", ConfirmGoodFlowSource::Passive), 2_000_000);
        assert!(accumulator.candidate_evidence(2_000_000).is_none());
    }

    #[test]
    fn evidence_accumulator_is_bounded() {
        let mut accumulator = ConfirmGoodDpiAccumulator::default();
        for index in 0..(CONFIRM_GOOD_MAX_OBSERVATIONS + 10) {
            accumulator
                .record(observation("wifi-a", &format!("target-{index}"), ConfirmGoodFlowSource::Passive), 1_000);
        }

        let evidence = accumulator.candidate_evidence(1_000).expect("bounded evidence remains actionable");
        assert_eq!(evidence.stalled_flow_count, CONFIRM_GOOD_MAX_OBSERVATIONS);
        assert_eq!(evidence.distinct_target_count, CONFIRM_GOOD_MAX_OBSERVATIONS);
    }
}
