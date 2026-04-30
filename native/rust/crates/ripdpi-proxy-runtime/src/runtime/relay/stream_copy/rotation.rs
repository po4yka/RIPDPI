use ripdpi_config::{DesyncGroup, RotationPolicy};
use ripdpi_failure_classifier::FailureClass;
use std::time::{Duration, Instant};

use super::super::super::desync::primary_tcp_strategy_family;
use super::super::tls_boundary::TlsRecordBoundaryTracker;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum RotationFailureReason {
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

pub(super) struct RoundObservation {
    pub(super) round: u32,
    pub(super) stream_start: usize,
    pub(super) request_bytes: Vec<u8>,
    pub(super) response_bytes: Vec<u8>,
    pub(super) tls_tracker: TlsRecordBoundaryTracker,
    pub(super) retrans_baseline: Option<u32>,
}

pub(super) struct CircularTcpRotationController {
    pub(super) base_group: DesyncGroup,
    pub(super) policy: RotationPolicy,
    pub(super) active_candidate_index: Option<usize>,
    pub(super) pending_advance: bool,
    pub(super) consecutive_failures: usize,
    pub(super) consecutive_rsts: u32,
    pub(super) last_failure_at: Option<Instant>,
    pub(super) observed_round: Option<RoundObservation>,
    /// When true, desync is suppressed for the remainder of this connection
    /// until rotation completes. Set on failure detection when
    /// `cancel_on_failure` is enabled.
    pub(super) desync_suppressed: bool,
}

impl CircularTcpRotationController {
    pub(super) fn new(base_group: DesyncGroup, policy: RotationPolicy) -> Option<Self> {
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

    pub(super) fn is_desync_suppressed(&self) -> bool {
        self.desync_suppressed
    }

    pub(super) fn current_group(&self) -> DesyncGroup {
        let mut group = self.base_group.clone();
        if let Some(index) = self.active_candidate_index {
            group.actions.tcp_chain = self.policy.candidates[index].tcp_chain.clone();
        }
        group
    }

    pub(super) fn current_family(&self) -> &'static str {
        primary_tcp_strategy_family(&self.current_group()).unwrap_or("plain")
    }

    pub(super) fn advance_target_index(&self) -> usize {
        match self.active_candidate_index {
            Some(index) => (index + 1) % self.policy.candidates.len(),
            None => 0,
        }
    }

    pub(super) fn candidate_family(&self, index: usize) -> &'static str {
        let mut group = self.base_group.clone();
        group.actions.tcp_chain = self.policy.candidates[index].tcp_chain.clone();
        primary_tcp_strategy_family(&group).unwrap_or("plain")
    }

    pub(super) fn rotate_if_pending(&mut self, host: Option<&str>, target: Option<std::net::SocketAddr>, round: u32) {
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

    pub(super) fn start_round(
        &mut self,
        config: &ripdpi_config::RuntimeConfig,
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
            tls_tracker: TlsRecordBoundaryTracker::for_first_response(request_chunk, config),
            retrans_baseline,
        });
    }

    pub(super) fn append_request_chunk(
        &mut self,
        config: &ripdpi_config::RuntimeConfig,
        round: u32,
        request_chunk: &[u8],
    ) {
        let Some(observation) = self.observed_round.as_mut() else {
            return;
        };
        if observation.round != round {
            return;
        }
        observation.request_bytes.extend_from_slice(request_chunk);
        observation.tls_tracker = TlsRecordBoundaryTracker::for_first_response(&observation.request_bytes, config);
    }

    pub(super) fn observe_response_chunk(&mut self, chunk: &[u8]) -> bool {
        let Some(observation) = self.observed_round.as_mut() else {
            return false;
        };
        observation.response_bytes.extend_from_slice(chunk);
        observation.tls_tracker.observe(chunk);
        !observation.tls_tracker.waiting_for_tls_record()
    }

    pub(super) fn observed_round(&self) -> Option<&RoundObservation> {
        self.observed_round.as_ref()
    }

    pub(super) fn observe_round_success(&mut self) {
        self.consecutive_failures = 0;
        self.consecutive_rsts = 0;
        self.last_failure_at = None;
        self.observed_round = None;
        self.desync_suppressed = false;
    }

    pub(super) fn observe_round_failure(
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
