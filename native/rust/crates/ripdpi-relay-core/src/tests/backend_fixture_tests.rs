use super::*;
use std::sync::Arc;

use crate::socks::UdpCarrier;

#[tokio::test]
async fn relay_runtime_routes_cloudflare_tunnel_through_xhttp_backend() {
    let mut config = sample_config("cloudflare_tunnel");
    config.common.server = "edge.example.com".to_string();
    config.common.server_name = "edge.example.com".to_string();
    cloudflare_config_mut(&mut config).xhttp_path = "/cdn/api".to_string();

    let backend = build_backend(&config).await;
    assert!(backend.is_ok(), "cloudflare tunnel backend should resolve");
    assert_eq!("edge.example.com:443", describe_upstream(&config));
}

/// Regression for the serve-an-`Unsupported`-backend hole: `run()` must fail
/// closed instead of binding the SOCKS listener and emitting `runtime_ready`
/// for a backend that fails every session (`off`, unknown kind, subprocess-only
/// kind wired to the native runtime).
#[tokio::test]
async fn relay_runtime_run_fails_closed_for_an_unsupported_backend() {
    let runtime = RelayRuntime::new(sample_config("totally_unknown"));

    let outcome = tokio::time::timeout(Duration::from_secs(5), Arc::clone(&runtime).run()).await;
    let result = outcome.expect("run() hung on an unsupported backend");
    let error = match result {
        Ok(()) => panic!("run() must not serve an Unsupported backend"),
        Err(error) => error,
    };
    assert_eq!(io::ErrorKind::Unsupported, error.kind());
    assert!(error.to_string().contains("cannot serve in-process"), "unexpected error: {error}");
    let telemetry = runtime.telemetry();
    assert!(telemetry.listener_address.is_none(), "no listener may be bound for an unsupported backend");
    assert_ne!("running", telemetry.state, "the runtime must never report running");
}

#[test]
fn relay_runtime_rejects_invalid_outbound_bind_ip() {
    let mut config = sample_config("vless_reality");
    config.common.outbound_bind_ip = "not-an-ip".to_string();
    let backend = RelayBackend::Unsupported { kind: "vless_reality".to_string() };

    let error = validate_runtime_config(&config, &backend).expect_err("invalid bind ip must fail");
    assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
}

#[test]
fn relay_runtime_rejects_bind_ip_for_unsupported_backend() {
    let mut config = sample_config("hysteria2");
    config.common.outbound_bind_ip = "203.0.113.10".to_string();
    let backend = RelayBackend::Unsupported { kind: "hysteria2".to_string() };

    let error = validate_runtime_config(&config, &backend).expect_err("unsupported bind ip must fail");
    assert_eq!(error.kind(), io::ErrorKind::Unsupported);
}

#[tokio::test]
async fn relay_runtime_builds_shadowtls_backend_with_inner_vless_profile() {
    let mut config = sample_config("shadowtls_v3");
    let shadowtls = shadowtls_config_mut(&mut config);
    shadowtls.inner_profile_id = "inner-vless".to_string();
    shadowtls.inner = Some(ResolvedShadowTlsInnerRelayConfig {
        kind: "vless_reality".to_string(),
        profile_id: "inner-vless".to_string(),
        server: "inner.example".to_string(),
        server_port: 443,
        server_name: "inner.example".to_string(),
        reality_public_key: "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=".to_string(),
        reality_short_id: String::new(),
        vless_flow: "xtls-rprx-vision".to_string(),
        vless_transport: "reality_tcp".to_string(),
        xhttp_mode: "auto".to_string(),
        vless_uuid: Some("00000000-0000-0000-0000-000000000000".to_string()),
        tls_fingerprint_profile: "chrome_stable".to_string(),
    });

    let backend = build_backend(&config).await.expect("shadowtls backend");
    match backend {
        RelayBackend::ShadowTls(_) => {}
        other => panic!("expected ShadowTLS backend, got {:?}", std::mem::discriminant(&other)),
    }
}

