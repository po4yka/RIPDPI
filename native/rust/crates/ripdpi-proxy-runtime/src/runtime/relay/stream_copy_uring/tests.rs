use std::time::{Duration, Instant};

use super::freeze_detector::FreezeDetector;

#[test]
fn duplex_continuation_preserves_response_and_uses_fixed_buffers() {
    use std::io::{Read, Write};
    use std::net::{Ipv4Addr, Shutdown, TcpListener, TcpStream};
    use std::sync::{Arc, atomic::Ordering, mpsc};
    use std::thread;

    use crate::runtime::config::RuntimeConfig;
    use crate::runtime::relay::failure_retry::prepare_relay;
    use crate::runtime::relay::stream_copy::RelayStreamSettings;
    use crate::runtime::state::RuntimeState;
    use crate::runtime::types::RuntimeConnectionRoute;

    fn pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind owned pair");
        let first = TcpStream::connect(listener.local_addr().expect("pair address")).expect("connect pair");
        let second = listener.accept().expect("accept pair").0;
        for stream in [&first, &second] {
            stream.set_read_timeout(Some(Duration::from_secs(4))).expect("bound read");
            stream.set_write_timeout(Some(Duration::from_secs(4))).expect("bound write");
        }
        (first, second)
    }

    // This opt-in Linux test must fail on EPERM/ENOSYS or missing registration.
    // Neither the capability probe nor plain-copy fallback can satisfy it.
    let driver = Arc::new(ripdpi_io_uring::IoUringDriver::start(2, 16_384).expect("real registered io_uring driver"));
    let (mut app, mut client) = pair();
    let (upstream, mut origin) = pair();
    let cancel_upstream = upstream.try_clone().expect("keep cancellation handle");
    let target = upstream.peer_addr().expect("origin target");
    let mut config = RuntimeConfig::default();
    config.host_autolearn.enabled = true;
    let state = RuntimeState::test(config);
    let (headers_tx, headers_rx) = mpsc::sync_channel(1);
    let (result_tx, result_rx) = mpsc::sync_channel(1);
    let body: Vec<u8> = (0..1024 * 1024).map(|index| (index % 251) as u8).collect();
    let expected = body.clone();
    let server = thread::spawn(move || {
        let mut headers = Vec::new();
        while !headers.ends_with(b"\r\n\r\n") {
            let mut byte = [0];
            origin.read_exact(&mut byte).expect("request header");
            headers.push(byte[0]);
            assert!(headers.len() <= 4096);
        }
        headers_tx.send(()).expect("headers reached origin");
        let mut received = vec![0; body.len()];
        origin.read_exact(&mut received).expect("complete upload before response");
        assert_eq!(received, body);
        origin.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\n\r\n").expect("response header");
        origin.write_all(&body).expect("response exceeds the first-response buffer");
        origin.shutdown(Shutdown::Write).expect("finish response");
    });
    let relay = thread::spawn(move || {
        let result = (|| {
            let prepared = prepare_relay(
                &mut client,
                upstream,
                &state,
                target,
                RuntimeConnectionRoute { group_index: 0, attempted_mask: 1 },
                None,
                None,
            )?;
            super::relay_streams_uring(
                client,
                prepared.upstream,
                &state,
                0,
                RelayStreamSettings { group: state.relay_group(0)?, rotation_seed: None },
                prepared.session_state,
                prepared.success_host,
                &driver,
            )
        })();
        result_tx.send(result).expect("report relay completion");
    });
    app.write_all(b"POST / HTTP/1.1\r\nHost: fixture.test\r\nContent-Length: 1048576\r\n\r\n")
        .expect("write separate headers");
    headers_rx.recv_timeout(Duration::from_secs(2)).expect("origin received headers");
    app.write_all(&expected).expect("write continued body");
    app.shutdown(Shutdown::Write).expect("half-close upload");
    let mut response = Vec::new();
    let received = app.read_to_end(&mut response);
    let completed = result_rx.recv_timeout(Duration::from_secs(5));
    let _ = app.shutdown(Shutdown::Both);
    let _ = cancel_upstream.shutdown(Shutdown::Both);
    server.join().expect("join origin");
    relay.join().expect("join relay");
    received.expect("read complete response");
    let final_session = completed.expect("bounded relay completion").expect("successful relay");
    let mut expected_response = b"HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\n\r\n".to_vec();
    expected_response.extend_from_slice(&expected);
    assert_eq!(response, expected_response);
    assert!(final_session.copy_path.fixed_writes.load(Ordering::Relaxed) > 0, "no positive WRITE_FIXED CQE");
    assert_eq!(final_session.copy_path.fallbacks.load(Ordering::Relaxed), 0, "plain copy cannot prove io_uring");
}

#[test]
fn freeze_detector_stays_inactive_until_data_is_observed() {
    let mut detector = FreezeDetector::new(1, 10, 1);

    assert!(!detector.check(Instant::now() + Duration::from_millis(2)));
}

#[test]
fn freeze_detector_trips_after_configured_stalls() {
    let mut detector = FreezeDetector::new(1, 10, 2);
    detector.record_bytes(1);

    assert!(!detector.check(Instant::now() + Duration::from_millis(2)));
    assert!(detector.check(Instant::now() + Duration::from_millis(4)));
}
