//! DoT connection reuse, pipelining, and bootstrap failover (DNS-04 audit regressions).
use super::*;
use std::net::Ipv6Addr;
use std::sync::Mutex;
use std::time::Instant;

type ServerTls = StreamOwned<ServerConnection, TcpStream>;

fn fixture_tls() -> (Arc<ServerConfig>, CertificateDer<'static>) {
    let certificate = generate_simple_self_signed(vec!["fixture.test".to_string()]).expect("certificate");
    let certificate_der: CertificateDer<'static> = certificate.cert.der().clone();
    let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()));
    let config = ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("TLS versions")
        .with_no_client_auth()
        .with_single_cert(vec![certificate_der.clone()], key_der)
        .expect("server config");
    (Arc::new(config), certificate_der)
}

fn accept_tls(stream: TcpStream, config: Arc<ServerConfig>) -> io::Result<ServerTls> {
    stream.set_read_timeout(Some(Duration::from_secs(5)))?;
    let mut tls = StreamOwned::new(ServerConnection::new(config).expect("server connection"), stream);
    while tls.conn.is_handshaking() {
        tls.conn.complete_io(&mut tls.sock)?;
    }
    Ok(tls)
}

/// Answers one query on `tls` with `ip`, echoing the wire ID it received.
fn answer_one(tls: &mut ServerTls, ip: Ipv4Addr) -> io::Result<()> {
    let query = read_length_prefixed_frame(tls)?;
    write_length_prefixed_frame(tls, &build_response(&query, ip))
}

fn resolver_for(
    port: u16,
    bootstrap_ips: Vec<IpAddr>,
    timeout: Duration,
    root: CertificateDer<'static>,
) -> EncryptedDnsResolver {
    let mut endpoint = dot_fixture_endpoint(port);
    endpoint.bootstrap_ips = bootstrap_ips;
    EncryptedDnsResolver::with_extra_tls_roots(endpoint, EncryptedDnsTransport::Direct, timeout, vec![root])
        .expect("resolver builds")
}

fn query_with_id(name: &str, id: u16) -> Vec<u8> {
    let mut query = build_query(name);
    query[..2].copy_from_slice(&id.to_be_bytes());
    query
}

#[test]
fn dot_tls_stage_failure_moves_to_the_next_bootstrap_ip() {
    for bad in ["stall", "reset"] {
        let (config, root) = fixture_tls();
        let good = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("IPv4 listener");
        let port = good.local_addr().expect("addr").port();
        let bad_listener = TcpListener::bind((Ipv6Addr::LOCALHOST, port)).expect("same port on ::1");
        let good_server = thread::spawn(move || {
            let (stream, _) = good.accept().expect("accept");
            let mut tls = accept_tls(stream, config).expect("TLS");
            answer_one(&mut tls, Ipv4Addr::new(198, 18, 10, 1)).expect("answer");
        });
        let bad_server = thread::spawn(move || {
            let (mut stream, _) = bad_listener.accept().expect("accept");
            let mut hello = [0u8; 512];
            let _ = stream.read(&mut hello);
            if bad == "stall" {
                thread::sleep(Duration::from_millis(1_500));
            }
        });
        let mut endpoint = dot_fixture_endpoint(port);
        endpoint.bootstrap_ips = vec![IpAddr::V6(Ipv6Addr::LOCALHOST), IpAddr::V4(Ipv4Addr::LOCALHOST)];
        let resolver = EncryptedDnsResolver::with_health_in_scope_default_tls(
            endpoint,
            EncryptedDnsTransport::Direct,
            Duration::from_millis(500),
            vec![root],
            Some(HealthRegistry::new(Duration::from_secs(3600))),
            ResolverNetworkScope::global(),
            EncryptedDnsConnectHooks::default(),
        )
        .expect("resolver builds");

        let response = resolver.exchange_blocking(&build_query("fixture.test"));

        assert_eq!(
            extract_ip_answers(&response.unwrap_or_else(|error| panic!("{bad}: {error}"))).expect("answers"),
            vec!["198.18.10.1"],
            "{bad}"
        );
        good_server.join().expect("healthy IP served the query");
        bad_server.join().expect("bad IP thread");
        assert_eq!(
            resolver.ranked_bootstrap_ips_for_test()[0],
            IpAddr::V4(Ipv4Addr::LOCALHOST),
            "{bad}: the TLS failure ranks the bad IP last"
        );
    }
}