#[tokio::test]
async fn relay_runtime_round_trips_udp_through_vless_reality_xudp() {
    const PAYLOAD: &[u8] = b"relay-core VLESS Reality XUDP payload";

    let fixture = VlessRealityLoopback::start().await.expect("start VLESS Reality fixture");
    let mut config = sample_config("vless_reality");
    config.common.udp_enabled = true;
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(fixture.port());
    config.common.server_name = fixture.server_name().to_string();
    let vless = vless_config_mut(&mut config);
    vless.reality_public_key = valid_reality_public_key();
    vless.reality_short_id = String::new();

    let capabilities = planned_backend_capabilities(&config);
    assert_eq!((true, true), (capabilities.tcp, capabilities.udp));
    let backend = build_backend(&config).await.expect("VLESS Reality backend");
    validate_runtime_config(&config, &backend).expect("XUDP-enabled Reality should validate");

    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.udp_target_port()));
    let mut udp = backend.open_udp_session().await.expect("VLESS Reality XUDP session");
    udp.send_to(&target, PAYLOAD).await.expect("send XUDP payload");
    let (source, echoed) = udp.recv_from().await.expect("receive XUDP payload");
    assert_eq!(source, target);
    assert_eq!(echoed, PAYLOAD);
}

#[tokio::test]
async fn socks5_udp_associate_round_trips_through_vless_reality_xudp() {
    const PAYLOAD: &[u8] = b"SOCKS5 over VLESS Reality XUDP";

    let fixture = VlessRealityLoopback::start().await.expect("start VLESS Reality fixture");
    let mut config = sample_config("vless_reality");
    config.common.udp_enabled = true;
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(fixture.port());
    config.common.server_name = fixture.server_name().to_string();
    config.common.local_socks_host = "127.0.0.1".to_string();
    config.common.local_socks_port = 0;
    let vless = vless_config_mut(&mut config);
    vless.reality_public_key = valid_reality_public_key();
    vless.reality_short_id = String::new();

    let runtime = RelayRuntime::new(config);
    let run_handle = tokio::spawn(Arc::clone(&runtime).run());
    let listener = tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            if let Some(listener) = runtime.telemetry().listener_address {
                break listener.parse::<SocketAddr>().expect("SOCKS listener address");
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .expect("SOCKS listener did not start");

    let mut control = tokio::net::TcpStream::connect(listener).await.expect("connect SOCKS control");
    control.write_all(&[0x05, 0x01, 0x00]).await.expect("write SOCKS greeting");
    let mut greeting = [0_u8; 2];
    control.read_exact(&mut greeting).await.expect("read SOCKS greeting");
    assert_eq!(greeting, [0x05, 0x00]);
    control.write_all(&[0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await.expect("write UDP ASSOCIATE");
    let mut reply = [0_u8; 10];
    control.read_exact(&mut reply).await.expect("read UDP ASSOCIATE reply");
    assert_eq!(reply[1], 0x00);
    let udp_relay = SocketAddr::new(
        IpAddr::V4(Ipv4Addr::new(reply[4], reply[5], reply[6], reply[7])),
        u16::from_be_bytes([reply[8], reply[9]]),
    );

    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.udp_target_port()));
    let frame = crate::socks::encode_udp_frame(&target, PAYLOAD).expect("encode SOCKS UDP frame");
    let udp = tokio::net::UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind SOCKS UDP client");
    udp.send_to(&frame, udp_relay).await.expect("send SOCKS UDP frame");
    let mut response = [0_u8; 256];
    let (read, _) = tokio::time::timeout(Duration::from_secs(5), udp.recv_from(&mut response))
        .await
        .expect("SOCKS UDP response timed out")
        .expect("receive SOCKS UDP response");
    let (source, echoed) = crate::socks::decode_udp_frame(&response[..read]).expect("decode SOCKS UDP response");
    assert_eq!(source, target);
    assert_eq!(echoed, PAYLOAD);

    let dns_query = [
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07, b'e', b'x', b'a', b'm', b'p',
        b'l', b'e', 0x03, b'c', b'o', b'm', 0x00, 0x00, 0x01, 0x00, 0x01,
    ];
    let dns_target = RelayTargetAddr::Domain("127.0.0.1".to_string(), fixture.udp_target_port());
    let dns_frame = crate::socks::encode_udp_frame(&dns_target, &dns_query).expect("encode SOCKS DNS frame");
    udp.send_to(&dns_frame, udp_relay).await.expect("send SOCKS DNS frame");
    let (read, _) = tokio::time::timeout(Duration::from_secs(5), udp.recv_from(&mut response))
        .await
        .expect("SOCKS DNS response timed out")
        .expect("receive SOCKS DNS response");
    let (dns_source, dns_echoed) =
        crate::socks::decode_udp_frame(&response[..read]).expect("decode SOCKS DNS response");
    assert_eq!(dns_source, target);
    assert_eq!(dns_echoed, dns_query);
    assert!(runtime.telemetry().last_target.is_none(), "XUDP telemetry must not expose datagram targets");

    drop(control);
    runtime.stop();
    tokio::time::timeout(Duration::from_secs(10), run_handle)
        .await
        .expect("relay runtime stop timed out")
        .expect("relay runtime task")
        .expect("relay runtime result");
}

#[tokio::test]
async fn relay_runtime_builds_trojan_backend_and_connects_tcp_fixture() {
    const PAYLOAD: &[u8] = b"relay-core trojan tcp payload";

    let fixture = TrojanLoopback::start("secret").await.expect("start trojan fixture");
    let mut config = sample_config("trojan");
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(fixture.port());
    config.common.server_name = fixture.server_name().to_string();
    trojan_config_mut(&mut config).root_certificate_pem = Some(fixture.certificate_pem().to_string());

    let backend = build_backend(&config).await.expect("trojan backend");
    match &backend {
        RelayBackend::Trojan(_) => {}
        other => panic!("expected Trojan backend, got {:?}", std::mem::discriminant(other)),
    }

    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.target_port()));
    let mut stream = backend.connect_tcp(&target).await.expect("trojan connect tcp");
    stream.write_all(PAYLOAD).await.expect("write tunnel payload");
    let mut echoed = vec![0_u8; PAYLOAD.len()];
    stream.read_exact(&mut echoed).await.expect("read tunnel payload");
    assert_eq!(echoed, PAYLOAD);
}

