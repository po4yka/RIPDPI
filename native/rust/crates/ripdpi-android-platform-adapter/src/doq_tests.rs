use serde_json::Value;

use super::*;

const CONTROLS: DoqRequestControls = DoqRequestControls {
    reserve: || Some(1),
    cancel: |_| {},
    release: |_| {},
    cancellation: |id| (id == 1).then(|| Arc::new(AtomicBool::new(false))),
};

fn exchange(request_json: &str) -> String {
    super::exchange(request_json, &CONTROLS).0
}

#[test]
fn doq_control_envelopes_do_not_start_network_exchange() {
    for (request, expected) in [
        (r#"{"operation":"reserve"}"#, serde_json::json!({"requestHandle": 1})),
        (r#"{"operation":"cancel","requestHandle":1}"#, serde_json::json!({"controlOk": true})),
        (r#"{"operation":"release","requestHandle":1}"#, serde_json::json!({"controlOk": true})),
    ] {
        let response: Value = serde_json::from_str(&exchange(request)).unwrap();
        assert_eq!(response, expected);
    }
    let invalid: Value = serde_json::from_str(&exchange(r#"{"operation":"cancel","requestHandle":0}"#)).unwrap();
    assert_eq!(invalid["errorKind"], "invalid_request");
}

#[test]
fn doq_cancellation_and_deadline_win_when_operation_completes_late() {
    run_on_current_thread(async {
        let cancellation = AtomicBool::new(false);
        let cancelled = run_cancellable(Instant::now() + Duration::from_secs(1), &cancellation, async {
            cancellation.store(true, Ordering::Relaxed);
            Ok(())
        })
        .await;
        assert!(matches!(cancelled, Err(NativeDoqError { kind: NativeDoqErrorKind::Cancelled, .. })));

        let deadline = Instant::now() + Duration::from_millis(30);
        let expired = run_cancellable(deadline, &AtomicBool::new(false), async {
            // A synchronous platform operation can exhaust the deadline in one poll.
            std::thread::sleep(Duration::from_millis(50));
            Ok(())
        })
        .await;
        assert!(matches!(expired, Err(NativeDoqError { kind: NativeDoqErrorKind::Timeout, .. })));
    })
    .unwrap();
}

#[test]
fn doq_cancel_before_exchange_sends_no_datagram() {
    let listener = std::net::UdpSocket::bind(("127.0.0.1", 0)).unwrap();
    listener.set_nonblocking(true).unwrap();
    let request = NativeDoqExchangeRequest {
        request_handle: 1,
        endpoint: "127.0.0.1".into(),
        port: listener.local_addr().unwrap().port(),
        tls_server_name: "localhost".into(),
        query_base64: STANDARD.encode(build_dns_query("localhost", 0x1234)),
        timeout_ms: 6_000,
    };
    let response: Value = serde_json::from_str(&exchange_reserved(request, &AtomicBool::new(true))).unwrap();
    assert_eq!(response["errorKind"], "cancelled");
    assert_eq!(listener.recv(&mut [0; 32]).unwrap_err().kind(), io::ErrorKind::WouldBlock);
}

#[test]
fn doq_cancelled_bootstrap_keeps_its_slot_until_worker_returns() {
    static LOOKUPS: Semaphore = Semaphore::const_new(1);
    let (lookup_started, started) = tokio::sync::oneshot::channel();
    let (finish, lookup_finish) = std::sync::mpsc::channel();
    run_on_current_thread(async {
        let lookup = bounded_platform_lookup(&LOOKUPS, move || {
            lookup_started.send(()).unwrap();
            lookup_finish.recv_timeout(Duration::from_secs(2)).unwrap();
            Ok(())
        });
        let cancelled = AtomicBool::new(false);
        let result = {
            tokio::join!(run_cancellable(Instant::now() + Duration::from_secs(2), &cancelled, lookup), async {
                started.await.unwrap();
                cancelled.store(true, Ordering::Relaxed);
            })
            .0
        };
        assert!(matches!(result, Err(NativeDoqError { kind: NativeDoqErrorKind::Cancelled, .. })));
        assert!(LOOKUPS.try_acquire().is_err());
        finish.send(()).unwrap();
        drop(timeout(Duration::from_secs(2), LOOKUPS.acquire()).await.unwrap().unwrap());
    })
    .unwrap();
}

#[test]
fn doq_cancellation_ends_active_exchange_without_waiting_for_deadline() {
    let listener = std::net::UdpSocket::bind(("127.0.0.1", 0)).unwrap();
    listener.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let request = NativeDoqExchangeRequest {
        request_handle: 1,
        endpoint: "127.0.0.1".into(),
        port: listener.local_addr().unwrap().port(),
        tls_server_name: "localhost".into(),
        query_base64: STANDARD.encode(build_dns_query("localhost", 0x1234)),
        timeout_ms: 6_000,
    };
    let cancelled = AtomicBool::new(false);
    std::thread::scope(|scope| {
        let exchange = scope.spawn(|| exchange_reserved(request, &cancelled));
        let mut packet = [0; 2048];
        assert!(listener.recv(&mut packet).unwrap() >= 1200);
        let started = Instant::now();
        cancelled.store(true, Ordering::Relaxed);
        let result: Value = serde_json::from_str(&exchange.join().unwrap()).unwrap();
        assert_eq!(result["errorKind"], "cancelled");
        assert!(started.elapsed() < Duration::from_secs(2));
    });
}

#[test]
fn doq_bridge_preserves_native_error_kinds() {
    let native_errors = [
        (DoqErrorKind::Setup, "setup"),
        (DoqErrorKind::Connect, "quic"),
        (DoqErrorKind::Exchange, "quic"),
        (DoqErrorKind::Tls, "tls"),
        (DoqErrorKind::Alpn, "alpn"),
        (DoqErrorKind::ConnectTimeout, "timeout"),
        (DoqErrorKind::QueryTimeout, "timeout"),
    ];
    for (kind, expected) in native_errors {
        let error = NativeDoqError::from(EncryptedDnsError::Doq { kind, message: "failure".into() });
        let result: Value = serde_json::from_str(&response_error(error.kind, error.message)).unwrap();
        assert_eq!(result["errorKind"], expected);
    }
    let wire_error = NativeDoqError::from(EncryptedDnsError::DnsParse("short response".into()));
    let result: Value = serde_json::from_str(&response_error(wire_error.kind, wire_error.message)).unwrap();
    assert_eq!(result["errorKind"], "wire");
}

#[test]
fn doq_checks_query_size_before_resolution() {
    for bytes in [0, 11, 65_536] {
        let request = serde_json::json!({
            "requestHandle": 1,
            "endpoint": "invalid.example",
            "port": 853,
            "tlsServerName": "invalid.example",
            "queryBase64": STANDARD.encode(vec![0; bytes]),
            "timeoutMs": 6_000,
        });
        let result: Value = serde_json::from_str(&exchange(&request.to_string())).unwrap();
        assert_eq!(result["errorKind"], "invalid_request");
    }
    for bytes in [12, 65_535] {
        assert_eq!(decode_query(&STANDARD.encode(vec![0; bytes])).unwrap().len(), bytes);
    }
    assert!(decode_query("b64*").is_err());
}

#[test]
fn doq_udp_is_protected_before_bind() {
    struct UnboundSocketRequired;
    impl ripdpi_runtime_platform::protect::ProtectCallback for UnboundSocketRequired {
        fn protect(&self, fd: std::os::fd::RawFd) -> io::Result<()> {
            let local =
                nix::sys::socket::getsockname::<nix::sys::socket::SockaddrStorage>(fd).map_err(io::Error::from)?;
            let port = local
                .as_sockaddr_in()
                .map(nix::sys::socket::SockaddrIn::port)
                .or_else(|| local.as_sockaddr_in6().map(nix::sys::socket::SockaddrIn6::port))
                .expect("IP socket");
            if port != 0 {
                return Err(io::Error::new(io::ErrorKind::InvalidInput, "protect received an already bound socket"));
            }
            Ok(())
        }
    }
    ripdpi_runtime_platform::protect::register_protect_callback(std::sync::Arc::new(UnboundSocketRequired));
    let hooks = doq_connect_hooks();
    let binder = hooks.direct_udp_binder.as_ref().expect("DoQ binder");
    let results = ["0.0.0.0:0", "[::]:0"].map(|address| binder(address.parse().expect("bind address")));
    ripdpi_runtime_platform::protect::unregister_protect_callback();
    for result in results {
        assert_ne!(result.expect("protect before bind").local_addr().expect("bound").port(), 0);
    }
}

#[test]
fn cloudflare_doq_exchange_is_available_when_network_tests_enabled() {
    if std::env::var("RIPDPI_RUN_NETWORK_TESTS").as_deref() != Ok("1") {
        return;
    }
    let query = build_dns_query("example.com", 0x1234);
    let request = serde_json::json!({
        "requestHandle": 1,
        "endpoint": "1.1.1.1",
        "port": 853,
        "tlsServerName": "cloudflare-dns.com",
        "queryBase64": STANDARD.encode(query),
        "timeoutMs": 6_000,
    });

    let payload = exchange(&request.to_string());
    let response: Value = serde_json::from_str(&payload).expect("native DoQ response JSON");

    assert_eq!(response.get("error").and_then(Value::as_str), None);
    let response_bytes = STANDARD
        .decode(response.get("responseBase64").and_then(Value::as_str).expect("DoQ response body"))
        .expect("base64 response");
    assert_eq!(response_bytes[0], 0x12);
    assert_eq!(response_bytes[1], 0x34);
}

#[test]
fn bounded_run_returns_at_the_deadline_while_a_blocking_lookup_runs() {
    let started = std::time::Instant::now();
    let cancelled = AtomicBool::new(false);
    let result = run_on_current_thread(run_cancellable(
        started + Duration::from_millis(50),
        &cancelled,
        bounded_platform_lookup(&BOOTSTRAP_LOOKUPS, || {
            // Stands in for getaddrinfo on a blackholed resolver.
            std::thread::sleep(Duration::from_secs(3));
            Ok(())
        }),
    ))
    .expect("runtime");

    assert!(matches!(result, Err(NativeDoqError { kind: NativeDoqErrorKind::Timeout, .. })));
    assert!(started.elapsed() < Duration::from_secs(1), "returned after {:?}", started.elapsed());
}

fn build_dns_query(domain: &str, query_id: u16) -> Vec<u8> {
    let mut packet = Vec::with_capacity(512);
    packet.extend(query_id.to_be_bytes());
    packet.extend(0x0100u16.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    for label in domain.trim_end_matches('.').split('.') {
        packet.push(label.len() as u8);
        packet.extend(label.as_bytes());
    }
    packet.push(0);
    packet.extend(1u16.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    packet
}
