use crate::failure::FailureClass;
use crate::model::config::{DesyncGroup, FirstResponseSettings, RotationPolicy};
use crate::protocol_payload::TlsRecordBoundaryTracker;
use std::time::{Duration, Instant};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RotationFailureReason {
    ResponseClassified(FailureClass),
    Retransmissions,
    Transport(FailureClass),
}

impl RotationFailureReason {
    fn as_str(self) -> &'static str {
        match self {
            Self::ResponseClassified(FailureClass::Redirect) => "redirect",
            Self::ResponseClassified(FailureClass::TlsAlert) => "tls_alert",
            Self::ResponseClassified(FailureClass::TcpReset) => "tcp_reset",
            Self::ResponseClassified(FailureClass::SilentDrop) => "silent_drop",
            Self::ResponseClassified(FailureClass::HttpBlockpage) => "http_blockpage",
            Self::ResponseClassified(FailureClass::TlsHandshakeFailure) => "tls_handshake_failure",
            Self::ResponseClassified(_) => "classified_failure",
            Self::Retransmissions => "retransmissions",
            Self::Transport(FailureClass::TcpReset) => "tcp_reset",
            Self::Transport(FailureClass::SilentDrop) => "silent_drop",
            Self::Transport(_) => "transport_failure",
        }
    }
}

pub struct RoundObservation {
    pub round: u32,
    pub stream_start: usize,
    pub request_bytes: Vec<u8>,
    pub response_bytes: Vec<u8>,
    pub tls_tracker: TlsRecordBoundaryTracker,
    pub retrans_baseline: Option<u32>,
}

pub struct CircularTcpRotationController {
    base_group: DesyncGroup,
    policy: RotationPolicy,
    active_candidate_index: Option<usize>,
    pending_advance: bool,
    consecutive_failures: usize,
    consecutive_rsts: u32,
    last_failure_at: Option<Instant>,
    observed_round: Option<RoundObservation>,
    desync_suppressed: bool,
}

impl CircularTcpRotationController {
    pub fn new(base_group: DesyncGroup, policy: RotationPolicy) -> Option<Self> {
        (!policy.candidates.is_empty()).then_some(Self {
            base_group,
            policy,
            active_candidate_index: None,
            pending_advance: false,
            consecutive_failures: 0,
            consecutive_rsts: 0,
            last_failure_at: None,
            observed_round: None,
            desync_suppressed: false,
        })
    }

    fn current_group(&self) -> DesyncGroup {
        let mut group = self.base_group.clone();
        if let Some(index) = self.active_candidate_index {
            group.actions.tcp_chain = self.policy.candidates[index].tcp_chain.clone();
        }
        group
    }

    pub fn current_send_group(&self) -> DesyncGroup {
        let mut group = self.current_group();
        if self.desync_suppressed {
            group.actions.tcp_chain.clear();
        }
        group
    }

    pub fn retransmission_failure_matches_observation(&self, stream_start: usize, retrans_delta: u32) -> bool {
        stream_start < self.policy.seq as usize && retrans_delta >= self.policy.retrans
    }