#[tokio::test]
async fn relay_runtime_builds_trojan_udp_associate_fixture() {
    const PAYLOAD: &[u8] = b"relay-core trojan udp payload";

    let fixture = TrojanLoopback::start("secret").await.expect("start trojan fixture");
    let mut config = sample_config("trojan");
    config.common.udp_enabled = true;
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(fixture.port());
    config.common.server_name = fixture.server_name().to_string();
    trojan_config_mut(&mut config).root_certificate_pem = Some(fixture.certificate_pem().to_string());

    let capabilities = planned_backend_capabilities(&config);
    assert!(capabilities.udp, "Trojan should report UDP capability");
    let backend = build_backend(&config).await.expect("trojan backend");
    validate_runtime_config(&config, &backend).expect("trojan udp should validate");

    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.udp_target_port()));
    let mut udp = backend.open_udp_session().await.expect("trojan udp session");
    udp.send_to(&target, PAYLOAD).await.expect("send udp payload");
    let (source, echoed) = udp.recv_from().await.expect("receive udp payload");
    assert_eq!(source, target);
    assert_eq!(echoed, PAYLOAD);
}

#[tokio::test]
async fn relay_runtime_builds_anytls_backend_and_connects_tcp_fixture() {
    const PAYLOAD: &[u8] = b"relay-core anytls tcp payload";

    let fixture = AnyTlsLoopback::start("secret", AnyTlsLoopbackConfig::default()).await.expect("start anytls fixture");
    let mut config = sample_config("anytls");
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(fixture.port());
    config.common.server_name = fixture.server_name().to_string();
    anytls_config_mut(&mut config).root_certificate_pem = Some(fixture.certificate_pem().to_string());

    let backend = build_backend(&config).await.expect("anytls backend");
    match &backend {
        RelayBackend::AnyTls(_) => {}
        other => panic!("expected AnyTLS backend, got {:?}", std::mem::discriminant(other)),
    }

    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.target_port()));
    let mut stream = backend.connect_tcp(&target).await.expect("anytls connect tcp");
    stream.write_all(PAYLOAD).await.expect("write tunnel payload");
    let mut echoed = vec![0_u8; PAYLOAD.len()];
    stream.read_exact(&mut echoed).await.expect("read tunnel payload");
    assert_eq!(echoed, PAYLOAD);
    assert_eq!(fixture.observed().tls_session_count, 1);
}

