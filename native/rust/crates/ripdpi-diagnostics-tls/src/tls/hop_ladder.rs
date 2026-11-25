use std::io::{self, Read, Write};
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::time::{Duration, Instant};

use rustls::pki_types::ServerName;
use rustls::{ClientConfig, ClientConnection};

use super::config::build_standard_client_config_with_key_log;
use super::types::{ApplicationProtocolPolicy, TlsClientProfile};
use crate::platform_ttl::{set_abortive_close, set_outbound_ttl};
use crate::transport::{connect_direct_address_with_abort, protected_tcp_socket};
use crate::util::CONNECT_TIMEOUT;

pub const MAX_LADDER_TTL: u8 = 32;
pub const REPETITIONS: u8 = 2;
pub const ATTEMPT_WINDOW: Duration = Duration::from_millis(1000);
pub const TOTAL_DEADLINE: Duration = Duration::from_secs(150);
const READ_SLICE: Duration = Duration::from_millis(50);

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SniRole {
    Control,
    Test,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AttemptOutcome {
    Rst,
    Fin,
    TlsAlert,
    ServerHello,
    Other,
    Silence,
    HandshakeFailed,
    LocalError,
}

impl AttemptOutcome {
    fn is_reaction(self) -> bool {
        !matches!(self, Self::Silence | Self::HandshakeFailed | Self::LocalError)
    }

    fn is_interference(self) -> bool {
        self.is_reaction() && self != Self::ServerHello
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LadderAttempt {
    pub ttl: u8,
    pub role: SniRole,
    pub repetition: u8,
    pub outcome: AttemptOutcome,
    pub elapsed_ms: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LadderLimit {
    ControlNeverReachedServer,
    ControlReactsLikeTest,
    ServerHelloNearClient,
    NetworkChanged,
    InconsistentRepetitions,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum UnsupportedReason {
    Protect,
    TtlSetter,
    Linger,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LadderStop {
    Completed,
    Cancelled,
    DeadlineExceeded,
    NetworkChanged,
    Unsupported(UnsupportedReason),
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LadderVerdict {
    Divergence { first_hop: u8, last_hop: u8 },
    Inconclusive,
    Unsupported(UnsupportedReason),
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LadderReport {
    pub verdict: LadderVerdict,
    pub path_length: Option<u8>,
    pub limits: Vec<LadderLimit>,
    pub attempts: Vec<LadderAttempt>,
    pub stop: LadderStop,
}

#[derive(Clone, Copy, Debug)]
pub struct LadderConfig {
    pub attempt_window: Duration,
    pub total_deadline: Duration,
}

impl Default for LadderConfig {
    fn default() -> Self {
        Self { attempt_window: ATTEMPT_WINDOW, total_deadline: TOTAL_DEADLINE }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AttemptError {
    Unsupported(UnsupportedReason),
    Aborted,
}

pub trait LadderProbe {
    fn attempt(
        &mut self,
        role: SniRole,
        ttl: u8,
        window: Duration,
        should_abort: &dyn Fn() -> bool,
    ) -> Result<AttemptOutcome, AttemptError>;

    fn network_changed(&self) -> bool {
        false
    }
}

pub fn run_ladder(probe: &mut dyn LadderProbe, config: LadderConfig, should_abort: &dyn Fn() -> bool) -> LadderReport {
    let started = Instant::now();
    let mut attempts = Vec::new();
    let stop = 'ladder: {
        for ttl in 1..=MAX_LADDER_TTL {
            for repetition in 0..REPETITIONS {
                for role in [SniRole::Control, SniRole::Test] {
                    if should_abort() {
                        break 'ladder LadderStop::Cancelled;
                    }
                    if started.elapsed() + CONNECT_TIMEOUT + config.attempt_window > config.total_deadline {
                        break 'ladder LadderStop::DeadlineExceeded;
                    }
                    let attempt_started = Instant::now();
                    match probe.attempt(role, ttl, config.attempt_window, should_abort) {
                        Ok(outcome) => attempts.push(LadderAttempt {
                            ttl,
                            role,
                            repetition,
                            outcome,
                            elapsed_ms: attempt_started.elapsed().as_millis() as u64,
                        }),
                        Err(AttemptError::Aborted) => break 'ladder LadderStop::Cancelled,
                        Err(AttemptError::Unsupported(reason)) => break 'ladder LadderStop::Unsupported(reason),
                    }
                    if probe.network_changed() {
                        break 'ladder LadderStop::NetworkChanged;
                    }
                }
            }
            if outcomes(&attempts, ttl, SniRole::Control).any(|outcome| outcome == AttemptOutcome::ServerHello) {
                break 'ladder LadderStop::Completed;
            }
        }
        LadderStop::Completed
    };
    analyze(attempts, stop)
}

fn outcomes(attempts: &[LadderAttempt], ttl: u8, role: SniRole) -> impl Iterator<Item = AttemptOutcome> + '_ {
    attempts.iter().filter(move |attempt| attempt.ttl == ttl && attempt.role == role).map(|attempt| attempt.outcome)
}

fn interfered(attempts: &[LadderAttempt], ttl: u8, role: SniRole) -> bool {
    outcomes(attempts, ttl, role).any(AttemptOutcome::is_interference)
}

fn silent(attempts: &[LadderAttempt], ttl: u8, role: SniRole) -> bool {
    let mut cell = outcomes(attempts, ttl, role).peekable();
    cell.peek().is_some() && cell.all(|outcome| outcome == AttemptOutcome::Silence)
}

fn analyze(attempts: Vec<LadderAttempt>, stop: LadderStop) -> LadderReport {
    if let LadderStop::Unsupported(reason) = stop {
        return LadderReport {
            verdict: LadderVerdict::Unsupported(reason),
            path_length: None,
            limits: Vec::new(),
            attempts,
            stop,
        };
    }
    let mut ttls: Vec<u8> = attempts.iter().map(|attempt| attempt.ttl).collect();
    ttls.dedup();
    let server_hello_at = |role| {
        ttls.iter().copied().find(|&ttl| outcomes(&attempts, ttl, role).any(|o| o == AttemptOutcome::ServerHello))
    };
    let path_length = server_hello_at(SniRole::Control).filter(|_| stop != LadderStop::NetworkChanged);
    let divergence = ttls
        .iter()
        .copied()
        .find(|&ttl| interfered(&attempts, ttl, SniRole::Test) && silent(&attempts, ttl, SniRole::Control));

    let verdict = match (divergence, path_length) {
        (Some(last_hop), Some(path)) if last_hop < path => {
            let lower_silent = ttls
                .iter()
                .copied()
                .filter(|&ttl| ttl < last_hop)
                .rev()
                .find(|&ttl| silent(&attempts, ttl, SniRole::Control) && silent(&attempts, ttl, SniRole::Test));
            LadderVerdict::Divergence { first_hop: lower_silent.map_or(1, |ttl| ttl + 1), last_hop }
        }
        _ => LadderVerdict::Inconclusive,
    };

    let mut limits = Vec::new();
    if path_length.is_none() && stop == LadderStop::Completed {
        limits.push(LadderLimit::ControlNeverReachedServer);
    }
    if ttls.iter().any(|&ttl| interfered(&attempts, ttl, SniRole::Test) && interfered(&attempts, ttl, SniRole::Control))
    {
        limits.push(LadderLimit::ControlReactsLikeTest);
    }
    if matches!(path_length, Some(1 | 2)) {
        limits.push(LadderLimit::ServerHelloNearClient);
    }
    if stop == LadderStop::NetworkChanged {
        limits.push(LadderLimit::NetworkChanged);
    }
    let repetitions_differ = ttls.iter().any(|&ttl| {
        [SniRole::Control, SniRole::Test].into_iter().any(|role| {
            let mut cell = outcomes(&attempts, ttl, role);
            cell.next().is_some_and(|first| cell.any(|outcome| outcome != first))
        })
    });
    let test_path_shorter =
        server_hello_at(SniRole::Test).is_some_and(|test| server_hello_at(SniRole::Control).is_none_or(|c| test < c));
    if repetitions_differ || test_path_shorter {
        limits.push(LadderLimit::InconsistentRepetitions);
    }

    LadderReport { verdict, path_length, limits, attempts, stop }
}

pub struct SocketLadderProbe {
    target: SocketAddr,
    control_hello: Vec<u8>,
    test_hello: Vec<u8>,
    local_ip: Option<IpAddr>,
    network_changed: bool,
}

impl SocketLadderProbe {
    pub fn new(target: SocketAddr, control_sni: &str, test_sni: &str) -> Result<Self, LadderSetupError> {
        let config = build_standard_client_config_with_key_log(
            TlsClientProfile::Auto,
            None,
            None,
            ApplicationProtocolPolicy::TemplateDefault,
        );
        if control_sni.eq_ignore_ascii_case(test_sni) {
            return Err(LadderSetupError::InvalidSni(test_sni.to_owned()));
        }
        let control_hello = client_hello(&config, control_sni)?;
        let test_hello = client_hello(&config, test_sni)?;
        protected_tcp_socket(target).map_err(|_| LadderSetupError::Unsupported(UnsupportedReason::Protect))?;
        Ok(Self { target, control_hello, test_hello, local_ip: None, network_changed: false })
    }
}

fn client_hello(config: &Arc<ClientConfig>, sni: &str) -> Result<Vec<u8>, LadderSetupError> {
    let invalid = || LadderSetupError::InvalidSni(sni.to_owned());
    let Ok(name @ ServerName::DnsName(_)) = ServerName::try_from(sni.to_owned()) else {
        return Err(invalid());
    };
    let mut connection = ClientConnection::new(config.clone(), name).map_err(|_| invalid())?;
    let mut hello = Vec::new();
    while connection.wants_write() {
        connection.write_tls(&mut hello).map_err(|_| invalid())?;
    }
    Ok(hello)
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum LadderSetupError {
    InvalidSni(String),
    Unsupported(UnsupportedReason),
}

impl LadderProbe for SocketLadderProbe {
    fn network_changed(&self) -> bool {
        self.network_changed
    }

    fn attempt(
        &mut self,
        role: SniRole,
        ttl: u8,
        window: Duration,
        should_abort: &dyn Fn() -> bool,
    ) -> Result<AttemptOutcome, AttemptError> {
        let hello = match role {
            SniRole::Control => &self.control_hello,
            SniRole::Test => &self.test_hello,
        };
        let mut stream = match connect_direct_address_with_abort(self.target, &|| {
            should_abort().then_some("hop_ladder_cancelled")
        }) {
            Ok(stream) => stream,
            Err(_) if should_abort() => return Err(AttemptError::Aborted),
            Err(_) if protected_tcp_socket(self.target).is_err() => {
                return Err(AttemptError::Unsupported(UnsupportedReason::Protect));
            }
            Err(_) => return Ok(AttemptOutcome::HandshakeFailed),
        };
        if let Ok(local) = stream.local_addr() {
            match self.local_ip {
                None => self.local_ip = Some(local.ip()),
                Some(first) if first != local.ip() => self.network_changed = true,
                Some(_) => {}
            }
        }
        set_abortive_close(&stream).map_err(|_| AttemptError::Unsupported(UnsupportedReason::Linger))?;
        set_outbound_ttl(&stream, ttl).map_err(|_| AttemptError::Unsupported(UnsupportedReason::TtlSetter))?;
        if let Err(error) = stream.write_all(hello) {
            return Ok(classify_io_error(&error));
        }
        let deadline = Instant::now() + window;
        let mut received = Vec::new();
        let mut chunk = [0u8; 512];
        loop {
            if should_abort() {
                return Err(AttemptError::Aborted);
            }
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return Ok(if received.is_empty() { AttemptOutcome::Silence } else { AttemptOutcome::Other });
            }
            if stream.set_read_timeout(Some(remaining.min(READ_SLICE))).is_err() {
                return Ok(AttemptOutcome::LocalError);
            }
            match stream.read(&mut chunk) {
                Ok(0) => return Ok(if received.is_empty() { AttemptOutcome::Fin } else { AttemptOutcome::Other }),
                Ok(read) => {
                    received.extend_from_slice(&chunk[..read]);
                    if let Some(outcome) = classify_response(&received) {
                        return Ok(outcome);
                    }
                }
                Err(error)
                    if matches!(
                        error.kind(),
                        io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut | io::ErrorKind::Interrupted
                    ) => {}
                Err(error) => return Ok(classify_io_error(&error)),
            }
        }
    }
}

fn classify_io_error(error: &io::Error) -> AttemptOutcome {
    match error.kind() {
        io::ErrorKind::ConnectionReset | io::ErrorKind::ConnectionAborted | io::ErrorKind::BrokenPipe => {
            AttemptOutcome::Rst
        }
        _ => AttemptOutcome::Other,
    }
}

fn classify_response(received: &[u8]) -> Option<AttemptOutcome> {
    const RECORD_ALERT: u8 = 0x15;
    const RECORD_HANDSHAKE: u8 = 0x16;
    const HANDSHAKE_SERVER_HELLO: u8 = 0x02;
    match received {
        [RECORD_HANDSHAKE, 0x03, _, _, _, HANDSHAKE_SERVER_HELLO, ..] => Some(AttemptOutcome::ServerHello),
        [RECORD_ALERT, 0x03, _, _, _, _, ..] => Some(AttemptOutcome::TlsAlert),
        [_, _, _, _, _, _, ..] => Some(AttemptOutcome::Other),
        _ => None,
    }
}

#[cfg(test)]
mod tests;