    fn current_family(&self) -> &'static str {
        ripdpi_desync_runtime::primary_tcp_strategy_family(&self.current_group()).unwrap_or("plain")
    }

    fn advance_target_index(&self) -> usize {
        match self.active_candidate_index {
            Some(index) => (index + 1) % self.policy.candidates.len(),
            None => 0,
        }
    }

    fn candidate_family(&self, index: usize) -> &'static str {
        let mut group = self.base_group.clone();
        group.actions.tcp_chain = self.policy.candidates[index].tcp_chain.clone();
        ripdpi_desync_runtime::primary_tcp_strategy_family(&group).unwrap_or("plain")
    }

    pub fn rotate_if_pending(&mut self, host: Option<&str>, target: Option<std::net::SocketAddr>, round: u32) {
        if !self.pending_advance {
            return;
        }
        let next_index = self.advance_target_index();
        let previous = self.current_family();
        let next = self.candidate_family(next_index);
        let wrapped =
            matches!(self.active_candidate_index, Some(current) if current + 1 >= self.policy.candidates.len());
        self.active_candidate_index = Some(next_index);
        self.pending_advance = false;
        self.desync_suppressed = false;
        tracing::info!(
            host = host.unwrap_or(""),
            target = target.map(|value| value.to_string()).unwrap_or_default(),
            from_family = previous,
            to_family = next,
            round,
            wrapped,
            "circular tcp rotation advance"
        );
        if wrapped {
            tracing::info!(
                host = host.unwrap_or(""),
                target = target.map(|value| value.to_string()).unwrap_or_default(),
                from_family = previous,
                to_family = next,
                round,
                "circular tcp rotation wraparound"
            );
        }
    }

    pub fn start_round(
        &mut self,
        first_response: FirstResponseSettings,
        round: u32,
        stream_start: usize,
        request_chunk: &[u8],
        retrans_baseline: Option<u32>,
        host: Option<&str>,
        target: Option<std::net::SocketAddr>,
    ) {
        self.rotate_if_pending(host, target, round);
        if stream_start >= self.policy.seq as usize {
            self.observed_round = None;
            return;
        }
        self.observed_round = Some(RoundObservation {
            round,
            stream_start,
            request_bytes: request_chunk.to_vec(),
            response_bytes: Vec::new(),
            tls_tracker: TlsRecordBoundaryTracker::for_first_response(request_chunk, first_response),
            retrans_baseline,
        });
    }

    pub fn append_request_chunk(&mut self, first_response: FirstResponseSettings, round: u32, request_chunk: &[u8]) {
        let Some(observation) = self.observed_round.as_mut() else {
            return;
        };
        if observation.round != round {
            return;
        }
        observation.request_bytes.extend_from_slice(request_chunk);
        observation.tls_tracker =
            TlsRecordBoundaryTracker::for_first_response(&observation.request_bytes, first_response);
    }

    pub fn observe_response_chunk(&mut self, chunk: &[u8]) -> bool {
        let Some(observation) = self.observed_round.as_mut() else {
            return false;
        };
        observation.response_bytes.extend_from_slice(chunk);
        observation.tls_tracker.observe(chunk);
        !observation.tls_tracker.waiting_for_tls_record()
    }

    pub fn observed_round(&self) -> Option<&RoundObservation> {
        self.observed_round.as_ref()
    }

    pub fn observe_round_success(&mut self) {
        self.consecutive_failures = 0;
        self.consecutive_rsts = 0;
        self.last_failure_at = None;
        self.observed_round = None;
        self.desync_suppressed = false;
    }

    pub fn observe_round_failure(
        &mut self,
        host: Option<&str>,
        target: Option<std::net::SocketAddr>,
        reason: RotationFailureReason,
        retrans_delta: u32,
    ) {
        if self
            .last_failure_at
            .is_some_and(|previous| previous.elapsed() > Duration::from_secs(self.policy.time_secs.max(1)))
        {
            self.consecutive_failures = 0;
            self.consecutive_rsts = 0;
        }
        self.last_failure_at = Some(Instant::now());
        self.consecutive_failures = self.consecutive_failures.saturating_add(1);
        if matches!(
            reason,
            RotationFailureReason::ResponseClassified(FailureClass::TcpReset)
                | RotationFailureReason::Transport(FailureClass::TcpReset)
        ) {
            self.consecutive_rsts = self.consecutive_rsts.saturating_add(1);
        }
        let should_rotate = retrans_delta >= self.policy.retrans
            || self.consecutive_failures >= self.policy.fails
            || self.consecutive_rsts >= self.policy.rst;
        if should_rotate && !self.pending_advance {
            let from_family = self.current_family();
            let to_family = self.candidate_family(self.advance_target_index());
            tracing::info!(
                host = host.unwrap_or(""),
                target = target.map(|value| value.to_string()).unwrap_or_default(),
                from_family,
                to_family,
                reason = reason.as_str(),
                round = self.observed_round.as_ref().map(|value| value.round).unwrap_or_default(),
                retrans_delta,
                fail_count = self.consecutive_failures,
                rst_count = self.consecutive_rsts,
                "circular tcp rotation trigger"
            );
            self.pending_advance = true;
            if self.policy.cancel_on_failure {
                self.desync_suppressed = true;
            }
        }
        self.observed_round = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::config::{
        first_response_settings, OffsetBase, OffsetExpr, RotationCandidate, RuntimeConfig, TcpChainStep,
        TcpChainStepKind,
    };

    fn rotation_controller() -> CircularTcpRotationController {
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain = vec![
            TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::tls_marker(OffsetBase::ExtLen, 0)),
            TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::host(2)),
        ];
        CircularTcpRotationController::new(
            group,
            RotationPolicy {
                candidates: vec![
                    RotationCandidate {
                        tcp_chain: vec![TcpChainStep::new(
                            TcpChainStepKind::HostFake,
                            OffsetExpr::marker(OffsetBase::EndHost, 8),
                        )],
                    },
                    RotationCandidate {
                        tcp_chain: vec![TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::host(1))],
                    },
                ],
                ..RotationPolicy::default()
            },
        )
        .expect("rotation controller")
    }

    #[test]
    fn retransmission_failure_advances_on_next_round() {
        let mut controller = rotation_controller();
        let config = RuntimeConfig::default();

        controller.start_round(
            first_response_settings(&config),
            2,
            0,
            b"GET / HTTP/1.1\r\n",
            Some(1),
            Some("example.org"),
            None,
        );
        controller.observe_round_failure(Some("example.org"), None, RotationFailureReason::Retransmissions, 3);
        assert!(controller.pending_advance);
        assert_eq!(controller.consecutive_failures, 1);

        controller.start_round(
            first_response_settings(&config),
            3,
            128,
            b"GET / HTTP/1.1\r\n",
            Some(4),
            Some("example.org"),
            None,
        );

        assert_eq!(controller.active_candidate_index, Some(0));
        assert!(!controller.pending_advance);
    }

    #[test]
    fn success_clears_failure_window() {
        let mut controller = rotation_controller();
        controller.consecutive_failures = 2;
        controller.consecutive_rsts = 1;
        controller.last_failure_at = Some(Instant::now());

        controller.observe_round_success();

        assert_eq!(controller.consecutive_failures, 0);
        assert_eq!(controller.consecutive_rsts, 0);
        assert!(controller.last_failure_at.is_none());
    }

    #[test]
    fn reset_failure_rotates_on_next_round() {
        let mut controller = rotation_controller();
        let config = RuntimeConfig::default();

        controller.start_round(
            first_response_settings(&config),
            2,
            0,
            b"GET / HTTP/1.1\r\n",
            Some(0),
            Some("example.org"),
            None,
        );
        controller.observe_round_failure(
            Some("example.org"),
            None,
            RotationFailureReason::Transport(FailureClass::TcpReset),
            0,
        );
        assert!(controller.pending_advance);

        controller.start_round(
            first_response_settings(&config),
            3,
            64,
            b"GET / HTTP/1.1\r\n",
            Some(0),
            Some("example.org"),
            None,
        );

        assert_eq!(controller.active_candidate_index, Some(0));
    }

    #[test]
    fn wraps_back_to_first_candidate() {
        let mut controller = rotation_controller();
        controller.active_candidate_index = Some(1);
        controller.pending_advance = true;

        controller.rotate_if_pending(Some("example.org"), None, 4);

        assert_eq!(controller.active_candidate_index, Some(0));
        assert!(!controller.pending_advance);
    }
}
