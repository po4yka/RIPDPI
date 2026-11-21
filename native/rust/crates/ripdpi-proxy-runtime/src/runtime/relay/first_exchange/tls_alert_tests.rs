use super::tests::pair;
use super::*;
use crate::runtime::config::RuntimeConfig;
use crate::runtime::payload::RUNTIME_DEFAULT_FAKE_TLS;
use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;
use std::io::{self, Read, Write};
use std::sync::Arc;
use std::time::{Duration, Instant};

fn independent_tls_flight(
    version: &'static rustls::SupportedProtocolVersion,
    warning: bool,
    record_split: Option<usize>,
    enable_sni: bool,
) -> (Vec<u8>, Vec<u8>) {
    use rustls::pki_types::PrivatePkcs8KeyDer;

    let cert = rcgen::generate_simple_self_signed(vec!["localhost".to_string()]).expect("test certificate");
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let server_config = rustls::ServerConfig::builder_with_provider(provider.clone())
        .with_protocol_versions(&[version])
        .expect("server protocol")
        .with_no_client_auth()
        .with_single_cert(
            vec![cert.cert.der().clone()],
            PrivatePkcs8KeyDer::from(cert.signing_key.serialize_der()).into(),
        )
        .expect("test server certificate");
    let mut roots = rustls::RootCertStore::empty();
    roots.add(cert.cert.der().clone()).expect("test root");
    let mut client_config = rustls::ClientConfig::builder_with_provider(provider)
        .with_protocol_versions(&[version])
        .expect("client protocol")
        .with_root_certificates(roots)
        .with_no_client_auth();
    client_config.enable_sni = enable_sni;
    let mut server = rustls::ServerConnection::new(Arc::new(server_config)).expect("TLS server");
    let mut client = rustls::ClientConnection::new(Arc::new(client_config), "localhost".try_into().expect("name"))
        .expect("TLS client");
    let mut request = Vec::new();
    client.write_tls(&mut request).expect("ClientHello bytes");
    server.read_tls(&mut io::Cursor::new(&request)).expect("server input");
    server.process_new_packets().expect("valid ClientHello");
    assert_eq!(server.server_name().is_some(), enable_sni, "independent peer observes the requested SNI mode");
    let mut response = if warning { vec![0x15, 0x03, 0x03, 0, 2, 1, 112] } else { Vec::new() };
    server.write_tls(&mut response).expect("ServerHello flight");
    if let Some(split) = record_split {
        let start = if warning { 7 } else { 0 };
        assert_eq!(response[start], 0x16);
        let end = start + 5 + usize::from(u16::from_be_bytes([response[start + 3], response[start + 4]]));
        let payload = &response[start + 5..end];
        assert!(split > 0 && split < payload.len());
        let mut fragmented = response[..start].to_vec();
        for fragment in [&payload[..split], &payload[split..]] {
            fragmented.extend([0x16, response[start + 1], response[start + 2]]);
            fragmented.extend(u16::try_from(fragment.len()).expect("TLS record length").to_be_bytes());
            fragmented.extend(fragment);
        }
        fragmented.extend(&response[end..]);
        response = fragmented;
    }
    client.read_tls(&mut io::Cursor::new(&response)).expect("client input");
    client.process_new_packets().expect("independent peer accepts flight");
    for _ in 0..5 {
        let mut flight = Vec::new();
        client.write_tls(&mut flight).expect("client continuation");
        if !flight.is_empty() {
            server.read_tls(&mut io::Cursor::new(&flight)).expect("server continuation");
            server.process_new_packets().expect("server handshake");
        }
        flight.clear();
        server.write_tls(&mut flight).expect("server continuation");
        if !flight.is_empty() {
            client.read_tls(&mut io::Cursor::new(&flight)).expect("client continuation");
            client.process_new_packets().expect("client handshake");
        }
        if !client.is_handshaking() && !server.is_handshaking() {
            break;
        }
    }
    assert!(!client.is_handshaking() && !server.is_handshaking(), "independent handshake completed");
    (request, response)
}

