use std::collections::{HashMap, HashSet};
use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use super::*;

const CONTROL: &str = "control.example.test";
const TEST: &str = "test.example.test";

/// Wire shape: long-header flags, version, a per-request nonce, then the SNI.
/// A reply is `I` or `V` followed by the exact request.
struct FakeCodec(AtomicU32);

impl QuicInitialCodec for FakeCodec {
    fn build(&self, version: u32, sni: &str) -> Option<Vec<u8>> {
        let mut packet = vec![0xc0];
        packet.extend_from_slice(&version.to_be_bytes());
        packet.extend_from_slice(&self.0.fetch_add(1, Ordering::Relaxed).to_be_bytes());
        packet.extend_from_slice(sni.as_bytes());
        Some(packet)
    }

    fn classify(&self, request: &[u8], response: &[u8]) -> QuicReply {
        match response.split_first() {
            Some((b'I', echoed)) if echoed == request => QuicReply::ValidInitial,
            Some((b'V', echoed)) if echoed == request => QuicReply::VersionNegotiation,
            _ => QuicReply::Other,
        }
    }
}

#[derive(Debug)]
struct Seen {
    peer: SocketAddr,
    version: u32,
    sni: String,
}

/// Loopback endpoint. `reply(version, sni, earlier datagrams from this peer)` returns the datagrams to send.
struct Responder {
    addr: SocketAddr,
    stop: Arc<AtomicBool>,
    worker: JoinHandle<Vec<Seen>>,
}

impl Responder {
    fn start(reply: impl Fn(u32, &str, &[&Seen]) -> Vec<Vec<u8>> + Send + 'static) -> Self {
        let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        socket.set_read_timeout(Some(Duration::from_millis(10))).unwrap();
        let addr = socket.local_addr().unwrap();
        let stop = Arc::new(AtomicBool::new(false));
        let done = Arc::clone(&stop);
        let worker = thread::spawn(move || {
            let mut seen: Vec<Seen> = Vec::new();
            let mut buf = [0; 2048];
            while !done.load(Ordering::Acquire) {
                let Ok((size, peer)) = socket.recv_from(&mut buf) else { continue };
                let request = &buf[..size];
                let version = u32::from_be_bytes(request[1..5].try_into().unwrap());
                let sni = String::from_utf8(request[9..].to_vec()).unwrap();
                let earlier = seen.iter().filter(|entry| entry.peer == peer).collect::<Vec<_>>();
                for datagram in reply(version, &sni, &earlier) {
                    let datagram = match datagram.as_slice() {
                        b"I" | b"V" => [datagram.as_slice(), request].concat(),
                        _ => datagram,
                    };
                    socket.send_to(&datagram, peer).unwrap();
                }
                seen.push(Seen { peer, version, sni });
            }
            seen
        });
        Self { addr, stop, worker }
    }

    fn finish(self) -> Vec<Seen> {
        self.stop.store(true, Ordering::Release);
        self.worker.join().unwrap()
    }
}

fn request(endpoint: SocketAddr, residual: Option<QuicMatrixVersion>) -> QuicVersionMatrixRequest<'static> {
    QuicVersionMatrixRequest {
        endpoint,
        control_sni: CONTROL,
        test_sni: TEST,
        cell_timeout: Duration::from_millis(150),
        cell_byte_budget: 4096,
        total_deadline: Duration::from_secs(5),
        residual,
    }
}

fn run(responder: &Responder, residual: Option<QuicMatrixVersion>) -> QuicVersionMatrixReport {
    run_quic_version_matrix(&request(responder.addr, residual), &FakeCodec(AtomicU32::new(0)), &AtomicBool::new(false))
        .unwrap()
}

fn answer() -> Vec<Vec<u8>> {
    vec![b"I".to_vec()]
}

fn stage(report: &QuicVersionMatrixReport, version: QuicMatrixVersion, sni: QuicMatrixSni) -> Vec<QuicCellStage> {
    report
        .cells
        .iter()
        .filter(|cell| cell.version == version && cell.sni == sni)
        .map(|cell| cell.outcome.stage)
        .collect()
}

