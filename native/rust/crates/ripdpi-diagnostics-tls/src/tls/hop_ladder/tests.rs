use super::*;
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::net::{Ipv4Addr, TcpListener, TcpStream};

use AttemptOutcome::{Rst, ServerHello, Silence, TlsAlert};
use SniRole::{Control, Test};

type Script = dyn Fn(SniRole, u8, u8) -> Result<AttemptOutcome, AttemptError>;

/// Answers from a script keyed by (role, ttl, nth attempt in that cell) and records the call order.
struct FakeProbe<'a> {
    script: &'a Script,
    calls: RefCell<Vec<(SniRole, u8)>>,
    cells: HashMap<(u8, bool), u8>,
}

impl<'a> FakeProbe<'a> {
    fn new(script: &'a Script) -> Self {
        Self { script, calls: RefCell::new(Vec::new()), cells: HashMap::new() }
    }
}

impl LadderProbe for FakeProbe<'_> {
    fn attempt(
        &mut self,
        role: SniRole,
        ttl: u8,
        _window: Duration,
        _should_abort: &dyn Fn() -> bool,
    ) -> Result<AttemptOutcome, AttemptError> {
        self.calls.borrow_mut().push((role, ttl));
        let nth = self.cells.entry((ttl, role == Test)).or_default();
        let result = (self.script)(role, ttl, *nth);
        *nth += 1;
        result
    }
}

/// Spec scenario: test RST from k = 5, both silent at k = 4, control ServerHello at k = 9.
fn spec_path(role: SniRole, ttl: u8, _nth: u8) -> Result<AttemptOutcome, AttemptError> {
    Ok(match (role, ttl) {
        (Control, 9..) => ServerHello,
        (Test, 5..) => Rst,
        _ => Silence,
    })
}

fn run(probe: &mut FakeProbe<'_>) -> LadderReport {
    run_ladder(probe, LadderConfig::default(), &|| false, &|| false)
}

#[test]
fn test_reaction_before_the_server_gives_the_spec_interval() {
    let mut probe = FakeProbe::new(&spec_path);
    let report = run(&mut probe);

    assert_eq!(report.verdict, LadderVerdict::Divergence { first_hop: 5, last_hop: 5 });
    assert_eq!(report.path_length, Some(9));
    assert!(report.limits.is_empty(), "{:?}", report.limits);
    assert_eq!(report.stop, LadderStop::Completed);
    // 9 TTLs x 2 repetitions x 2 SNIs, ending after the TTL at which the control reached the server.
    assert_eq!(report.attempts.len(), 36);
    assert_eq!(&probe.calls.borrow()[..5], &[(Control, 1), (Test, 1), (Control, 1), (Test, 1), (Control, 2)]);
    assert_eq!(report.attempts.last().map(|attempt| (attempt.ttl, attempt.repetition)), Some((9, 1)));
}

#[test]
fn reaction_first_seen_at_the_path_length_is_inconclusive() {
    let script = |role, ttl, _| {
        Ok(match (role, ttl) {
            (Control, 9..) => ServerHello,
            (Test, 9..) => TlsAlert,
            _ => Silence,
        })
    };
    let mut probe = FakeProbe::new(&script);
    let report = run(&mut probe);

    assert_eq!(report.verdict, LadderVerdict::Inconclusive);
    assert_eq!(report.path_length, Some(9));
}

#[test]
fn network_change_withdraws_the_interval_and_keeps_the_attempts() {
    let mut probe = FakeProbe::new(&spec_path);
    let calls = Cell::new(0);
    let report = run_ladder(&mut probe, LadderConfig::default(), &|| false, &|| {
        calls.set(calls.get() + 1);
        // The last attempt of the spec scenario, after the control reached the server at k = 9.
        calls.get() == 36
    });

    assert_eq!(report.verdict, LadderVerdict::Inconclusive);
    assert_eq!(report.path_length, None);
    assert_eq!(report.limits, vec![LadderLimit::NetworkChanged]);
    assert_eq!(report.stop, LadderStop::NetworkChanged);
    assert_eq!(report.attempts.len(), 36);
}

#[test]
fn test_server_hello_before_the_control_path_length_is_no_divergence() {
    let script = |role, ttl, _| {
        Ok(match (role, ttl) {
            (Control, 7..) | (Test, 6..) => ServerHello,
            _ => Silence,
        })
    };
    let mut probe = FakeProbe::new(&script);
    let report = run(&mut probe);

    assert_eq!(report.verdict, LadderVerdict::Inconclusive);
    assert_eq!(report.path_length, Some(7));
    assert_eq!(report.limits, vec![LadderLimit::InconsistentRepetitions]);
}