#[test]
fn late_tcp_freeze_does_not_pin_the_strategy_after_forwarding_an_accepted_response() {
    use crate::runtime::relay::RelayFlow;
    use crate::runtime::routing::select_route;
    use crate::runtime::types::RuntimeConnectionRoute;
    use ripdpi_proxy_runtime_adapter::model::config::DesyncGroup;
    use std::sync::mpsc;

    let mut config = RuntimeConfig::default();
    config.groups.push(DesyncGroup::new(1));
    config.adaptive.cache_ttl = 60;
    config.host_autolearn.enabled = true;
    config.timeouts.freeze_window_ms = 40;
    config.timeouts.freeze_min_bytes = 512;
    config.timeouts.freeze_max_stalls = 2;
    let state = RuntimeState::test(config);
    let (client, mut app) = pair();
    let (upstream, mut origin) = pair();
    let target = upstream.peer_addr().expect("owned origin");
    for socket in [&app, &origin] {
        socket.set_read_timeout(Some(Duration::from_secs(3))).expect("bounded reads");
    }
    let (request, response) = independent_tls_flight(&rustls::version::TLS12, false, None, true);
    let partial = [0x17, 0x03, 0x03, 0x00, 0x0a, 0x11];
    app.write_all(&request).expect("application ClientHello");

    let selected = || select_route(&state, target, None, None, true).expect("next route").group_index;
    std::thread::scope(|workers| {
        let (received, proceed) = mpsc::sync_channel(1);
        let flight = response.as_slice();
        let request = request.as_slice();
        let origin = workers.spawn(move || {
            let mut incoming = vec![0; request.len()];
            origin.read_exact(&mut incoming).expect("initial request forwarded");
            assert_eq!(incoming, request);
            origin.write_all(flight).expect("send independent TLS flight");
            proceed.recv_timeout(Duration::from_secs(3)).expect("first response visible to app");
            origin.write_all(&partial).expect("server sends an incomplete record after the first response");
            assert_eq!(origin.read(&mut [0]).expect("relay closes the stalled upstream"), 0);
        });
        let app = workers.spawn(move || {
            let mut bytes = vec![0; flight.len()];
            app.read_exact(&mut bytes).expect("accepted response before relay completion");
            assert_eq!(bytes, flight);
            assert_eq!(selected(), 0, "an accepted response cannot pin the route before completion");
            received.send(()).expect("allow the late record");
            let mut bytes = [0; 6];
            app.read_exact(&mut bytes).expect("partial data is forwarded without waiting for completion");
            assert_eq!(bytes, partial);
            assert_eq!(
                app.read(&mut [0]).expect_err("a frozen relay resets the connection").kind(),
                io::ErrorKind::ConnectionReset
            );
        });
        let error = crate::runtime::relay::relay(
            client,
            upstream,
            &state,
            target,
            RuntimeConnectionRoute { group_index: 1, attempted_mask: 3, route_advances: 1 },
            None,
            RelayFlow::default(),
            None,
        )
        .expect_err("late freeze");
        assert_eq!(error.kind(), io::ErrorKind::TimedOut);
        app.join().expect("app worker");
        origin.join().expect("origin worker");
    });
    assert_eq!(selected(), 0, "a later transport freeze must leave the route cache unchanged");
}

#[test]
fn dpi38_tls12_warning_survives_each_first_record_split() {
    for record_split in [None, Some(1), Some(38)] {
        let (request, response) = independent_tls_flight(&rustls::version::TLS12, true, record_split, true);
        let first_handshake_end = 12 + usize::from(u16::from_be_bytes([response[10], response[11]]));
        let split_limit = first_handshake_end + usize::from(record_split.is_some());
        for split in 1..split_limit {
            let mut config = RuntimeConfig::default();
            config.timeouts.partial_timeout_ms = 1_000;
            let state = RuntimeState::test(config);
            let (client, mut app) = pair();
            let (mut upstream, mut origin) = pair();
            upstream.set_read_timeout(Some(Duration::from_secs(1))).expect("bounded test read");
            let target = upstream.peer_addr().expect("target");
            origin.write_all(&response[..split]).expect("response prefix");
            app.write_all(b"x").expect("owned client continuation");
            let FirstResponse::Continuation(reader) = read_first_response(
                &state,
                target,
                None,
                &mut upstream,
                &request,
                Some(&client),
                FirstResponseReader::new(&state, &request),
            )
            .expect("read split response") else {
                panic!("TLS 1.2 warning or partial ServerHello must wait at split {split}");
            };
            origin.write_all(&response[split..]).expect("response remainder");
            let FirstResponse::Forward(mut bytes, _) =
                read_first_response(&state, target, None, &mut upstream, &request, None, *reader)
                    .expect("complete split response")
            else {
                panic!("valid independent TLS 1.2 flight must be forwarded at split {split}");
            };
            assert_eq!(bytes, response[..bytes.len()]);
            let mut rest = vec![0; response.len() - bytes.len()];
            upstream.read_exact(&mut rest).expect("remaining bytes stay unread");
            bytes.extend(rest);
            assert_eq!(bytes, response);
        }
    }
}