#[test]
fn v2_version_negotiation_only_is_unsupported_by_endpoint() {
    let responder =
        Responder::start(|version, _, _| if version == QUIC_V2_WIRE { vec![b"V".to_vec()] } else { answer() });
    let report = run(&responder, None);
    responder.finish();

    assert_eq!(report.v2, QuicVersionStatus::UnsupportedByEndpoint);
    assert_eq!(report.v1, QuicVersionStatus::TestAnswered);
    assert!(!report.version_difference);
    assert_eq!(
        stage(&report, QuicMatrixVersion::V2, QuicMatrixSni::Control),
        [QuicCellStage::VersionNegotiationOnly, QuicCellStage::VersionNegotiationOnly]
    );
}

#[test]
fn control_timeout_is_inconclusive_not_blocked() {
    let responder = Responder::start(
        |version, sni, _| {
            if version == QUIC_V1_WIRE && sni == CONTROL { Vec::new() } else { answer() }
        },
    );
    let report = run(&responder, None);
    responder.finish();

    assert_eq!(
        stage(&report, QuicMatrixVersion::V1, QuicMatrixSni::Control),
        [QuicCellStage::Timeout, QuicCellStage::Timeout]
    );
    assert_eq!(report.v1, QuicVersionStatus::Inconclusive);
    assert_eq!(report.v2, QuicVersionStatus::TestAnswered);
    assert!(!report.version_difference);
}

#[test]
fn v1_test_timeout_with_v2_answer_and_valid_controls_is_a_version_difference() {
    let responder =
        Responder::start(|version, sni, _| if version == QUIC_V1_WIRE && sni == TEST { Vec::new() } else { answer() });
    let report = run(&responder, None);
    let seen = responder.finish();

    assert_eq!(report.v1, QuicVersionStatus::TestNotAnswered);
    assert_eq!(report.v2, QuicVersionStatus::TestAnswered);
    assert!(report.version_difference);
    assert_eq!(report.stopped, None);
    assert_eq!(report.limits, [QuicMatrixLimit::InitialStageOnly, QuicMatrixLimit::LocalPortSeparationOnly]);
    // Both versions went out with their RFC version numbers.
    let versions = seen.iter().map(|entry| entry.version).collect::<HashSet<_>>();
    assert_eq!(versions, HashSet::from([0x0000_0001, 0x6b33_43cf]));
}

#[test]
fn residual_disabled_runs_every_cell_on_its_own_socket_in_alternating_order() {
    let responder = Responder::start(|_, _, _| answer());
    let report = run(&responder, None);
    let seen = responder.finish();

    use QuicMatrixSni::{Control, Test};
    use QuicMatrixVersion::{V1, V2};
    let order = report.cells.iter().map(|cell| (cell.sequence, cell.version, cell.sni)).collect::<Vec<_>>();
    assert_eq!(
        order,
        [
            (0, V1, Control),
            (1, V1, Test),
            (2, V2, Control),
            (3, V2, Test),
            (4, V2, Control),
            (5, V2, Test),
            (6, V1, Control),
            (7, V1, Test),
        ]
    );
    assert_eq!(report.residual, None);
    let ports = report.cells.iter().map(|cell| cell.local_port).collect::<HashSet<_>>();
    assert_eq!(ports.len(), 8);
    let mut per_peer = HashMap::new();
    for entry in &seen {
        *per_peer.entry(entry.peer).or_insert(0) += 1;
    }
    assert_eq!(per_peer.len(), 8);
    assert!(per_peer.values().all(|count| *count == 1), "no socket sent twice: {seen:?}");
}

#[test]
fn residual_cell_shares_one_socket_and_is_reported_apart() {
    // The flow is dropped after a test SNI; fresh flows are not.
    let responder = Responder::start(|_, sni, earlier| {
        if sni == TEST || earlier.iter().any(|entry| entry.sni == TEST) { Vec::new() } else { answer() }
    });
    let report = run(&responder, Some(QuicMatrixVersion::V1));
    let seen = responder.finish();

    assert_eq!(report.cells.len(), 8);
    let residual = report.residual.expect("residual cell");
    assert_eq!(residual.test.stage, QuicCellStage::Timeout);
    assert_eq!(residual.control.stage, QuicCellStage::Timeout);
    assert!(residual.residual_state_suspected);
    assert!(report.cells.iter().all(|cell| cell.local_port != residual.local_port));
    let residual_snis =
        seen.iter().filter(|entry| entry.peer.port() == residual.local_port).map(|entry| entry.sni.as_str());
    assert_eq!(residual_snis.collect::<Vec<_>>(), [TEST, CONTROL]);
    // The residual cell does not feed the matrix verdict.
    assert_eq!(report.v1, QuicVersionStatus::TestNotAnswered);
}