#[test]
fn cancellation_stops_and_keeps_recorded_attempts() {
    let mut probe = FakeProbe::new(&spec_path);
    let checks = Cell::new(0);
    let report = run_ladder(
        &mut probe,
        LadderConfig::default(),
        &|| {
            checks.set(checks.get() + 1);
            checks.get() > 10
        },
        &|| false,
    );

    assert_eq!(report.stop, LadderStop::Cancelled);
    assert_eq!(report.attempts.len(), 10);
    assert_eq!(probe.calls.borrow().len(), 10);
    assert_eq!(report.verdict, LadderVerdict::Inconclusive);
    assert!(!report.limits.contains(&LadderLimit::ControlNeverReachedServer));
}

#[test]
fn abort_inside_an_attempt_drops_only_that_attempt() {
    let script = |role, ttl, nth| {
        if ttl == 3 && nth == 0 && role == Test { Err(AttemptError::Aborted) } else { spec_path(role, ttl, nth) }
    };
    let mut probe = FakeProbe::new(&script);
    let report = run(&mut probe);

    assert_eq!(report.stop, LadderStop::Cancelled);
    // Two full TTLs plus the control attempt at k = 3.
    assert_eq!(report.attempts.len(), 9);
}

#[test]
fn ttl_setter_failure_is_unsupported_without_hops() {
    let script = |_, _, _| Err(AttemptError::Unsupported(UnsupportedReason::TtlSetter));
    let mut probe = FakeProbe::new(&script);
    let report = run(&mut probe);

    assert_eq!(report.verdict, LadderVerdict::Unsupported(UnsupportedReason::TtlSetter));
    assert_eq!(report.path_length, None);
    assert!(report.limits.is_empty());
    assert_eq!(probe.calls.borrow().len(), 1);
}

#[test]
fn control_that_never_reaches_the_server_runs_to_the_maximum_ttl() {
    let script = |role, ttl, _| Ok(if role == Test && ttl >= 5 { Rst } else { Silence });
    let mut probe = FakeProbe::new(&script);
    let report = run(&mut probe);

    assert_eq!(report.verdict, LadderVerdict::Inconclusive);
    assert_eq!(report.limits, vec![LadderLimit::ControlNeverReachedServer]);
    assert_eq!(report.attempts.len(), 32 * 2 * 2);
}

#[test]
fn near_client_server_hello_control_reaction_and_inconsistent_repetitions_are_limits() {
    let script = |role, ttl, nth| {
        Ok(match (role, ttl, nth) {
            (Control, 1, _) => Rst,
            (Test, 1, _) => Rst,
            (Control, 2, _) => ServerHello,
            (Test, 2, 0) => Rst,
            _ => Silence,
        })
    };
    let mut probe = FakeProbe::new(&script);
    let report = run(&mut probe);

    assert_eq!(report.path_length, Some(2));
    assert_eq!(report.verdict, LadderVerdict::Inconclusive);
    assert_eq!(
        report.limits,
        vec![
            LadderLimit::ControlReactsLikeTest,
            LadderLimit::ServerHelloNearClient,
            LadderLimit::InconsistentRepetitions
        ]
    );
}

#[test]
fn interval_starts_at_hop_one_without_an_earlier_silent_pair() {
    let script = |role, ttl, _| {
        Ok(match (role, ttl) {
            (Control, 4..) => ServerHello,
            (Test, _) => Rst,
            _ => Silence,
        })
    };
    let mut probe = FakeProbe::new(&script);
    assert_eq!(run(&mut probe).verdict, LadderVerdict::Divergence { first_hop: 1, last_hop: 1 });
}

#[test]
fn failed_handshakes_below_the_reaction_do_not_narrow_the_interval() {
    let script = |role, ttl, _| {
        Ok(match (role, ttl) {
            (_, 1 | 2) => AttemptOutcome::HandshakeFailed,
            (Control, 6..) => ServerHello,
            (Test, 3..) => Rst,
            _ => Silence,
        })
    };
    let mut probe = FakeProbe::new(&script);
    let report = run(&mut probe);

    assert_eq!(report.verdict, LadderVerdict::Divergence { first_hop: 1, last_hop: 3 });
    assert_eq!(report.path_length, Some(6));
}