#[test]
fn dpi38_alert_window_includes_the_initial_response_wait() {
    let mut config = RuntimeConfig::default();
    config.timeouts.timeout_ms = 400;
    let state = RuntimeState::test(config);
    let (mut upstream, mut origin) = pair();
    let target = upstream.peer_addr().expect("target");
    let (release, released) = std::sync::mpsc::channel();
    let origin_worker = std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(300));
        origin.write_all(b"\x15\x03\x03\x00\x02\x01\x70").expect("delayed warning");
        let _ = released.recv_timeout(Duration::from_secs(2));
    });
    let started = Instant::now();
    let result = read_first_response(
        &state,
        target,
        None,
        &mut upstream,
        RUNTIME_DEFAULT_FAKE_TLS,
        None,
        FirstResponseReader::new(&state, RUNTIME_DEFAULT_FAKE_TLS),
    );
    let elapsed = started.elapsed();
    let _ = release.send(());
    origin_worker.join().expect("owned origin worker");
    let FirstResponse::Neutral(bytes) = result.expect("absolute observation deadline") else {
        panic!("a warning that outlasts the original window stays inconclusive");
    };
    assert_eq!(bytes, b"\x15\x03\x03\x00\x02\x01\x70");
    assert!(elapsed < Duration::from_millis(600), "the first chunk started a second 400ms window");
}

#[test]
fn dpi38_warning_is_not_suppressed_by_a_tls13_server_hello() {
    let (request, response) = independent_tls_flight(&rustls::version::TLS13, false, None, true);
    let response = [b"\x15\x03\x03\x00\x02\x01\x70".as_slice(), &response].concat();
    let state = RuntimeState::test(RuntimeConfig::default());
    let failure = state
        .classify_response_failure("127.0.0.1:443".parse().expect("target"), &request, &response, None)
        .expect("TLS 1.3 error alert remains a failure despite warning level");
    assert_eq!(failure.class.as_str(), "tls_alert");
}

#[test]
fn dpi38_tls12_alert_messages_follow_wire_order_before_closure() {
    let (request, flight) = independent_tls_flight(&rustls::version::TLS12, false, None, true);
    for prefix in [vec![0x15, 3, 3, 0, 4, 2, 40, 1, 0], vec![0x15, 3, 3, 0, 4, 1, 112, 2, 40]] {
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let config = rustls::ClientConfig::builder_with_provider(provider)
            .with_protocol_versions(&[&rustls::version::TLS12])
            .expect("TLS 1.2 oracle")
            .with_root_certificates(rustls::RootCertStore::empty())
            .with_no_client_auth();
        let mut oracle = rustls::ClientConnection::new(Arc::new(config), "localhost".try_into().expect("name"))
            .expect("independent client");
        oracle.write_tls(&mut Vec::new()).expect("start independent handshake");
        oracle.read_tls(&mut io::Cursor::new(&prefix)).expect("raw alert bytes");
        assert!(
            matches!(
                oracle.process_new_packets(),
                Err(rustls::Error::InvalidMessage(rustls::InvalidMessage::TrailingData("AlertMessagePayload")))
            ),
            "rustls rejects coalesced alerts instead of completing a handshake"
        );
        // RFC 5246 permits coalescing; warning 112 is nonfatal, fatal 40 ends the flight.
        let response = [prefix, flight.clone()].concat();
        let state = RuntimeState::test(RuntimeConfig::default());
        let (mut upstream, mut origin) = pair();
        origin.write_all(&response).expect("original source records");
        let target = upstream.peer_addr().expect("target");
        upstream.set_read_timeout(Some(Duration::from_secs(2))).expect("bounded oracle read");
        let FirstResponse::Failure { failure, response_bytes: Some(mut bytes) } = read_first_response(
            &state,
            target,
            None,
            &mut upstream,
            &request,
            None,
            FirstResponseReader::new(&state, &request),
        )
        .expect("classify actual alert records") else {
            panic!("fatal before close_notify must not be hidden by a warning");
        };
        assert_eq!(failure.class.as_str(), "tls_alert");
        assert!(failure.evidence.tags.iter().any(|tag| tag == "alert=handshake_failure"));
        assert_eq!(bytes, response[..bytes.len()]);
        let mut rest = vec![0; response.len() - bytes.len()];
        upstream.read_exact(&mut rest).expect("unread source bytes");
        bytes.extend(rest);
        assert_eq!(bytes, response);
    }
}