#[test]
fn cancellation_during_a_cell_drops_it_and_stops() {
    let cancel = Arc::new(AtomicBool::new(false));
    let flag = Arc::clone(&cancel);
    let responder = Responder::start(move |_, _, _| {
        flag.store(true, Ordering::Release);
        Vec::new()
    });
    let report =
        run_quic_version_matrix(&request(responder.addr, None), &FakeCodec(AtomicU32::new(0)), &cancel).unwrap();
    responder.finish();

    assert_eq!(report.stopped, Some(QuicMatrixStop::Cancelled));
    assert!(report.cells.is_empty());
    assert_eq!((report.v1, report.v2), (QuicVersionStatus::Inconclusive, QuicVersionStatus::Inconclusive));
}

#[test]
fn total_deadline_stops_before_a_cell_without_its_full_window() {
    let responder = Responder::start(|_, _, _| Vec::new());
    let mut bounded = request(responder.addr, None);
    bounded.cell_timeout = Duration::from_millis(200);
    // Cells 1 and 2 fit; cell 3 would start near 400 ms and end past 500 ms.
    bounded.total_deadline = Duration::from_millis(500);
    let report = run_quic_version_matrix(&bounded, &FakeCodec(AtomicU32::new(0)), &AtomicBool::new(false)).unwrap();
    responder.finish();

    assert_eq!(report.stopped, Some(QuicMatrixStop::DeadlineExceeded));
    assert_eq!(report.cells.len(), 2);
    assert!(report.cells.iter().all(|cell| cell.outcome.stage == QuicCellStage::Timeout));
    assert_eq!(report.v1, QuicVersionStatus::Inconclusive);
}

#[test]
fn byte_budget_ends_a_cell_of_unvalidated_replies() {
    let responder = Responder::start(|_, _, _| vec![vec![0; 40]; 5]);
    let mut bounded = request(responder.addr, None);
    bounded.cell_byte_budget = 120;
    let report = run_quic_version_matrix(&bounded, &FakeCodec(AtomicU32::new(0)), &AtomicBool::new(false)).unwrap();
    responder.finish();

    let first = report.cells[0].outcome;
    assert_eq!(first.stage, QuicCellStage::Other);
    // 29-byte request: the third 40-byte reply crosses 120 bytes.
    assert_eq!((first.bytes_sent, first.bytes_received), (9 + CONTROL.len(), 120));
}

#[test]
fn late_test_flow_replies_do_not_mark_the_residual_control() {
    // Fresh flows answer at once; on the residual socket the test reply arrives late, during the control wait.
    let responder = Responder::start(|_, sni, earlier| {
        if earlier.is_empty() && sni == TEST {
            thread::sleep(Duration::from_millis(30));
            vec![b"I".to_vec(), b"I".to_vec()]
        } else {
            answer()
        }
    });
    let report = run(&responder, Some(QuicMatrixVersion::V1));
    responder.finish();

    let residual = report.residual.expect("residual cell");
    assert_eq!(residual.control.stage, QuicCellStage::ValidInitial);
    assert_eq!(residual.control.bytes_received, residual.control.bytes_sent + 1);
    assert!(!residual.residual_state_suspected);
}

#[test]
fn builder_version_fallback_is_rejected() {
    struct FallbackCodec;
    impl QuicInitialCodec for FallbackCodec {
        fn build(&self, _: u32, sni: &str) -> Option<Vec<u8>> {
            FakeCodec(AtomicU32::new(0)).build(QUIC_V1_WIRE, sni)
        }
        fn classify(&self, _: &[u8], _: &[u8]) -> QuicReply {
            QuicReply::ValidInitial
        }
    }
    let responder = Responder::start(|_, _, _| answer());
    let result = run_quic_version_matrix(&request(responder.addr, None), &FallbackCodec, &AtomicBool::new(false));
    responder.finish();

    assert!(matches!(result, Err(TransportError::InvalidProbePayload)));
}