#[tokio::test]
async fn relay_runtime_builds_anytls_udp_over_tcp_fixture() {
    const PAYLOAD: &[u8] = b"relay-core anytls udp payload";

    let fixture = AnyTlsLoopback::start("secret", AnyTlsLoopbackConfig::default()).await.expect("start anytls fixture");
    let mut config = sample_config("anytls");
    config.common.udp_enabled = true;
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(fixture.port());
    config.common.server_name = fixture.server_name().to_string();
    anytls_config_mut(&mut config).root_certificate_pem = Some(fixture.certificate_pem().to_string());

    let capabilities = planned_backend_capabilities(&config);
    assert_eq!((true, true, true), (capabilities.tcp, capabilities.udp, capabilities.reusable));
    let backend = build_backend(&config).await.expect("anytls backend");
    validate_runtime_config(&config, &backend).expect("anytls udp should validate");

    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.udp_target_port()));
    let mut udp = backend.open_udp_session().await.expect("anytls udp-over-tcp session");
    udp.send_to(&target, PAYLOAD).await.expect("send udp payload");
    let (source, echoed) = udp.recv_from().await.expect("receive udp payload");
    assert_eq!(source, target);
    assert_eq!(echoed, PAYLOAD);
    assert_eq!(fixture.observed().udp_magic_targets, vec!["sp.v2.udp-over-tcp.arpa:0".to_string()]);
}

#[tokio::test]
async fn relay_runtime_builds_shadowsocks_backend_and_connects_tcp_fixture() {
    const PAYLOAD: &[u8] = b"relay-core shadowsocks tcp payload";

    let fixture = ShadowsocksLoopback::start("aes-256-gcm", "secret").await.expect("start shadowsocks fixture");
    let mut config = sample_config("shadowsocks");
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(fixture.port());
    shadowsocks_config_mut(&mut config).method = "aes-256-gcm".to_string();

    let backend = build_backend(&config).await.expect("shadowsocks backend");
    match &backend {
        RelayBackend::Shadowsocks(_) => {}
        other => panic!("expected Shadowsocks backend, got {:?}", std::mem::discriminant(other)),
    }

    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.target_port()));
    let mut stream = backend.connect_tcp(&target).await.expect("shadowsocks connect tcp");
    stream.write_all(PAYLOAD).await.expect("write tunnel payload");
    let mut echoed = vec![0_u8; PAYLOAD.len()];
    stream.read_exact(&mut echoed).await.expect("read tunnel payload");
    assert_eq!(echoed, PAYLOAD);
}

#[tokio::test]
async fn relay_runtime_builds_shadowsocks_udp_associate_fixture() {
    const PAYLOAD: &[u8] = b"relay-core shadowsocks udp payload";

    let fixture = ShadowsocksLoopback::start("aes-256-gcm", "secret").await.expect("start shadowsocks fixture");
    let mut config = sample_config("shadowsocks");
    config.common.udp_enabled = true;
    config.common.server = "127.0.0.1".to_string();
    config.common.server_port = i32::from(fixture.port());

    let capabilities = planned_backend_capabilities(&config);
    assert!(capabilities.udp, "Shadowsocks should report UDP capability");
    let backend = build_backend(&config).await.expect("shadowsocks backend");
    validate_runtime_config(&config, &backend).expect("shadowsocks udp should validate");

    let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), fixture.udp_target_port()));
    let mut udp = backend.open_udp_session().await.expect("shadowsocks udp session");
    udp.send_to(&target, PAYLOAD).await.expect("send udp payload");
    let (source, echoed) = udp.recv_from().await.expect("receive udp payload");
    assert_eq!(source, target);
    assert_eq!(echoed, PAYLOAD);
}