#[test]
fn dpi38_tls12_record_fragmented_warning_retains_exact_flight() {
    let (request, flight) = independent_tls_flight(&rustls::version::TLS12, false, None, true);
    // RFC 5246 section 6.2.1 permits the (warning, unrecognized_name) message across records.
    let response = [b"\x15\x03\x03\x00\x01\x01\x15\x03\x03\x00\x01\x70".as_slice(), &flight].concat();
    let state = RuntimeState::test(RuntimeConfig::default());
    let (mut upstream, mut origin) = pair();
    upstream.set_read_timeout(Some(Duration::from_secs(2))).expect("bounded oracle read");
    origin.write_all(&response).expect("fragmented alert and ServerHello");
    let target = upstream.peer_addr().expect("target");
    let FirstResponse::Forward(mut bytes, _) = read_first_response(
        &state,
        target,
        None,
        &mut upstream,
        &request,
        None,
        FirstResponseReader::new(&state, &request),
    )
    .expect("observe exact source bytes") else {
        panic!("complete TLS 1.2 warning before a valid ServerHello is not a DPI failure");
    };
    assert_eq!(bytes, response[..bytes.len()]);
    let mut rest = vec![0; response.len() - bytes.len()];
    upstream.read_exact(&mut rest).expect("unread source bytes");
    bytes.extend(rest);
    assert_eq!(bytes, response);
}

#[test]
fn dpi38_partial_outbound_header_keeps_alert_observation() {
    let (request, response) = independent_tls_flight(&rustls::version::TLS12, true, None, true);
    let partial_request = &request[..1];
    let state = RuntimeState::test(RuntimeConfig::default());
    let (client, mut app) = pair();
    let (mut upstream, mut origin) = pair();
    upstream.set_read_timeout(Some(Duration::from_secs(2))).expect("bounded oracle read");
    origin.write_all(&response[..7]).expect("warning prefix");
    app.write_all(b"x").expect("owned continuation");
    let target = upstream.peer_addr().expect("target");
    let FirstResponse::Continuation(reader) = read_first_response(
        &state,
        target,
        None,
        &mut upstream,
        partial_request,
        Some(&client),
        FirstResponseReader::new(&state, partial_request),
    )
    .expect("inspect a TLS header flushed before the rest of its ClientHello") else {
        panic!("a partial outbound TLS header must retain warning observation");
    };
    origin.write_all(&response[7..]).expect("ServerHello flight");
    let FirstResponse::Forward(mut bytes, _) =
        read_first_response(&state, target, None, &mut upstream, partial_request, None, *reader)
            .expect("finish independently accepted TLS flight")
    else {
        panic!("valid continuation must forward");
    };
    assert_eq!(bytes, response[..bytes.len()]);
    let mut rest = vec![0; response.len() - bytes.len()];
    upstream.read_exact(&mut rest).expect("unread source bytes");
    bytes.extend(rest);
    assert_eq!(bytes, response);
}

#[test]
fn dpi38_partial_header_malformed_alert_cannot_succeed() {
    let alert = b"\x15\x03\x03\0\x02\0\x28";
    let config = rustls::ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_protocol_versions(&[&rustls::version::TLS12])
        .expect("TLS 1.2 oracle")
        .with_root_certificates(rustls::RootCertStore::empty())
        .with_no_client_auth();
    let mut oracle = rustls::ClientConnection::new(Arc::new(config), "localhost".try_into().expect("name"))
        .expect("independent client");
    oracle.write_tls(&mut Vec::new()).expect("start handshake");
    oracle.read_tls(&mut io::Cursor::new(alert)).expect("source alert bytes");
    assert!(matches!(
        oracle.process_new_packets(),
        Err(rustls::Error::AlertReceived(rustls::AlertDescription::HandshakeFailure))
    ));
    let state = RuntimeState::test(RuntimeConfig::default());
    let request = b"\x16";
    let (mut upstream, mut origin) = pair();
    origin.write_all(alert).expect("malformed alert source bytes");
    let target = upstream.peer_addr().expect("target");
    let result = read_first_response(
        &state,
        target,
        None,
        &mut upstream,
        request,
        None,
        FirstResponseReader::new(&state, request),
    )
    .expect("malformed response observation");
    let FirstResponse::Neutral(bytes) = result else {
        panic!("an unconfirmed partial request requires exact neutral forwarding");
    };
    assert_eq!(bytes, alert);
}