#[test]
fn dot_pipelines_a_burst_on_one_connection_and_matches_out_of_order_replies() {
    const BURST: u8 = 16;
    let (config, root) = fixture_tls();
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("addr").port();
    let accepts = Arc::new(AtomicUsize::new(0));
    let accepted = accepts.clone();
    let wire_ids = Arc::new(Mutex::new(Vec::new()));
    let seen_ids = wire_ids.clone();
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        accepted.fetch_add(1, Ordering::SeqCst);
        let mut tls = accept_tls(stream, config).expect("TLS");
        // Read the whole burst before answering: this only completes if the client pipelines.
        let queries: Vec<Vec<u8>> =
            (0..BURST).map(|_| read_length_prefixed_frame(&mut tls).expect("pipelined query")).collect();
        let mut replies = Vec::new();
        for query in queries.iter().rev() {
            let name = Message::from_vec(query).expect("query parses").queries[0].name.to_ascii();
            let index: u8 = name.trim_start_matches('q').split('.').next().unwrap().parse().unwrap();
            let reply = build_response(query, Ipv4Addr::new(198, 18, 9, index));
            replies.extend_from_slice(&u16::try_from(reply.len()).unwrap().to_be_bytes());
            replies.extend_from_slice(&reply);
            seen_ids.lock().unwrap().push(u16::from_be_bytes([query[0], query[1]]));
        }
        // One write: several frames reach the client in one read.
        tls.write_all(&replies).expect("replies");
        tls.flush().expect("flush");
        let _ = read_length_prefixed_frame(&mut tls);
    });
    let resolver = resolver_for(port, vec![IpAddr::V4(Ipv4Addr::LOCALHOST)], Duration::from_secs(3), root);

    let runtime = tokio::runtime::Builder::new_multi_thread().worker_threads(4).enable_all().build().unwrap();
    let results = runtime.block_on(futures::future::join_all((0..BURST).map(|index| {
        let resolver = resolver.clone();
        // Every client query carries the same ID; the connection must keep wire IDs unique.
        async move { (index, resolver.exchange(&query_with_id(&format!("q{index}.test"), 0x1234)).await) }
    })));
    drop(resolver);
    drop(runtime);
    server.join().expect("server");

    for (index, result) in results {
        let response = result.unwrap_or_else(|error| panic!("q{index}: {error}"));
        assert_eq!(&response[..2], &[0x12, 0x34], "q{index} keeps the client ID");
        assert_eq!(extract_ip_answers(&response).unwrap(), vec![format!("198.18.9.{index}")]);
    }
    assert_eq!(accepts.load(Ordering::SeqCst), 1, "the burst shares one connection");
    let mut ids = wire_ids.lock().unwrap().clone();
    ids.sort_unstable();
    ids.dedup();
    assert_eq!(ids.len(), usize::from(BURST), "wire IDs are unique on the connection");
}

/// conn 1 answers q1, then handles q2 by `mode`; a later connection answers normally.
fn reuse_server(mode: &'static str) -> (u16, CertificateDer<'static>, Arc<AtomicUsize>, thread::JoinHandle<()>) {
    let (config, root) = fixture_tls();
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("addr").port();
    let accepts = Arc::new(AtomicUsize::new(0));
    let accepted = accepts.clone();
    let handle = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        accepted.fetch_add(1, Ordering::SeqCst);
        let mut first = accept_tls(stream, config.clone()).expect("TLS");
        answer_one(&mut first, Ipv4Addr::new(198, 18, 7, 1)).expect("q1");
        let held = match mode {
            "fin" => {
                first.conn.send_close_notify();
                let _ = first.conn.complete_io(&mut first.sock);
                let _ = first.sock.shutdown(std::net::Shutdown::Both);
                None
            }
            "foreign-id" => {
                let query = read_length_prefixed_frame(&mut first).expect("q2");
                let mut reply = build_response(&query, Ipv4Addr::new(198, 18, 7, 9));
                reply[0] ^= 0x5a;
                write_length_prefixed_frame(&mut first, &reply).expect("foreign reply");
                Some(first)
            }
            "blackhole" => {
                let _ = read_length_prefixed_frame(&mut first);
                Some(first)
            }
            other => unreachable!("{other}"),
        };
        let (stream, _) = listener.accept().expect("accept fresh");
        accepted.fetch_add(1, Ordering::SeqCst);
        let mut fresh = accept_tls(stream, config).expect("TLS");
        answer_one(&mut fresh, Ipv4Addr::new(198, 18, 7, 2)).expect("q2 on fresh connection");
        drop(held);
    });
    (port, root, accepts, handle)
}