#[tokio::test]
async fn tor_backend_builds_in_process_and_rejects_udp() {
    let mut config = sample_config("tor");
    config.common.udp_enabled = true;

    let backend = build_backend(&config).await.expect("tor backend builds");

    assert_eq!(Some("tor"), relay_backend_kind_id(&backend));
    assert!(!backend.udp_capable(), "Tor is TCP-only");
    let error = validate_runtime_config(&config, &backend).expect_err("Tor backend must reject UDP");
    assert_eq!(io::ErrorKind::Unsupported, error.kind());
}

#[tokio::test]
async fn relay_runtime_rejects_a_non_loopback_socks_listener_host() {
    let mut config = sample_config("trojan");
    config.common.local_socks_host = "0.0.0.0".to_string();
    let backend = build_backend(&config).await.expect("backend builds");
    let error =
        validate_runtime_config(&config, &backend).expect_err("a non-loopback NO-AUTH listener must fail closed");
    assert_eq!(io::ErrorKind::InvalidInput, error.kind());
    assert!(error.to_string().contains("loopback"), "unexpected error: {error}");
}

/// A v6 `outbound_bind_ip` against a v4-only loopback server must fail closed
/// at the transport dial — proving the builder actually forwards the bind IP
/// into each transport's client config instead of silently dropping it.
/// Covers every kind whose descriptor advertises `supports_outbound_bind_ip`
/// and whose carrier is wired in this crate (the remaining kinds fail closed
/// inside their own transports or are subprocess-backed).
#[tokio::test]
async fn outbound_bind_ip_reaches_trojan_anytls_and_tuic_transports() {
    for kind_id in ["trojan", "anytls", "tuic_v5"] {
        let mut config = sample_config(kind_id);
        config.common.server = "127.0.0.1".to_string();
        config.common.outbound_bind_ip = "::1".to_string();

        let backend = build_backend(&config).await.unwrap_or_else(|error| panic!("{kind_id} backend: {error}"));
        validate_runtime_config(&config, &backend).unwrap_or_else(|error| panic!("{kind_id} validate: {error}"));

        let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 9));
        let Err(error) = backend.connect_tcp(&target).await else {
            panic!("{kind_id} family mismatch must fail closed at the transport");
        };
        assert!(
            error.to_string().contains("outbound bind IP family"),
            "{kind_id} must surface the bind-family failure, got: {error}"
        );
    }
}

/// Asserts whether `validate_runtime_config` accepts an outbound bind IP for a
/// relay kind, exercising the descriptor-driven capability gate end to end.
fn assert_outbound_bind_ip_support(kind_id: &str, base: &ResolvedRelayRuntimeConfig, supported: bool) {
    let mut config = base.clone();
    config.common.outbound_bind_ip = "203.0.113.10".to_string();
    let backend = RelayBackend::Unsupported { kind: kind_id.to_string() };

    let result = validate_runtime_config(&config, &backend);
    if supported {
        result.unwrap_or_else(|error| panic!("{kind_id} should accept an outbound bind IP: {error}"));
    } else {
        let error = result.expect_err("outbound bind IP must be rejected");
        assert_eq!(error.kind(), io::ErrorKind::Unsupported, "{kind_id} bind-IP rejection error kind");
    }
}

