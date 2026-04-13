use crate::platform;
use crate::runtime_policy::extract_host;
use crate::sync::{Arc, Mutex};
use ripdpi_config::{DesyncGroup, RotationPolicy};
use ripdpi_failure_classifier::{classify_transport_error, FailureClass, FailureStage};
use ripdpi_session::SessionState;
use std::io::{self, Read, Write};
use std::net::{Shutdown, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::{Duration, Instant};

use super::super::desync::{primary_tcp_strategy_family, send_with_group, OutboundSendError};
use super::super::routing::{classify_response_failure, note_block_signal_for_failure};
use super::super::state::RuntimeState;
use super::tls_boundary::TlsRecordBoundaryTracker;

const RELAY_IDLE_TIMEOUT: Duration = Duration::from_secs(60);

pub(super) const CONNECTION_FREEZE_MARKER: &str = "connection freeze detected";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum RotationFailureReason {
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

struct RoundObservation {
    round: u32,
    stream_start: usize,
    request_bytes: Vec<u8>,
    response_bytes: Vec<u8>,
    tls_tracker: TlsRecordBoundaryTracker,
    retrans_baseline: Option<u32>,
}

struct CircularTcpRotationController {
    base_group: DesyncGroup,
    policy: RotationPolicy,
    active_candidate_index: Option<usize>,
    pending_advance: bool,
    consecutive_failures: usize,
    consecutive_rsts: u32,
    last_failure_at: Option<Instant>,
    observed_round: Option<RoundObservation>,
}

impl CircularTcpRotationController {
    fn new(base_group: DesyncGroup, policy: RotationPolicy) -> Option<Self> {
        (!policy.candidates.is_empty()).then_some(Self {
            base_group,
            policy,
            active_candidate_index: None,
            pending_advance: false,
            consecutive_failures: 0,
            consecutive_rsts: 0,
            last_failure_at: None,
            observed_round: None,
        })
    }

    fn current_group(&self) -> DesyncGroup {
        let mut group = self.base_group.clone();
        if let Some(index) = self.active_candidate_index {
            group.actions.tcp_chain = self.policy.candidates[index].tcp_chain.clone();
        }
        group
    }

    fn current_family(&self) -> &'static str {
        primary_tcp_strategy_family(&self.current_group()).unwrap_or("plain")
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
        primary_tcp_strategy_family(&group).unwrap_or("plain")
    }

    fn rotate_if_pending(&mut self, host: Option<&str>, target: Option<std::net::SocketAddr>, round: u32) {
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

    fn start_round(
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

    fn append_request_chunk(&mut self, config: &ripdpi_config::RuntimeConfig, round: u32, request_chunk: &[u8]) {
        let Some(observation) = self.observed_round.as_mut() else {
            return;
        };
        if observation.round != round {
            return;
        }
        observation.request_bytes.extend_from_slice(request_chunk);
        observation.tls_tracker = TlsRecordBoundaryTracker::for_first_response(&observation.request_bytes, config);
    }

    fn observe_response_chunk(&mut self, chunk: &[u8]) -> bool {
        let Some(observation) = self.observed_round.as_mut() else {
            return false;
        };
        observation.response_bytes.extend_from_slice(chunk);
        observation.tls_tracker.observe(chunk);
        !observation.tls_tracker.waiting_for_tls_record()
    }

    fn observed_round(&self) -> Option<&RoundObservation> {
        self.observed_round.as_ref()
    }

    fn observe_round_success(&mut self) {
        self.consecutive_failures = 0;
        self.consecutive_rsts = 0;
        self.last_failure_at = None;
        self.observed_round = None;
    }

    fn observe_round_failure(
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
        }
        self.observed_round = None;
    }
}

fn group_rotation_controller(
    state: &RuntimeState,
    group_index: usize,
    session_seed: &SessionState,
) -> io::Result<Option<Arc<Mutex<CircularTcpRotationController>>>> {
    if session_seed.recv_count == 0 {
        return Ok(None);
    }
    let group = state
        .config
        .groups
        .get(group_index)
        .cloned()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
    let policy = group.actions.rotation_policy.clone();
    Ok(policy
        .and_then(|policy| CircularTcpRotationController::new(group, policy))
        .map(|controller| Arc::new(Mutex::new(controller))))
}

fn remembered_host_value(remembered_host: &Arc<Mutex<Option<String>>>) -> Option<String> {
    remembered_host.lock().ok().and_then(|host| host.clone())
}

fn observe_rotation_inbound_chunk(
    state: &RuntimeState,
    target: Option<std::net::SocketAddr>,
    remembered_host: &Arc<Mutex<Option<String>>>,
    rotation: Option<&Arc<Mutex<CircularTcpRotationController>>>,
    reader: &TcpStream,
    chunk: &[u8],
) {
    let Some(rotation) = rotation else {
        return;
    };
    let host = remembered_host_value(remembered_host);
    let ready = match rotation.lock() {
        Ok(mut rotation) => rotation.observe_response_chunk(chunk),
        Err(_) => return,
    };
    if !ready {
        return;
    }
    let (request_bytes, response_bytes, retrans_baseline, stream_start) = match rotation.lock() {
        Ok(rotation) => {
            let Some(observed) = rotation.observed_round() else {
                return;
            };
            (
                observed.request_bytes.clone(),
                observed.response_bytes.clone(),
                observed.retrans_baseline,
                observed.stream_start,
            )
        }
        Err(_) => return,
    };
    if let Some(target) = target {
        if let Some(failure) =
            classify_response_failure(state, target, &request_bytes, &response_bytes, host.as_deref())
        {
            note_block_signal_for_failure(state, host.as_deref(), &failure, None);
            if let Ok(mut rotation) = rotation.lock() {
                rotation.observe_round_failure(
                    host.as_deref(),
                    Some(target),
                    RotationFailureReason::ResponseClassified(failure.class),
                    0,
                );
            }
            return;
        }
    }
    let retrans_delta = platform::tcp_total_retransmissions(reader)
        .ok()
        .flatten()
        .zip(retrans_baseline)
        .map(|(current, baseline)| current.saturating_sub(baseline))
        .unwrap_or_default();
    if let Ok(mut rotation) = rotation.lock() {
        if stream_start < rotation.policy.seq as usize && retrans_delta >= rotation.policy.retrans {
            rotation.observe_round_failure(
                host.as_deref(),
                target,
                RotationFailureReason::Retransmissions,
                retrans_delta,
            );
        } else {
            rotation.observe_round_success();
        }
    }
}

fn observe_rotation_transport_failure(
    state: &RuntimeState,
    target: Option<std::net::SocketAddr>,
    remembered_host: &Arc<Mutex<Option<String>>>,
    rotation: Option<&Arc<Mutex<CircularTcpRotationController>>>,
    reader: &TcpStream,
    err: io::Error,
) {
    let Some(rotation) = rotation else {
        return;
    };
    let (has_observation, retrans_baseline) = match rotation.lock() {
        Ok(rotation) => (
            rotation.observed_round().is_some(),
            rotation.observed_round().and_then(|observed| observed.retrans_baseline),
        ),
        Err(_) => return,
    };
    if !has_observation {
        return;
    }
    let host = remembered_host_value(remembered_host);
    let failure = classify_transport_error(FailureStage::FirstResponse, &err);
    let retrans_delta = platform::tcp_total_retransmissions(reader)
        .ok()
        .flatten()
        .zip(retrans_baseline)
        .map(|(current, baseline)| current.saturating_sub(baseline))
        .unwrap_or_default();
    note_block_signal_for_failure(state, host.as_deref(), &failure, None);
    if let Ok(mut rotation) = rotation.lock() {
        rotation.observe_round_failure(
            host.as_deref(),
            target,
            RotationFailureReason::Transport(failure.class),
            retrans_delta,
        );
    }
}

struct FreezeDetector {
    window_ms: u64,
    min_bytes: u64,
    max_stalls: u32,
    window_start: Instant,
    window_bytes: u64,
    consecutive_stalls: u32,
    warm: bool,
}

impl FreezeDetector {
    fn new(window_ms: u32, min_bytes: u32, max_stalls: u32) -> Self {
        Self {
            window_ms: u64::from(window_ms),
            min_bytes: u64::from(min_bytes),
            max_stalls,
            window_start: Instant::now(),
            window_bytes: 0,
            consecutive_stalls: 0,
            warm: false,
        }
    }

    fn is_enabled(&self) -> bool {
        self.max_stalls > 0
    }

    fn record_bytes(&mut self, n: usize) {
        self.warm = true;
        self.window_bytes += n as u64;
    }

    fn check(&mut self, now: Instant) -> bool {
        if !self.is_enabled() || !self.warm {
            return false;
        }
        let elapsed = now.duration_since(self.window_start).as_millis() as u64;
        if elapsed >= self.window_ms {
            if self.window_bytes < self.min_bytes {
                self.consecutive_stalls += 1;
            } else {
                self.consecutive_stalls = 0;
            }
            self.window_start = now;
            self.window_bytes = 0;
        }
        self.consecutive_stalls >= self.max_stalls
    }
}

pub(super) fn relay_streams(
    client: TcpStream,
    upstream: TcpStream,
    state: &RuntimeState,
    group_index: usize,
    session_seed: SessionState,
    remembered_host_seed: Option<String>,
) -> io::Result<SessionState> {
    let rotation_state = group_rotation_controller(state, group_index, &session_seed)?;
    let _ = (client.set_read_timeout(Some(RELAY_IDLE_TIMEOUT)), client.set_write_timeout(None));
    let _ = (upstream.set_read_timeout(Some(RELAY_IDLE_TIMEOUT)), upstream.set_write_timeout(None));

    fn clone_err(role: &'static str) -> impl FnOnce(io::Error) -> io::Error {
        move |e| io::Error::other(format!("clone {role} socket for relay: {e}"))
    }
    let client_reader = client.try_clone().map_err(clone_err("client"))?;
    let client_writer = client.try_clone().map_err(clone_err("client"))?;
    let upstream_reader = upstream.try_clone().map_err(clone_err("upstream"))?;
    let upstream_writer = upstream.try_clone().map_err(clone_err("upstream"))?;
    let session_state = Arc::new(Mutex::new(session_seed));
    let outbound_session = session_state.clone();
    let inbound_session = session_state.clone();
    let outbound_state = state.clone();
    let inbound_state = state.clone();
    let groups = &state.config.groups;
    let group = groups
        .get(group_index)
        .cloned()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
    let drop_sack = group.actions.drop_sack;
    let peer_done = Arc::new(AtomicBool::new(false));
    let freeze_detected = Arc::new(AtomicBool::new(false));
    let remembered_host = Arc::new(Mutex::new(remembered_host_seed));
    let inbound_host = remembered_host.clone();
    let outbound_host = remembered_host.clone();
    let inbound_rotation = rotation_state.clone();
    let outbound_rotation = rotation_state.clone();

    let freeze_flag = freeze_detected.clone();
    let timeouts = state.config.timeouts;
    let down_done = peer_done.clone();
    let down = thread::Builder::new()
        .name("ripdpi-dn".into())
        .spawn(move || {
            let detector =
                FreezeDetector::new(timeouts.freeze_window_ms, timeouts.freeze_min_bytes, timeouts.freeze_max_stalls);
            copy_inbound_half(
                upstream_reader,
                client_writer,
                &inbound_state,
                inbound_session,
                inbound_host,
                inbound_rotation,
                down_done,
                detector,
                freeze_flag,
            )
        })
        .map_err(|err| io::Error::other(format!("failed to spawn inbound relay thread: {err}")))?;

    // Keep the client->upstream half on the worker thread that already owns
    // this connection. That preserves the blocking desync path while avoiding
    // a second per-flow relay thread in steady state.
    let up_result = copy_outbound_half(
        client_reader,
        upstream_writer,
        outbound_state,
        group_index,
        outbound_session,
        outbound_host,
        outbound_rotation,
        peer_done.clone(),
    );
    if up_result.is_err() {
        peer_done.store(true, Ordering::Release);
        let _ = upstream.shutdown(Shutdown::Both);
        let _ = client.shutdown(Shutdown::Both);
    }
    let down_result = down.join().map_err(|_| io::Error::other("downstream thread panicked"))?;

    // Ensure both sockets are fully closed regardless of how relay threads exited.
    // The per-direction shutdown in each thread may be skipped on error paths;
    // this guarantees FIN is sent so sockets don't linger in CLOSE_WAIT.
    let _ = upstream.shutdown(Shutdown::Both);
    let _ = client.shutdown(Shutdown::Both);

    if drop_sack {
        let _ = platform::detach_drop_sack(&upstream);
    }

    up_result.and(down_result)?;

    if freeze_detected.load(Ordering::Acquire) {
        return Err(io::Error::new(io::ErrorKind::TimedOut, CONNECTION_FREEZE_MARKER));
    }

    session_state.lock().map_err(|_| io::Error::other("session mutex poisoned")).map(|state| state.clone())
}

fn copy_inbound_half(
    mut reader: TcpStream,
    mut writer: TcpStream,
    state: &RuntimeState,
    session: Arc<Mutex<SessionState>>,
    remembered_host: Arc<Mutex<Option<String>>>,
    rotation: Option<Arc<Mutex<CircularTcpRotationController>>>,
    peer_done: Arc<AtomicBool>,
    mut detector: FreezeDetector,
    freeze_detected: Arc<AtomicBool>,
) -> io::Result<()> {
    let mut buffer = [0u8; 16_384];
    let target = reader.peer_addr().ok();
    loop {
        match reader.read(&mut buffer) {
            Ok(0) => {
                observe_rotation_transport_failure(
                    state,
                    target,
                    &remembered_host,
                    rotation.as_ref(),
                    &reader,
                    io::Error::new(io::ErrorKind::UnexpectedEof, "upstream closed before first response"),
                );
                break;
            }
            Ok(n) => {
                if let Ok(mut state) = session.lock() {
                    state.observe_inbound(&buffer[..n]);
                }
                observe_rotation_inbound_chunk(
                    state,
                    target,
                    &remembered_host,
                    rotation.as_ref(),
                    &reader,
                    &buffer[..n],
                );
                writer.write_all(&buffer[..n])?;
                detector.record_bytes(n);
                if detector.check(Instant::now()) {
                    freeze_detected.store(true, Ordering::Release);
                    break;
                }
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                if detector.check(Instant::now()) {
                    freeze_detected.store(true, Ordering::Release);
                    break;
                }
                if peer_done.load(Ordering::Acquire) {
                    break;
                }
                continue;
            }
            Err(err) => {
                observe_rotation_transport_failure(
                    state,
                    target,
                    &remembered_host,
                    rotation.as_ref(),
                    &reader,
                    io::Error::new(err.kind(), err.to_string()),
                );
                return Err(err);
            }
        }
    }
    peer_done.store(true, Ordering::Release);
    let _ = writer.shutdown(Shutdown::Write);
    let _ = reader.shutdown(Shutdown::Read);
    Ok(())
}

fn flush_outbound_payload(
    writer: &mut TcpStream,
    state: &RuntimeState,
    group_index: usize,
    session: &Arc<Mutex<SessionState>>,
    remembered_host: &Arc<Mutex<Option<String>>>,
    rotation: Option<&Arc<Mutex<CircularTcpRotationController>>>,
    payload: &[u8],
) -> io::Result<()> {
    let (is_new_round, progress) = {
        let mut state = session.lock().map_err(|_| io::Error::other("session mutex poisoned"))?;
        let is_new_round = state.sent_this_round == 0;
        let progress = state.observe_outbound(payload);
        (is_new_round, progress)
    };
    let mut remembered = remembered_host.lock().map_err(|_| io::Error::other("remembered host mutex poisoned"))?;
    if let Some(host) = extract_host(&state.config, payload) {
        *remembered = Some(host);
    }
    let host = remembered.clone();
    drop(remembered);
    let groups = &state.config.groups;
    let base_group = groups
        .get(group_index)
        .cloned()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
    let peer_addr = writer.peer_addr()?;
    let group = if let Some(rotation) = rotation {
        let mut rotation = rotation.lock().map_err(|_| io::Error::other("rotation mutex poisoned"))?;
        let retrans_baseline =
            if is_new_round { platform::tcp_total_retransmissions(writer).ok().flatten() } else { None };
        if is_new_round {
            rotation.start_round(
                &state.config,
                progress.round,
                progress.stream_start,
                payload,
                retrans_baseline,
                host.as_deref(),
                Some(peer_addr),
            );
        } else {
            rotation.append_request_chunk(&state.config, progress.round, payload);
        }
        rotation.current_group()
    } else {
        base_group
    };
    let send_outcome =
        send_with_group(writer, state, group_index, &group, payload, progress, host.as_deref(), peer_addr)
            .map_err(OutboundSendError::into_io_error)?;
    tracing::trace!(
        target = %peer_addr,
        strategy_family = send_outcome.strategy_family.unwrap_or("plain"),
        bytes_committed = send_outcome.bytes_committed,
        "steady-state outbound payload forwarded"
    );
    Ok(())
}

fn copy_outbound_half(
    mut reader: TcpStream,
    mut writer: TcpStream,
    state: RuntimeState,
    group_index: usize,
    session: Arc<Mutex<SessionState>>,
    remembered_host: Arc<Mutex<Option<String>>>,
    rotation: Option<Arc<Mutex<CircularTcpRotationController>>>,
    peer_done: Arc<AtomicBool>,
) -> io::Result<()> {
    let mut buffer = [0u8; 16_384];
    loop {
        let _ = reader.set_read_timeout(Some(RELAY_IDLE_TIMEOUT));
        match reader.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => {
                flush_outbound_payload(
                    &mut writer,
                    &state,
                    group_index,
                    &session,
                    &remembered_host,
                    rotation.as_ref(),
                    &buffer[..n],
                )?;
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                if peer_done.load(Ordering::Acquire) {
                    break;
                }
                continue;
            }
            Err(err) => return Err(err),
        }
    }
    peer_done.store(true, Ordering::Release);
    let _ = writer.shutdown(Shutdown::Write);
    let _ = reader.shutdown(Shutdown::Read);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_config::{
        DesyncGroup, OffsetBase, OffsetExpr, RotationCandidate, RotationPolicy, TcpChainStep, TcpChainStepKind,
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
    fn freeze_detector_disabled_when_max_stalls_zero() {
        let mut d = FreezeDetector::new(5000, 512, 0);
        d.record_bytes(1);
        let far_future = Instant::now() + Duration::from_secs(300);
        assert!(!d.check(far_future));
    }

    #[test]
    fn freeze_detector_does_not_trigger_before_warm() {
        let mut d = FreezeDetector::new(100, 512, 1);
        let far_future = Instant::now() + Duration::from_secs(300);
        assert!(!d.check(far_future));
    }

    #[test]
    fn freeze_detector_triggers_after_consecutive_stalls() {
        let start = Instant::now();
        let mut d = FreezeDetector::new(100, 512, 3);
        d.window_start = start;
        d.record_bytes(1024);

        // First window: good throughput -- reset
        assert!(!d.check(start + Duration::from_millis(100)));
        assert_eq!(d.consecutive_stalls, 0);

        // Windows 2-4: only trickle bytes (below 512)
        d.record_bytes(10);
        assert!(!d.check(start + Duration::from_millis(200)));
        assert_eq!(d.consecutive_stalls, 1);

        d.record_bytes(5);
        assert!(!d.check(start + Duration::from_millis(300)));
        assert_eq!(d.consecutive_stalls, 2);

        d.record_bytes(2);
        assert!(d.check(start + Duration::from_millis(400)));
        assert_eq!(d.consecutive_stalls, 3);
    }

    #[test]
    fn freeze_detector_resets_on_good_window() {
        let start = Instant::now();
        let mut d = FreezeDetector::new(100, 512, 3);
        d.window_start = start;
        d.record_bytes(1024);

        // Warm-up window passes with good throughput
        assert!(!d.check(start + Duration::from_millis(100)));
        assert_eq!(d.consecutive_stalls, 0);

        // First stall window: only 10 bytes
        d.record_bytes(10);
        d.check(start + Duration::from_millis(200));
        assert_eq!(d.consecutive_stalls, 1);

        // Second stall window
        d.record_bytes(10);
        d.check(start + Duration::from_millis(300));
        assert_eq!(d.consecutive_stalls, 2);

        // Good window -- resets counter
        d.record_bytes(600);
        assert!(!d.check(start + Duration::from_millis(400)));
        assert_eq!(d.consecutive_stalls, 0);
    }

    #[test]
    fn freeze_detector_does_not_false_positive_on_slow_but_sufficient_transfer() {
        let start = Instant::now();
        let mut d = FreezeDetector::new(5000, 512, 3);
        d.window_start = start;
        d.record_bytes(1024);

        // Each window: exactly at threshold (512 bytes)
        for i in 1..=10 {
            d.record_bytes(512);
            assert!(!d.check(start + Duration::from_millis(5000 * i)));
            assert_eq!(d.consecutive_stalls, 0);
        }
    }

    #[test]
    fn rotation_retransmission_failure_advances_on_next_round() {
        let mut controller = rotation_controller();
        let config = ripdpi_config::RuntimeConfig::default();

        controller.start_round(&config, 2, 0, b"GET / HTTP/1.1\r\n", Some(1), Some("example.org"), None);
        controller.observe_round_failure(Some("example.org"), None, RotationFailureReason::Retransmissions, 3);
        assert!(controller.pending_advance);
        assert_eq!(controller.consecutive_failures, 1);

        controller.start_round(&config, 3, 128, b"GET / HTTP/1.1\r\n", Some(4), Some("example.org"), None);

        assert_eq!(controller.active_candidate_index, Some(0));
        assert!(!controller.pending_advance);
    }

    #[test]
    fn rotation_success_clears_failure_window() {
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
    fn rotation_reset_failure_rotates_on_next_round() {
        let mut controller = rotation_controller();
        let config = ripdpi_config::RuntimeConfig::default();

        controller.start_round(&config, 2, 0, b"GET / HTTP/1.1\r\n", Some(0), Some("example.org"), None);
        controller.observe_round_failure(
            Some("example.org"),
            None,
            RotationFailureReason::Transport(FailureClass::TcpReset),
            0,
        );
        assert!(controller.pending_advance);

        controller.start_round(&config, 3, 64, b"GET / HTTP/1.1\r\n", Some(0), Some("example.org"), None);

        assert_eq!(controller.active_candidate_index, Some(0));
    }

    #[test]
    fn rotation_wraps_back_to_first_candidate() {
        let mut controller = rotation_controller();
        controller.active_candidate_index = Some(1);
        controller.pending_advance = true;

        controller.rotate_if_pending(Some("example.org"), None, 4);

        assert_eq!(controller.active_candidate_index, Some(0));
        assert!(!controller.pending_advance);
    }
}