#[test]
fn dpi38_complete_malformed_alert_after_full_hello_is_a_generic_failure() {
    let (request, _) = independent_tls_flight(&rustls::version::TLS12, false, None, true);
    let alert = b"\x15\x03\x03\0\x02\0\x28";
    let state = RuntimeState::test(RuntimeConfig::default());
    let (mut upstream, mut origin) = pair();
    origin.write_all(alert).expect("complete malformed alert source bytes");
    let target = upstream.peer_addr().expect("target");
    let FirstResponse::Failure { failure, response_bytes: Some(bytes) } = read_first_response(
        &state,
        target,
        None,
        &mut upstream,
        &request,
        None,
        FirstResponseReader::new(&state, &request),
    )
    .expect("recognized ClientHello error contract") else {
        panic!("a complete malformed response to a recognized ClientHello keeps its generic failure");
    };
    assert_eq!(failure.class.as_str(), "tls_handshake_failure");
    assert!(
        !failure.evidence.tags.iter().any(|tag| tag.starts_with("alert=")),
        "malformed fields cannot forge a peer alert code"
    );
    assert_eq!(bytes, alert);
}

#[test]
fn dpi38_sni_free_client_hello_retains_configured_tls_observation() {
    let (request, response) = independent_tls_flight(&rustls::version::TLS12, true, None, false);
    let mut config = RuntimeConfig::default();
    config.timeouts.partial_timeout_ms = 1_000;
    let state = RuntimeState::test(config);
    let (client, mut app) = pair();
    let (mut upstream, mut origin) = pair();
    upstream.set_read_timeout(Some(Duration::from_secs(2))).expect("bounded oracle read");
    origin.write_all(&response[..7]).expect("warning before ServerHello");
    app.write_all(b"x").expect("owned continuation");
    let target = upstream.peer_addr().expect("target");
    let FirstResponse::Continuation(reader) = read_first_response(
        &state,
        target,
        None,
        &mut upstream,
        &request,
        Some(&client),
        FirstResponseReader::new(&state, &request),
    )
    .expect("inspect a ClientHello without SNI") else {
        panic!("SNI is not a TLS framing requirement");
    };
    let rest = response[7..].to_vec();
    let origin_worker = std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(500));
        origin.write_all(&rest).expect("remaining independently accepted flight");
    });
    let result = read_first_response(&state, target, None, &mut upstream, &request, None, *reader);
    origin_worker.join().expect("owned origin worker");
    let FirstResponse::Forward(mut bytes, _) = result.expect("configured partial timeout applies without SNI") else {
        panic!("SNI-free TLS must keep its configured 1000ms budget");
    };
    assert_eq!(bytes, response[..bytes.len()]);
    let mut rest = vec![0; response.len() - bytes.len()];
    upstream.read_exact(&mut rest).expect("unread source bytes");
    bytes.extend(rest);
    assert_eq!(bytes, response);
}

#[test]
fn dpi38_alert_window_exhaustion_is_neutral_with_exact_bytes() {
    let response = b"\x15\x03\x03\x00\x02\x01\x70\x16\x03\x03\x00\x04\x02\0\0\0";
    let control = Arc::new(EmbeddedProxyControl::new(None));
    let state = RuntimeState::test_with_control(RuntimeConfig::default(), control.clone());
    let (mut upstream, mut origin) = pair();
    origin.write_all(response).expect("warning and incomplete ServerHello");
    let target = upstream.peer_addr().expect("target");
    let started = Instant::now();
    let (sent, received) = std::sync::mpsc::sync_channel(1);
    let worker = std::thread::spawn(move || {
        let result = read_first_response(
            &state,
            target,
            None,
            &mut upstream,
            RUNTIME_DEFAULT_FAKE_TLS,
            None,
            FirstResponseReader::new(&state, RUNTIME_DEFAULT_FAKE_TLS),
        );
        sent.send(result).expect("inspection result");
    });
    let result = received.recv_timeout(Duration::from_secs(1));
    control.request_shutdown();
    worker.join().expect("bounded inspection worker");
    let FirstResponse::Neutral(bytes) =
        result.expect("fallback inspection must finish before owner shutdown").expect("bounded inspection")
    else {
        panic!("unknown negotiated version cannot accept a warning as TLS 1.2 success");
    };
    assert_eq!(bytes, response);
    assert!(started.elapsed() < Duration::from_secs(1), "inspection has a bounded fallback deadline");
}