#[test]
fn deadline_that_cannot_fit_one_window_records_nothing() {
    let mut probe = FakeProbe::new(&spec_path);
    let config = LadderConfig { attempt_window: Duration::from_secs(1), total_deadline: Duration::ZERO };
    let report = run_ladder(&mut probe, config, &|| false, &|| false);

    assert_eq!(report.stop, LadderStop::DeadlineExceeded);
    assert!(report.attempts.is_empty());
    assert!(report.limits.is_empty());
}

/// Run one real attempt against a loopback peer; `respond` gets the accepted socket after the ClientHello.
fn loopback_attempt(
    window: Duration,
    should_abort: &dyn Fn() -> bool,
    respond: impl FnOnce(TcpStream) + Send + 'static,
) -> Result<AttemptOutcome, AttemptError> {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let mut probe = SocketLadderProbe::new(listener.local_addr().unwrap(), "control.example", "test.example").unwrap();
    let server = std::thread::spawn(move || {
        let (mut peer, _) = listener.accept().unwrap();
        peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let mut hello = [0u8; 2048];
        let read = peer.read(&mut hello).unwrap();
        assert_eq!(&hello[..2], &[0x16, 0x03], "a TLS handshake record");
        assert!(hello[..read].windows(12).any(|window| window == b"test.example"), "the test SNI");
        respond(peer);
    });
    let outcome = probe.attempt(Test, 7, window, should_abort);
    server.join().unwrap();
    outcome
}

#[test]
fn loopback_responses_are_classified() {
    let window = Duration::from_secs(2);
    let server_hello = loopback_attempt(window, &|| false, |mut peer| {
        peer.write_all(&[0x16, 0x03, 0x03, 0x00, 0x04, 0x02, 0, 0, 0]).unwrap();
    });
    assert_eq!(server_hello, Ok(ServerHello));
    let alert =
        loopback_attempt(window, &|| false, |mut peer| peer.write_all(&[0x15, 0x03, 0x03, 0, 2, 2, 40]).unwrap());
    assert_eq!(alert, Ok(TlsAlert));
    let fin = loopback_attempt(window, &|| false, |peer| peer.shutdown(std::net::Shutdown::Write).unwrap());
    assert_eq!(fin, Ok(AttemptOutcome::Fin));
    let rst = loopback_attempt(window, &|| false, |peer| {
        set_abortive_close(&peer).unwrap();
        drop(peer);
    });
    assert_eq!(rst, Ok(Rst));
}

/// Silence lasts the whole window; the close afterwards is abortive (the peer sees a reset, not a FIN).
#[test]
fn silent_peer_gets_an_abortive_close_after_the_window() {
    let window = Duration::from_millis(200);
    let started = Instant::now();
    let outcome = loopback_attempt(window, &|| false, |mut peer| {
        let error = peer.read(&mut [0u8; 1]).unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::ConnectionReset);
    });
    assert_eq!(outcome, Ok(Silence));
    assert!(started.elapsed() >= window);
}

#[test]
fn cancellation_inside_the_window_closes_the_socket() {
    let started = Instant::now();
    let should_abort = move || started.elapsed() > Duration::from_millis(100);
    let outcome = loopback_attempt(Duration::from_secs(5), &should_abort, |mut peer| {
        let error = peer.read(&mut [0u8; 1]).unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::ConnectionReset);
    });
    assert_eq!(outcome, Err(AttemptError::Aborted));
    assert!(started.elapsed() < Duration::from_secs(2));
}

#[test]
fn invalid_sni_is_rejected_before_any_socket() {
    let target = SocketAddr::from((Ipv4Addr::LOCALHOST, 9));
    for (control, test) in
        [("control.example", "not a name"), ("control.example", "192.0.2.1"), ("same.example", "SAME.example")]
    {
        assert!(
            matches!(SocketLadderProbe::new(target, control, test), Err(LadderSetupError::InvalidSni(_))),
            "{control} / {test}"
        );
    }
}

#[test]
fn response_prefixes_classify_once_complete() {
    assert_eq!(classify_response(&[0x16, 0x03, 0x03, 0, 4]), None);
    assert_eq!(classify_response(&[0x16, 0x03, 0x03, 0, 4, 0x02]), Some(ServerHello));
    assert_eq!(classify_response(&[0x15, 0x03, 0x01, 0, 2, 2]), Some(TlsAlert));
    assert_eq!(classify_response(b"HTTP/1.1 403"), Some(AttemptOutcome::Other));
}