/// Pins the planned SOCKS capability profile and outbound-bind-IP support for
/// every `RelayKind`. `planned_backend_capabilities` and the bind-IP gate
/// resolve through the `RELAY_TRANSPORT_REGISTRATIONS` descriptors, so a wrong
/// descriptor row is caught here against literal expectations.
#[test]
fn relay_planned_capabilities_are_pinned_for_every_kind() {
    // kind_id, tcp, udp, reusable, supports_outbound_bind_ip
    let pinned: [(&str, bool, bool, bool, bool); 15] = [
        ("hysteria2", true, true, true, false),
        ("tuic_v5", true, true, true, true),
        ("vless", true, false, true, true),
        ("vless_reality", true, false, false, true),
        ("mieru", true, false, false, false),
        ("ssh", true, false, false, false),
        ("cloudflare_tunnel", true, false, true, true),
        ("chain_relay", true, false, false, true),
        ("masque", true, true, true, false),
        ("shadowtls_v3", true, false, false, true),
        ("trojan", true, true, false, true),
        ("anytls", true, true, true, true),
        ("shadowsocks", true, true, false, true),
        ("tor", true, false, true, false),
        ("naiveproxy", true, false, false, true),
    ];

    for (kind_id, tcp, udp, reusable, bind_ip) in pinned {
        let config = sample_config(kind_id);
        let capabilities = planned_backend_capabilities(&config);
        assert_eq!(
            (tcp, udp, reusable),
            (capabilities.tcp, capabilities.udp, capabilities.reusable),
            "planned capabilities drifted for {kind_id}",
        );
        assert_outbound_bind_ip_support(kind_id, &config, bind_ip);
    }

    let mut xudp_reality = sample_config("vless_reality");
    xudp_reality.common.udp_enabled = true;
    let xudp = planned_backend_capabilities(&xudp_reality);
    assert_eq!((true, true, false), (xudp.tcp, xudp.udp, xudp.reusable));

    // VLESS Reality's `xhttp` sub-mode shares the single `vless_reality`
    // descriptor, but the planned profile capabilities apply the sub-mode
    // gate without introducing a second kind capability table: xhttp muxes
    // and pools its carrier, so reuse is reported even though the per-kind
    // descriptor row cannot express it.
    let mut vless_xhttp = sample_config("vless_reality");
    vless_config_mut(&mut vless_xhttp).vless_transport = "xhttp".to_string();
    let xhttp = planned_backend_capabilities(&vless_xhttp);
    assert_eq!(
        (true, false, true),
        (xhttp.tcp, xhttp.udp, xhttp.reusable),
        "VLESS xhttp sub-mode must report mux-carrier reuse",
    );

    // The `Unsupported` catch-all has no descriptor: it reports the empty
    // capability profile and keeps the permissive outbound-bind-IP default.
    let unsupported = sample_config("totally_unknown");
    let caps = planned_backend_capabilities(&unsupported);
    assert_eq!(
        (false, false, false),
        (caps.tcp, caps.udp, caps.reusable),
        "Unsupported relay kind must report no capabilities",
    );
    assert_outbound_bind_ip_support("totally_unknown", &unsupported, true);
}

/// Compile-time drift guard for the runtime-dispatch backend enum. The
/// `dispatch_pooled_backend!` macro routes SOCKS traffic by `RelayBackend`
/// variant; this maps each variant back to the `relay_kind` it serves. A new
/// `RelayBackend` variant fails to compile here until it is mapped, which
/// forces a matching registration. `Unsupported` carries no `relay_kind`.
pub(super) fn relay_backend_kind_id(backend: &RelayBackend) -> Option<&'static str> {
    match backend {
        RelayBackend::Hysteria2(_) => Some("hysteria2"),
        RelayBackend::Tuic(_) => Some("tuic_v5"),
        RelayBackend::VlessReality(_) | RelayBackend::Xhttp(_) => Some("vless_reality"),
        RelayBackend::Mieru(_) => Some("mieru"),
        RelayBackend::Ssh(_) => Some("ssh"),
        RelayBackend::ChainRelay { .. } => Some("chain_relay"),
        RelayBackend::Masque(_) => Some("masque"),
        RelayBackend::ShadowTls(_) => Some("shadowtls_v3"),
        RelayBackend::Trojan(_) => Some("trojan"),
        RelayBackend::AnyTls(_) => Some("anytls"),
        RelayBackend::Shadowsocks(_) => Some("shadowsocks"),
        RelayBackend::Tor(_) => Some("tor"),
        RelayBackend::Unsupported { .. } => None,
    }
}