#[test]
fn dpi38_alert_cap_retains_collected_and_unread_bytes() {
    let (request, response) = independent_tls_flight(&rustls::version::TLS12, true, None, true);
    let mut config = RuntimeConfig::default();
    config.timeouts.timeout_bytes_limit = 8;
    let state = RuntimeState::test(config);
    let (mut upstream, mut origin) = pair();
    upstream.set_read_timeout(Some(Duration::from_secs(2))).expect("bounded fixture read");
    origin.write_all(&response).expect("complete independently accepted TLS flight");
    let target = upstream.peer_addr().expect("target");
    let FirstResponse::Neutral(mut bytes) = read_first_response(
        &state,
        target,
        None,
        &mut upstream,
        &request,
        None,
        FirstResponseReader::new(&state, &request),
    )
    .expect("capped inspection") else {
        panic!("configured cap cannot manufacture failure or success");
    };
    assert_eq!(bytes, response[..8], "the lower configured limit is effective");
    let mut unread = vec![0; response.len() - bytes.len()];
    upstream.read_exact(&mut unread).expect("unread remainder remains on the socket");
    bytes.extend(unread);
    assert_eq!(bytes, response);
}

#[test]
fn dpi38_alert_chunks_do_not_extend_the_absolute_window() {
    let mut config = RuntimeConfig::default();
    config.timeouts.timeout_ms = 200;
    let state = RuntimeState::test(config);
    let request = RUNTIME_DEFAULT_FAKE_TLS;
    let (client, mut app) = pair();
    let (mut upstream, mut origin) = pair();
    origin.write_all(b"\x15\x03\x03\x00\x02\x01\x70").expect("warning prefix");
    app.write_all(b"x").expect("owned continuation");
    let target = upstream.peer_addr().expect("target");
    let started = Instant::now();
    let FirstResponse::Continuation(reader) = read_first_response(
        &state,
        target,
        None,
        &mut upstream,
        request,
        Some(&client),
        FirstResponseReader::new(&state, request),
    )
    .expect("first handoff") else {
        panic!("warning awaits its ServerHello");
    };
    std::thread::sleep(Duration::from_millis(125));
    origin.write_all(b"\x16").expect("next record prefix");
    let FirstResponse::Continuation(reader) =
        read_first_response(&state, target, None, &mut upstream, request, Some(&client), *reader)
            .expect("second handoff")
    else {
        panic!("partial ServerHello retains its owner");
    };
    let FirstResponse::Neutral(bytes) =
        read_first_response(&state, target, None, &mut upstream, request, None, *reader)
            .expect("absolute inspection deadline")
    else {
        panic!("window exhaustion is inconclusive");
    };
    assert_eq!(bytes, b"\x15\x03\x03\x00\x02\x01\x70\x16");
    assert!(started.elapsed() < Duration::from_millis(300), "a chunk extended the original 200ms window");
}

#[test]
fn dpi38_alert_window_owner_stop_stays_interrupted() {
    let control = Arc::new(EmbeddedProxyControl::new(None));
    let state = RuntimeState::test_with_control(RuntimeConfig::default(), control.clone());
    let request = RUNTIME_DEFAULT_FAKE_TLS;
    let (client, mut app) = pair();
    let (mut upstream, mut origin) = pair();
    origin.write_all(b"\x15\x03\x03\x00\x02\x01\x70").expect("warning prefix");
    app.write_all(b"x").expect("owned continuation");
    let target = upstream.peer_addr().expect("target");
    let FirstResponse::Continuation(reader) = read_first_response(
        &state,
        target,
        None,
        &mut upstream,
        request,
        Some(&client),
        FirstResponseReader::new(&state, request),
    )
    .expect("pending alert window") else {
        panic!("warning remains incomplete");
    };
    control.request_shutdown();
    assert!(
        read_first_response(&state, target, None, &mut upstream, request, None, *reader)
            .is_err_and(|error| error.kind() == io::ErrorKind::Interrupted),
        "cancellation is not window exhaustion"
    );
}
