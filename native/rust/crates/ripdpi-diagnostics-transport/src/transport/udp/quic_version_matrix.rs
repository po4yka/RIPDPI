//! QUIC v1/v2 Initial matrix with control and test SNI on isolated UDP flows.
//!
//! The matrix reports Initial-stage evidence only. Initial keys come from public inputs, so a valid
//! Initial response is not endpoint authentication, handshake success or HTTP/3 success. A result
//! never changes relay or strategy settings.

use std::io;
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket};
use std::sync::atomic::AtomicBool;
use std::time::{Duration, Instant};

use super::super::protect::protected_udp_bind;
use super::super::types::TransportError;
use super::{check_udp_attempt, poll_wait, udp_remaining};

/// Repetitions of the four-cell matrix.
pub const QUIC_MATRIX_REPETITIONS: usize = 2;

const QUIC_V1_WIRE: u32 = 0x0000_0001;
const QUIC_V2_WIRE: u32 = 0x6b33_43cf;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuicMatrixVersion {
    V1,
    V2,
}

impl QuicMatrixVersion {
    /// RFC 9000 and RFC 9369 version numbers. Unknown-version decoys are never a matrix version.
    pub fn wire(self) -> u32 {
        match self {
            Self::V1 => QUIC_V1_WIRE,
            Self::V2 => QUIC_V2_WIRE,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuicMatrixSni {
    Control,
    Test,
}

/// How the caller classified one datagram from the endpoint.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuicReply {
    /// The DIA-07 validator accepted a server Initial for this request.
    ValidInitial,
    /// A correlated Version Negotiation packet.
    VersionNegotiation,
    /// Anything else: Retry, unvalidated or uncorrelated bytes.
    Other,
}

/// Builds Initials and validates replies. The caller supplies the `ripdpi-packets` builders
/// and the DIA-07 Initial-response validator: every accepted Initial kind (CRYPTO, ACK, other
/// frames, CONNECTION_CLOSE) maps to `ValidInitial`, Version Negotiation to `VersionNegotiation`,
/// and Retry or a rejected datagram to `Other`.
pub trait QuicInitialCodec {
    /// Build a fresh client Initial with new connection IDs for `version` and `sni`.
    fn build(&self, version: u32, sni: &str) -> Option<Vec<u8>>;
    /// Classify one datagram that came from the endpoint in reply to `request`.
    fn classify(&self, request: &[u8], response: &[u8]) -> QuicReply;
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuicCellStage {
    ValidInitial,
    VersionNegotiationOnly,
    /// Datagrams came from the endpoint but none was a valid Initial or Version Negotiation.
    Other,
    Timeout,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct QuicCellOutcome {
    pub stage: QuicCellStage,
    pub bytes_sent: usize,
    pub bytes_received: usize,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct QuicMatrixCell {
    /// Execution order across the whole matrix, starting at 0.
    pub sequence: usize,
    pub repetition: usize,
    pub version: QuicMatrixVersion,
    pub sni: QuicMatrixSni,
    pub local_port: u16,
    pub outcome: QuicCellOutcome,
}

/// Test SNI then control SNI on one socket. Reported apart from the matrix.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct QuicResidualCell {
    pub version: QuicMatrixVersion,
    pub local_port: u16,
    pub test: QuicCellOutcome,
    pub control: QuicCellOutcome,
    /// Fresh-socket controls for this version were valid, but the control after the test timed out.
    /// An `Other` control is not counted: leftover test-flow Handshake datagrams can cause it.
    pub residual_state_suspected: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuicVersionStatus {
    /// Not every control cell got a valid Initial response. Says nothing about blocking.
    Inconclusive,
    /// Every control cell got Version Negotiation only: the endpoint does not offer this version.
    UnsupportedByEndpoint,
    /// Controls valid; the test SNI got a valid Initial response in every repetition.
    TestAnswered,
    /// Controls valid; the test SNI got no valid Initial response in any repetition.
    TestNotAnswered,
    /// Controls valid; repetitions disagree for the test SNI.
    TestMixed,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuicMatrixStop {
    Cancelled,
    DeadlineExceeded,
}

/// Limits every matrix result carries.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuicMatrixLimit {
    /// Initial-stage evidence only: not endpoint authentication, handshake or HTTP/3 success.
    InitialStageOnly,
    /// Each cell uses a new local port, which does not prove a separate NAT mapping.
    LocalPortSeparationOnly,
}

pub const QUIC_MATRIX_LIMITS: &[QuicMatrixLimit] =
    &[QuicMatrixLimit::InitialStageOnly, QuicMatrixLimit::LocalPortSeparationOnly];

pub struct QuicVersionMatrixRequest<'a> {
    /// One user-authorized endpoint.
    pub endpoint: SocketAddr,
    pub control_sni: &'a str,
    pub test_sni: &'a str,
    /// Receive window for one Initial.
    pub cell_timeout: Duration,
    /// Bytes sent plus received for one Initial exchange. Receiving stops once a datagram crosses
    /// the budget, so the total can exceed it by at most that one datagram.
    pub cell_byte_budget: usize,
    /// Wall-clock bound for the whole run, residual cell included.
    pub total_deadline: Duration,
    /// Run the same-socket residual cell for this version. `None` keeps it disabled.
    pub residual: Option<QuicMatrixVersion>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct QuicVersionMatrixReport {
    pub cells: Vec<QuicMatrixCell>,
    pub residual: Option<QuicResidualCell>,
    pub stopped: Option<QuicMatrixStop>,
    pub v1: QuicVersionStatus,
    pub v2: QuicVersionStatus,
    /// Initial-stage version difference for the test SNI. Requires valid controls on both versions.
    pub version_difference: bool,
    pub limits: &'static [QuicMatrixLimit],
}

/// Run the matrix: each repetition alternates the version order, and every cell uses a fresh
/// protected socket. All sockets stay open until the run ends, so no two cells share a local port.
/// Cancellation and the scan deadline are checked between cells and while a cell waits.
/// A cell that does not complete is dropped and `stopped` says why.
pub fn run_quic_version_matrix(
    request: &QuicVersionMatrixRequest<'_>,
    codec: &dyn QuicInitialCodec,
    cancel: &AtomicBool,
) -> Result<QuicVersionMatrixReport, TransportError> {
    if request.cell_timeout.is_zero() || request.cell_byte_budget == 0 {
        return Err(TransportError::InvalidProbePayload);
    }
    let deadline = Instant::now().checked_add(request.total_deadline).ok_or(TransportError::InvalidProbePayload)?;
    let mut cells = Vec::with_capacity(4 * QUIC_MATRIX_REPETITIONS);
    let mut sockets = Vec::with_capacity(4 * QUIC_MATRIX_REPETITIONS + 1);
    let mut stopped = None;

    'matrix: for repetition in 0..QUIC_MATRIX_REPETITIONS {
        let versions = if repetition % 2 == 0 {
            [QuicMatrixVersion::V1, QuicMatrixVersion::V2]
        } else {
            [QuicMatrixVersion::V2, QuicMatrixVersion::V1]
        };
        for version in versions {
            for sni in [QuicMatrixSni::Control, QuicMatrixSni::Test] {
                if let Err(stop) = stop_reason(check_udp_attempt(Some(cancel)))? {
                    stopped = Some(stop);
                    break 'matrix;
                }
                let socket = fresh_socket(request.endpoint)?;
                let local_port = socket.local_addr()?.port();
                let sni_name = match sni {
                    QuicMatrixSni::Control => request.control_sni,
                    QuicMatrixSni::Test => request.test_sni,
                };
                let outcome = exchange(&socket, request, codec, version, sni_name, None, deadline, cancel)
                    .map(|(outcome, _)| outcome);
                sockets.push(socket);
                match stop_reason(outcome)? {
                    Ok(outcome) => cells.push(QuicMatrixCell {
                        sequence: cells.len(),
                        repetition,
                        version,
                        sni,
                        local_port,
                        outcome,
                    }),
                    Err(stop) => {
                        stopped = Some(stop);
                        break 'matrix;
                    }
                }
            }
        }
    }

    let v1 = quic_version_status(&cells, QuicMatrixVersion::V1);
    let v2 = quic_version_status(&cells, QuicMatrixVersion::V2);
    let mut residual = None;
    if let (None, Some(version)) = (stopped, request.residual) {
        match run_residual(request, codec, version, deadline, cancel)? {
            Ok((local_port, test, control)) => {
                let fresh = if version == QuicMatrixVersion::V1 { v1 } else { v2 };
                let controls_valid = matches!(
                    fresh,
                    QuicVersionStatus::TestAnswered | QuicVersionStatus::TestNotAnswered | QuicVersionStatus::TestMixed
                );
                residual = Some(QuicResidualCell {
                    version,
                    local_port,
                    test,
                    control,
                    residual_state_suspected: controls_valid && control.stage == QuicCellStage::Timeout,
                });
            }
            Err(stop) => stopped = Some(stop),
        }
    }
    drop(sockets);

    Ok(QuicVersionMatrixReport {
        cells,
        residual,
        stopped,
        v1,
        v2,
        version_difference: quic_version_difference(v1, v2),
        limits: QUIC_MATRIX_LIMITS,
    })
}

/// Status of one version from the matrix cells. A cell missing after a stop counts as a failed control.
pub fn quic_version_status(cells: &[QuicMatrixCell], version: QuicMatrixVersion) -> QuicVersionStatus {
    let stages = |sni| {
        cells.iter().filter(move |cell| cell.version == version && cell.sni == sni).map(|cell| cell.outcome.stage)
    };
    let controls = stages(QuicMatrixSni::Control).collect::<Vec<_>>();
    if controls.len() == QUIC_MATRIX_REPETITIONS
        && controls.iter().all(|stage| *stage == QuicCellStage::VersionNegotiationOnly)
    {
        return QuicVersionStatus::UnsupportedByEndpoint;
    }
    let tests = stages(QuicMatrixSni::Test).collect::<Vec<_>>();
    if controls.len() < QUIC_MATRIX_REPETITIONS
        || tests.len() < QUIC_MATRIX_REPETITIONS
        || controls.iter().any(|stage| *stage != QuicCellStage::ValidInitial)
    {
        return QuicVersionStatus::Inconclusive;
    }
    match tests.iter().filter(|stage| **stage == QuicCellStage::ValidInitial).count() {
        0 => QuicVersionStatus::TestNotAnswered,
        QUIC_MATRIX_REPETITIONS => QuicVersionStatus::TestAnswered,
        _ => QuicVersionStatus::TestMixed,
    }
}

/// A version difference needs valid controls on both versions and a consistent, different test outcome.
pub fn quic_version_difference(v1: QuicVersionStatus, v2: QuicVersionStatus) -> bool {
    use QuicVersionStatus::{TestAnswered, TestNotAnswered};
    matches!((v1, v2), (TestAnswered, TestNotAnswered) | (TestNotAnswered, TestAnswered))
}

type Residual = (u16, QuicCellOutcome, QuicCellOutcome);

fn run_residual(
    request: &QuicVersionMatrixRequest<'_>,
    codec: &dyn QuicInitialCodec,
    version: QuicMatrixVersion,
    deadline: Instant,
    cancel: &AtomicBool,
) -> Result<Result<Residual, QuicMatrixStop>, TransportError> {
    if let Err(stop) = stop_reason(check_udp_attempt(Some(cancel)))? {
        return Ok(Err(stop));
    }
    let socket = fresh_socket(request.endpoint)?;
    let local_port = socket.local_addr()?.port();
    let (test, test_initial) =
        match stop_reason(exchange(&socket, request, codec, version, request.test_sni, None, deadline, cancel))? {
            Ok(done) => done,
            Err(stop) => return Ok(Err(stop)),
        };
    let control = stop_reason(
        exchange(&socket, request, codec, version, request.control_sni, Some(&test_initial), deadline, cancel)
            .map(|(outcome, _)| outcome),
    )?;
    Ok(control.map(|control| (local_port, test, control)))
}

fn fresh_socket(endpoint: SocketAddr) -> Result<UdpSocket, TransportError> {
    let bind: SocketAddr =
        if endpoint.is_ipv4() { (Ipv4Addr::UNSPECIFIED, 0).into() } else { (Ipv6Addr::UNSPECIFIED, 0).into() };
    Ok(protected_udp_bind(bind, endpoint)?)
}

/// Turn cancellation and deadline errors into a stop; propagate every other failure.
fn stop_reason<T>(result: Result<T, TransportError>) -> Result<Result<T, QuicMatrixStop>, TransportError> {
    match result {
        Ok(value) => Ok(Ok(value)),
        Err(TransportError::ProbeAborted(_)) => Ok(Err(QuicMatrixStop::Cancelled)),
        Err(TransportError::ScanDeadlineExceeded) => Ok(Err(QuicMatrixStop::DeadlineExceeded)),
        Err(error) => Err(error),
    }
}

/// Send one Initial and wait for its reply. Datagrams that answer `earlier` (an Initial sent
/// before on the same socket) are ignored and not charged to the budget.
#[allow(clippy::too_many_arguments)] // shortcut: one private call shape; group into a struct if a caller is added.
fn exchange(
    socket: &UdpSocket,
    request: &QuicVersionMatrixRequest<'_>,
    codec: &dyn QuicInitialCodec,
    version: QuicMatrixVersion,
    sni: &str,
    earlier: Option<&[u8]>,
    deadline: Instant,
    cancel: &AtomicBool,
) -> Result<(QuicCellOutcome, Vec<u8>), TransportError> {
    check_udp_attempt(Some(cancel))?;
    let cell_deadline = Instant::now().checked_add(request.cell_timeout).ok_or(TransportError::InvalidProbePayload)?;
    // Start a cell only with its full receive window, so every Timeout means the same wait.
    if cell_deadline > deadline {
        return Err(TransportError::ScanDeadlineExceeded);
    }
    let initial = codec.build(version.wire(), sni).ok_or(TransportError::InvalidProbePayload)?;
    // A builder that falls back to another version must not be counted as this version.
    if initial.first().is_none_or(|flags| flags & 0x80 == 0) || initial.get(1..5) != Some(&version.wire().to_be_bytes())
    {
        return Err(TransportError::InvalidProbePayload);
    }
    if initial.len() > request.cell_byte_budget {
        return Err(TransportError::InvalidProbePayload);
    }

    socket.set_write_timeout(Some(poll_wait(udp_remaining(cell_deadline, Some(cancel))?, true)))?;
    socket.send_to(&initial, request.endpoint)?;
    let mut bytes = vec![0; usize::from(u16::MAX)];
    let mut received = 0;
    let mut other = false;
    let stage = loop {
        let remaining = match udp_remaining(cell_deadline, Some(cancel)) {
            Ok(remaining) => remaining,
            Err(TransportError::UdpRecvTimeout) if other => break QuicCellStage::Other,
            Err(TransportError::UdpRecvTimeout) => break QuicCellStage::Timeout,
            Err(error) => return Err(error),
        };
        socket.set_read_timeout(Some(poll_wait(remaining, true)))?;
        match socket.recv_from(&mut bytes) {
            // Datagrams from other sources are not the endpoint's reply.
            Ok((size, from))
                if from == request.endpoint
                    && earlier.is_none_or(|earlier| codec.classify(earlier, &bytes[..size]) == QuicReply::Other) =>
            {
                received += size;
                match codec.classify(&initial, &bytes[..size]) {
                    QuicReply::ValidInitial => break QuicCellStage::ValidInitial,
                    QuicReply::VersionNegotiation => break QuicCellStage::VersionNegotiationOnly,
                    QuicReply::Other if initial.len() + received >= request.cell_byte_budget => {
                        break QuicCellStage::Other;
                    }
                    QuicReply::Other => other = true,
                }
            }
            Ok(_) => {}
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::Interrupted | io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                ) => {}
            Err(error) => return Err(TransportError::udp_recv_error(error)),
        }
    };
    Ok((QuicCellOutcome { stage, bytes_sent: initial.len(), bytes_received: received }, initial))
}

#[cfg(test)]
#[path = "quic_version_matrix_tests.rs"]
mod tests;
