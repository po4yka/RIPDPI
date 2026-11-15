use std::io::Read;
use std::net::TcpListener;
use std::thread;

use ripdpi_dns_resolver::EncryptedDnsProtocol;

use super::*;

#[test]
fn strategy_dns_exchange_honors_deadline_and_cancellation_during_io() {
    use std::cell::Cell;
    use std::sync::mpsc;
    use std::time::Instant;

    use ripdpi_diagnostics_contracts::util::with_scan_io_deadline;

    for cancel_during_exchange in [false, true] {
        let listener = TcpListener::bind(("127.0.0.1", 0)).expect("bind silent DoT fixture");
        listener.set_nonblocking(true).expect("bound fixture's accept wait");
        let port = listener.local_addr().expect("fixture address").port();
        let (received_tx, received_rx) = mpsc::channel();
        let server = thread::spawn(move || {
            let accept_deadline = Instant::now() + Duration::from_secs(2);
            let mut socket = loop {
                match listener.accept() {
                    Ok((socket, _)) => break socket,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        assert!(Instant::now() < accept_deadline, "DoT did not connect to its fixture");
                        thread::sleep(Duration::from_millis(5));
                    }
                    Err(error) => panic!("DoT fixture accept failed: {error}"),
                }
            };
            socket.set_nonblocking(false).expect("blocking fixture socket");
            socket.set_read_timeout(Some(Duration::from_secs(2))).expect("socket timeout");
            let mut bytes = [0; 4096];
            assert!(socket.read(&mut bytes).expect("receive ClientHello") > 0);
            received_tx.send(()).expect("signal accepted connection");
            while socket.read(&mut bytes).expect("DNS exchange closed the stream") != 0 {}
        });
        let endpoint = EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Dot,
            resolver_id: None,
            host: "127.0.0.1".to_string(),
            port,
            tls_server_name: Some("fixture.test".to_string()),
            bootstrap_ips: vec!["127.0.0.1".parse().expect("loopback address")],
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
            odoh: None,
        };
        let cancelled = Cell::new(false);
        let should_cancel = || {
            if cancel_during_exchange && received_rx.try_recv().is_ok() {
                cancelled.set(true);
            }
            cancelled.get()
        };
        let started = Instant::now();
        let deadline = (!cancel_during_exchange).then(|| started + Duration::from_millis(500));
        let error = with_scan_io_deadline(deadline, || {
            resolve_via_encrypted_dns_with_cancel(
                "probe.fixture.test",
                endpoint,
                &TransportConfig::Direct { route_experiment: None },
                &should_cancel,
            )
        })
        .expect_err("silent resolver did not complete");

        assert!(started.elapsed() < Duration::from_secs(2), "{error}");
        let expected_error = if cancel_during_exchange { "cancelled" } else { "timed out" };
        assert!(error.contains(expected_error), "{error}");
        server.join().expect("fixture observed the socket close");
    }
}