#[test]
fn dot_reused_connection_failures_reconnect_quickly() {
    let timeout = Duration::from_millis(600);
    for mode in ["fin", "foreign-id", "blackhole"] {
        let (port, root, accepts, server) = reuse_server(mode);
        let resolver = resolver_for(port, vec![IpAddr::V4(Ipv4Addr::LOCALHOST)], timeout, root);
        let first = resolver.exchange_blocking(&build_query("first.test")).expect("q1");
        assert_eq!(extract_ip_answers(&first).unwrap(), vec!["198.18.7.1"]);
        // Idle for longer than one timeout, as after a silent network change.
        thread::sleep(if mode == "blackhole" {
            timeout + Duration::from_millis(100)
        } else {
            Duration::from_millis(100)
        });

        let started = Instant::now();
        let second = resolver.exchange_blocking(&build_query("second.test"));
        let elapsed = started.elapsed();
        server.join().expect("server");

        let second = second.unwrap_or_else(|error| panic!("{mode}: {error}"));
        assert_eq!(extract_ip_answers(&second).unwrap(), vec!["198.18.7.2"], "{mode}");
        assert_eq!(accepts.load(Ordering::SeqCst), 2, "{mode}");
        assert!(elapsed < timeout, "{mode}: recovery took {elapsed:?}");
    }
}

#[test]
fn dot_lost_reply_keeps_a_live_connection_and_drops_the_late_reply() {
    let (config, root) = fixture_tls();
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    let port = listener.local_addr().expect("addr").port();
    let accepts = Arc::new(AtomicUsize::new(0));
    let accepted = accepts.clone();
    let server = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept");
        accepted.fetch_add(1, Ordering::SeqCst);
        let mut tls = accept_tls(stream, config).expect("TLS");
        let lost = read_length_prefixed_frame(&mut tls).expect("lost query");
        let answered = read_length_prefixed_frame(&mut tls).expect("answered query");
        write_length_prefixed_frame(&mut tls, &build_response(&answered, Ipv4Addr::new(198, 18, 11, 2))).unwrap();
        let next = read_length_prefixed_frame(&mut tls).expect("query after the timeout");
        // The late reply to the timed-out query arrives first and must be dropped.
        write_length_prefixed_frame(&mut tls, &build_response(&lost, Ipv4Addr::new(198, 18, 11, 1))).unwrap();
        write_length_prefixed_frame(&mut tls, &build_response(&next, Ipv4Addr::new(198, 18, 11, 3))).unwrap();
        let _ = read_length_prefixed_frame(&mut tls);
    });
    let timeout = Duration::from_millis(500);
    let resolver = resolver_for(port, vec![IpAddr::V4(Ipv4Addr::LOCALHOST)], timeout, root);
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();

    let (lost, answered) = runtime.block_on(async {
        let lost_query = build_query("lost.test");
        let lost = resolver.exchange(&lost_query);
        let answered = async {
            tokio::time::sleep(Duration::from_millis(50)).await;
            resolver.exchange(&build_query("answered.test")).await
        };
        tokio::join!(lost, answered)
    });
    let next = runtime.block_on(resolver.exchange(&build_query("next.test")));
    drop(resolver);
    drop(runtime);
    server.join().expect("server");

    assert!(lost.is_err(), "the unanswered query times out");
    assert_eq!(extract_ip_answers(&answered.expect("answered")).unwrap(), vec!["198.18.11.2"]);
    assert_eq!(extract_ip_answers(&next.expect("next")).unwrap(), vec!["198.18.11.3"]);
    assert_eq!(accepts.load(Ordering::SeqCst), 1, "a connection that still answers is kept");
}
