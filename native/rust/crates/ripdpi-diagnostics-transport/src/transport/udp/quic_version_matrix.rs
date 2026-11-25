use std::io;
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket};
use std::sync::atomic::AtomicBool;
use std::time::{Duration, Instant};

use super::super::protect::protected_udp_bind;
use super::super::types::TransportError;
use super::{check_udp_attempt, poll_wait, udp_remaining};

pub const QUIC_MATRIX_REPETITIONS: usize = 2;

const QUIC_V1_WIRE: u32 = 0x0000_0001;
const QUIC_V2_WIRE: u32 = 0x6b33_43cf;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuicMatrixVersion {
    V1,
    V2,
}

impl QuicMatrixVersion {
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

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuicReply {
    ValidInitial,
    VersionNegotiation,
    Other,
}

pub trait QuicInitialCodec {
    fn build(&self, version: u32, sni: &str) -> Option<Vec<u8>>;
    fn classify(&self, request: &[u8], response: &[u8]) -> QuicReply;
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuicCellStage {
    ValidInitial,
    VersionNegotiationOnly,
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
    pub sequence: usize,
    pub repetition: usize,
    pub version: QuicMatrixVersion,
    pub sni: QuicMatrixSni,
    pub local_port: u16,
    pub outcome: QuicCellOutcome,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct QuicResidualCell {
    pub version: QuicMatrixVersion,
    pub local_port: u16,
    pub test: QuicCellOutcome,
    pub control: QuicCellOutcome,
    pub residual_state_suspected: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuicVersionStatus {
    Inconclusive,
    UnsupportedByEndpoint,
    TestAnswered,
    TestNotAnswered,
    TestMixed,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuicMatrixStop {
    Cancelled,
    DeadlineExceeded,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuicMatrixLimit {
    InitialStageOnly,
    LocalPortSeparationOnly,
}

pub const QUIC_MATRIX_LIMITS: &[QuicMatrixLimit] =
    &[QuicMatrixLimit::InitialStageOnly, QuicMatrixLimit::LocalPortSeparationOnly];

pub struct QuicVersionMatrixRequest<'a> {
    pub endpoint: SocketAddr,
    pub control_sni: &'a str,
    pub test_sni: &'a str,
    pub cell_timeout: Duration,
    pub cell_byte_budget: usize,
    pub total_deadline: Duration,
    pub residual: Option<QuicMatrixVersion>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct QuicVersionMatrixReport {
    pub cells: Vec<QuicMatrixCell>,
    pub residual: Option<QuicResidualCell>,
    pub stopped: Option<QuicMatrixStop>,
    pub v1: QuicVersionStatus,
    pub v2: QuicVersionStatus,
    pub version_difference: bool,
    pub limits: &'static [QuicMatrixLimit],
}

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

fn stop_reason<T>(result: Result<T, TransportError>) -> Result<Result<T, QuicMatrixStop>, TransportError> {
    match result {
        Ok(value) => Ok(Ok(value)),
        Err(TransportError::ProbeAborted(_)) => Ok(Err(QuicMatrixStop::Cancelled)),
        Err(TransportError::ScanDeadlineExceeded) => Ok(Err(QuicMatrixStop::DeadlineExceeded)),
        Err(error) => Err(error),
    }
}

#[allow(clippy::too_many_arguments)]
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
    if cell_deadline > deadline {
        return Err(TransportError::ScanDeadlineExceeded);
    }
    let initial = codec.build(version.wire(), sni).ok_or(TransportError::InvalidProbePayload)?;
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
