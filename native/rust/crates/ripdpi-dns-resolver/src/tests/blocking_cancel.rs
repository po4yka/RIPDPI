use super::*;
use std::cell::Cell;
use std::sync::mpsc;
use std::time::Instant;

#[test]
fn blocking_exchange_cancels_a_stalled_tls_handshake_and_closes_its_stream() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("DNS listener");
    let port = listener.local_addr().expect("DNS address").port();
    let (received_tx, received_rx) = mpsc::channel();
    let server = thread::spawn(move || {
        let (mut stream, _) = listener.accept().expect("DNS connection");
        stream.set_read_timeout(Some(Duration::from_secs(2))).expect("server timeout");
        let mut bytes = [0; 4096];
        assert!(stream.read(&mut bytes).expect("TLS ClientHello") > 0);
        received_tx.send(()).expect("request reached server");
        loop {
            if stream.read(&mut bytes).expect("cancel closes the pending TLS stream") == 0 {
                break;
            }
        }
    });
    let resolver = EncryptedDnsResolver::with_timeout(
        dot_fixture_endpoint(port),
        EncryptedDnsTransport::Direct,
        Duration::from_secs(10),
    )
    .expect("resolver");
    let cancelled = Cell::new(false);
    let should_cancel = || {
        if received_rx.try_recv().is_ok() {
            cancelled.set(true);
        }
        cancelled.get()
    };
    let started = Instant::now();
    let error = resolver
        .exchange_blocking_with_cancel(&build_query("fixture.test"), &should_cancel)
        .expect_err("caller cancellation");
    assert!(error.to_string().contains("cancelled"), "{error}");
    assert!(started.elapsed() < Duration::from_secs(1));
    server.join().expect("server observed EOF before resolver drop");
}

#[test]
fn blocking_exchange_cancelled_before_start_does_not_connect() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("DNS listener");
    listener.set_nonblocking(true).expect("nonblocking listener");
    let resolver = EncryptedDnsResolver::new(
        dot_fixture_endpoint(listener.local_addr().expect("DNS address").port()),
        EncryptedDnsTransport::Direct,
    )
    .expect("resolver");

    let error =
        resolver.exchange_blocking_with_cancel(&build_query("fixture.test"), &|| true).expect_err("already cancelled");

    assert!(error.to_string().contains("cancelled"), "{error}");
    assert_eq!(listener.accept().expect_err("cancelled query must not connect").kind(), io::ErrorKind::WouldBlock);
}
