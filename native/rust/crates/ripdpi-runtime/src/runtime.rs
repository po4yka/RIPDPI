mod adaptive;
mod desync;
mod handshake;
mod listeners;
mod relay;
mod retry;
mod routing;
mod state;
mod udp;

use std::io;
use std::net::TcpListener;

use ripdpi_config::{RuntimeConfig, TcpChainStepKind, UdpChainStepKind};

use self::listeners::{build_listener, run_proxy_with_listener_internal};
use crate::platform::IpFragmentationCapabilities;
use crate::EmbeddedProxyControl;

pub fn run_proxy(config: RuntimeConfig) -> io::Result<()> {
    let listener = create_listener(&config)?;
    run_proxy_with_listener(config, listener)
}

pub fn create_listener(config: &RuntimeConfig) -> io::Result<TcpListener> {
    validate_ip_fragmentation_support(config)?;
    build_listener(config)
}

fn validate_ip_fragmentation_support(config: &RuntimeConfig) -> io::Result<()> {
    let requires_raw_sockets = config
        .groups
        .iter()
        .flat_map(|group| group.effective_tcp_chain())
        .any(|step| step.kind == TcpChainStepKind::IpFrag2)
        || config
            .groups
            .iter()
            .flat_map(|group| group.effective_udp_chain())
            .any(|step| step.kind == UdpChainStepKind::IpFrag2Udp);
    if !requires_raw_sockets {
        return Ok(());
    }

    let capabilities = crate::platform::probe_ip_fragmentation_capabilities(config.process.protect_path.as_deref())?;
    validate_ip_fragmentation_capabilities(config, capabilities)
}

fn validate_ip_fragmentation_capabilities(
    config: &RuntimeConfig,
    capabilities: IpFragmentationCapabilities,
) -> io::Result<()> {
    let requires_tcp_ipfrag = config
        .groups
        .iter()
        .flat_map(|group| group.effective_tcp_chain())
        .any(|step| step.kind == TcpChainStepKind::IpFrag2);
    let requires_udp_ipfrag = config
        .groups
        .iter()
        .flat_map(|group| group.effective_udp_chain())
        .any(|step| step.kind == UdpChainStepKind::IpFrag2Udp);
    if !requires_tcp_ipfrag && !requires_udp_ipfrag {
        return Ok(());
    }

    let mut missing = Vec::new();
    if (requires_tcp_ipfrag || requires_udp_ipfrag) && !capabilities.raw_ipv4 {
        missing.push("raw IPv4 sockets");
    }
    if (requires_tcp_ipfrag || requires_udp_ipfrag) && config.network.ipv6 && !capabilities.raw_ipv6 {
        missing.push("raw IPv6 sockets");
    }
    if requires_tcp_ipfrag && !capabilities.tcp_repair {
        missing.push("TCP repair");
    }
    if missing.is_empty() {
        Ok(())
    } else {
        Err(io::Error::new(io::ErrorKind::Unsupported, format!("ipfrag2 requires {}", missing.join(", "))))
    }
}

pub fn run_proxy_with_listener(config: RuntimeConfig, listener: TcpListener) -> io::Result<()> {
    run_proxy_with_listener_internal(config, listener, None)
}

pub fn run_proxy_with_embedded_control(
    config: RuntimeConfig,
    listener: TcpListener,
    control: std::sync::Arc<EmbeddedProxyControl>,
) -> io::Result<()> {
    run_proxy_with_listener_internal(config, listener, Some(control))
}

#[cfg(test)]
mod tests {
    #[cfg(not(feature = "loom"))]
    use super::state::ClientSlotGuard;
    use crate::adaptive_fake_ttl::AdaptiveFakeTtlResolver;
    use crate::adaptive_tuning::AdaptivePlannerResolver;
    use crate::retry_stealth::RetryPacer;
    use crate::runtime::desync::send_with_group;
    use crate::runtime::routing::{advance_route_for_failure, select_route};
    use crate::runtime::state::RuntimeState;
    use crate::runtime_policy::RuntimePolicy;
    use crate::sync::{Arc, AtomicBool, AtomicUsize, Mutex};
    use ripdpi_config::{
        DesyncGroup, OffsetExpr, RuntimeConfig, TcpChainStep, TcpChainStepKind, UdpChainStep, UdpChainStepKind,
        DETECT_CONNECT, DETECT_HTTP_LOCAT,
    };
    use ripdpi_packets::{DEFAULT_FAKE_TLS, IS_HTTPS};
    use ripdpi_session::{
        encode_http_connect_reply, encode_socks4_reply, encode_socks5_reply, OutboundProgress, S_ATP_I4, S_ATP_I6,
        S_CMD_CONN, S_ER_CONN, S_VER5,
    };
    use std::io::ErrorKind;
    use std::io::Read;
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpListener, TcpStream};
    #[cfg(not(feature = "loom"))]
    use std::sync::atomic::Ordering;
    use std::thread;

    use super::routing::{encode_upstream_socks_connect, failure_penalizes_strategy, failure_trigger_mask};
    use super::validate_ip_fragmentation_capabilities;

    use ripdpi_failure_classifier::{ClassifiedFailure, FailureAction, FailureClass, FailureStage};

    #[cfg(not(feature = "loom"))]
    #[test]
    fn client_slot_guard_enforces_limit_and_releases_slot() {
        let active = Arc::new(AtomicUsize::new(0));

        let guard = ClientSlotGuard::acquire(active.clone(), 1).expect("first slot");
        assert_eq!(active.load(Ordering::Relaxed), 1);
        assert!(ClientSlotGuard::acquire(active.clone(), 1).is_none());

        drop(guard);
        assert_eq!(active.load(Ordering::Relaxed), 0);
        assert!(ClientSlotGuard::acquire(active, 1).is_some());
    }

    #[test]
    fn encode_upstream_socks_connect_encodes_ipv6_targets() {
        let target = SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 8080);
        let encoded = encode_upstream_socks_connect(target);

        assert_eq!(encoded[..4], [S_VER5, S_CMD_CONN, 0, S_ATP_I6]);
        assert_eq!(&encoded[4..20], &Ipv6Addr::LOCALHOST.octets());
        assert_eq!(&encoded[20..22], &8080u16.to_be_bytes());
    }

    // -- Characterization: protocol reply byte sequences --

    #[test]
    fn socks4_success_reply_byte_sequence() {
        let reply = encode_socks4_reply(true);
        let bytes = reply.as_bytes();
        assert_eq!(bytes[0], 0x00, "VN must be 0");
        assert_eq!(bytes[1], 0x5a, "CD must be 0x5a (granted)");
        assert_eq!(bytes.len(), 8, "SOCKS4 reply is always 8 bytes");
    }

    #[test]
    fn socks4_failure_reply_byte_sequence() {
        let reply = encode_socks4_reply(false);
        let bytes = reply.as_bytes();
        assert_eq!(bytes[0], 0x00);
        assert_eq!(bytes[1], 0x5b, "CD must be 0x5b (rejected)");
    }

    #[test]
    fn socks5_success_reply_preserves_bind_address() {
        let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 168, 1, 100)), 8080);
        let reply = encode_socks5_reply(0, addr);
        let bytes = reply.as_bytes();
        assert_eq!(bytes[0], S_VER5);
        assert_eq!(bytes[1], 0x00, "REP success");
        assert_eq!(bytes[3], S_ATP_I4);
        assert_eq!(&bytes[4..8], &[192, 168, 1, 100]);
        assert_eq!(&bytes[8..10], &8080u16.to_be_bytes());
    }

    #[test]
    fn socks5_error_reply_carries_error_code() {
        let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
        let reply = encode_socks5_reply(S_ER_CONN, addr);
        let bytes = reply.as_bytes();
        assert_eq!(bytes[1], S_ER_CONN);
    }

    #[test]
    fn http_connect_success_reply_is_200_ok() {
        let reply = encode_http_connect_reply(true);
        let text = std::str::from_utf8(reply.as_bytes()).expect("utf8");
        assert!(text.starts_with("HTTP/1.1 200 OK\r\n"));
        assert!(text.ends_with("\r\n\r\n"));
    }

    #[test]
    fn http_connect_failure_reply_is_503() {
        let reply = encode_http_connect_reply(false);
        let text = std::str::from_utf8(reply.as_bytes()).expect("utf8");
        assert!(text.starts_with("HTTP/1.1 503 Fail\r\n"));
    }

    // -- Characterization: failure classification trigger mapping --

    #[test]
    fn failure_trigger_mask_covers_all_detection_classes() {
        use ripdpi_config::{
            DETECT_DNS_TAMPER, DETECT_HTTP_BLOCKPAGE, DETECT_SILENT_DROP, DETECT_TCP_RESET, DETECT_TLS_ALERT,
            DETECT_TLS_HANDSHAKE_FAILURE,
        };

        let cases = [
            (FailureClass::TcpReset, DETECT_TCP_RESET),
            (FailureClass::SilentDrop, DETECT_SILENT_DROP),
            (FailureClass::TlsAlert, DETECT_TLS_ALERT),
            (FailureClass::HttpBlockpage, DETECT_HTTP_BLOCKPAGE),
            (FailureClass::Redirect, DETECT_HTTP_LOCAT),
            (FailureClass::TlsHandshakeFailure, DETECT_TLS_HANDSHAKE_FAILURE),
            (FailureClass::DnsTampering, DETECT_DNS_TAMPER),
            (FailureClass::ConnectFailure, DETECT_CONNECT),
            (FailureClass::StrategyExecutionFailure, DETECT_CONNECT),
        ];

        for (class, expected_mask) in cases {
            let failure =
                ClassifiedFailure::new(class, FailureStage::FirstResponse, FailureAction::RetryWithMatchingGroup, "");
            assert_eq!(failure_trigger_mask(&failure), expected_mask, "trigger mask mismatch for {class:?}");
        }

        // Classes with zero trigger mask
        for class in [FailureClass::QuicBreakage, FailureClass::Unknown] {
            let failure =
                ClassifiedFailure::new(class, FailureStage::FirstResponse, FailureAction::RetryWithMatchingGroup, "");
            assert_eq!(failure_trigger_mask(&failure), 0, "{class:?} should have zero mask");
        }
    }

    #[test]
    fn failure_penalizes_strategy_for_expected_classes() {
        let penalizing = [
            FailureClass::TcpReset,
            FailureClass::SilentDrop,
            FailureClass::TlsAlert,
            FailureClass::HttpBlockpage,
            FailureClass::Redirect,
            FailureClass::TlsHandshakeFailure,
        ];
        let non_penalizing = [
            FailureClass::DnsTampering,
            FailureClass::ConnectFailure,
            FailureClass::StrategyExecutionFailure,
            FailureClass::QuicBreakage,
            FailureClass::Unknown,
        ];

        for class in penalizing {
            let failure =
                ClassifiedFailure::new(class, FailureStage::FirstResponse, FailureAction::RetryWithMatchingGroup, "");
            assert!(failure_penalizes_strategy(&failure), "{class:?} should penalize");
        }
        for class in non_penalizing {
            let failure =
                ClassifiedFailure::new(class, FailureStage::FirstResponse, FailureAction::RetryWithMatchingGroup, "");
            assert!(!failure_penalizes_strategy(&failure), "{class:?} should not penalize");
        }
    }

    fn runtime_config_with_ipfrag(tcp: bool, udp: bool, ipv6: bool) -> RuntimeConfig {
        let mut config = RuntimeConfig::default();
        config.network.ipv6 = ipv6;
        let mut group = DesyncGroup::new(0);
        if tcp {
            group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::IpFrag2, OffsetExpr::host(2)));
        }
        if udp {
            group.actions.udp_chain.push(UdpChainStep {
                kind: UdpChainStepKind::IpFrag2Udp,
                count: 0,
                split_bytes: 8,
                activation_filter: None,
            });
        }
        config.groups = vec![group];
        config
    }

    #[test]
    fn ipfrag_capability_validation_allows_non_fragmenting_configs() {
        let config = RuntimeConfig::default();

        validate_ip_fragmentation_capabilities(&config, crate::platform::IpFragmentationCapabilities::default())
            .expect("non-ipfrag configs should skip capability gating");
    }

    #[test]
    fn ipfrag_capability_validation_requires_ipv6_raw_socket_when_enabled() {
        let config = runtime_config_with_ipfrag(false, true, true);
        let err = validate_ip_fragmentation_capabilities(
            &config,
            crate::platform::IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: false, tcp_repair: false },
        )
        .expect_err("ipv6 ipfrag should require raw ipv6");

        assert_eq!(err.kind(), ErrorKind::Unsupported);
        assert!(err.to_string().contains("raw IPv6 sockets"));
    }

    #[test]
    fn ipfrag_capability_validation_requires_tcp_repair_for_tcp_steps() {
        let config = runtime_config_with_ipfrag(true, false, false);
        let err = validate_ip_fragmentation_capabilities(
            &config,
            crate::platform::IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: false, tcp_repair: false },
        )
        .expect_err("tcp ipfrag should require tcp repair");

        assert_eq!(err.kind(), ErrorKind::Unsupported);
        assert!(err.to_string().contains("TCP repair"));
    }

    #[test]
    fn ipfrag_capability_validation_does_not_require_ipv6_when_disabled() {
        let config = runtime_config_with_ipfrag(false, true, false);

        validate_ip_fragmentation_capabilities(
            &config,
            crate::platform::IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: false, tcp_repair: false },
        )
        .expect("ipv4-only ipfrag should not require raw ipv6");
    }

    #[test]
    fn ipfrag_capability_helpers_distinguish_tcp_and_udp_requirements() {
        let udp_only =
            crate::platform::IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: true, tcp_repair: false };
        assert!(udp_only.supports_udp_ip_fragmentation(true));
        assert!(!udp_only.supports_tcp_ip_fragmentation(true));

        let tcp_and_udp =
            crate::platform::IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: true, tcp_repair: true };
        assert!(tcp_and_udp.supports_udp_ip_fragmentation(true));
        assert!(tcp_and_udp.supports_tcp_ip_fragmentation(true));
    }

    #[test]
    fn strategy_execution_failure_advances_to_plain_connect_fallback_and_replays_payload() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind fixture listener");
        let target = listener.local_addr().expect("listener addr");
        let payload = DEFAULT_FAKE_TLS.to_vec();
        let expected = payload.clone();

        let server = thread::spawn(move || {
            let (mut socket, _) = listener.accept().expect("accept fallback connection");
            let mut received = vec![0u8; expected.len()];
            socket.read_exact(&mut received).expect("read fallback payload");
            received
        });

        let mut primary = DesyncGroup::new(0);
        primary.matches.proto = IS_HTTPS;
        primary.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Disorder, OffsetExpr::tls_host(1)));

        let mut fallback = DesyncGroup::new(1);
        fallback.matches.detect = DETECT_CONNECT;

        let config =
            ripdpi_config::RuntimeConfig { groups: vec![primary, fallback], ..ripdpi_config::RuntimeConfig::default() };
        let state = RuntimeState {
            config: Arc::new(config.clone()),
            cache: Arc::new(Mutex::new(RuntimePolicy::load(&config))),
            adaptive_fake_ttl: Arc::new(Mutex::new(AdaptiveFakeTtlResolver::default())),
            adaptive_tuning: Arc::new(Mutex::new(AdaptivePlannerResolver::default())),
            retry_stealth: Arc::new(Mutex::new(RetryPacer::default())),
            strategy_evolver: Arc::new(Mutex::new(crate::strategy_evolver::StrategyEvolver::new(false, 0.0))),
            active_clients: Arc::new(AtomicUsize::new(0)),
            telemetry: None,
            runtime_context: None,
            control: None,
            ttl_unavailable: Arc::new(AtomicBool::new(false)),
        };

        let initial = select_route(&state, target, Some(&payload), None, false).expect("initial route");
        assert_eq!(initial.group_index, 0);

        let failure = ClassifiedFailure::new(
            FailureClass::StrategyExecutionFailure,
            FailureStage::FirstWrite,
            FailureAction::RetryWithMatchingGroup,
            "desync action=set_ttl: Invalid argument (os error 22)",
        )
        .with_tag("action", "set_ttl")
        .with_tag("errno", libc::EINVAL.to_string());
        let next = advance_route_for_failure(&state, target, &initial, None, Some(&payload), &failure)
            .expect("advance route")
            .expect("fallback route");
        assert_eq!(next.group_index, 1);

        let mut upstream = TcpStream::connect(target).expect("connect fallback upstream");
        let progress = OutboundProgress {
            round: 1,
            payload_size: payload.len(),
            stream_start: 0,
            stream_end: payload.len().saturating_sub(1),
        };
        send_with_group(
            &mut upstream,
            &state,
            next.group_index,
            &config.groups[next.group_index],
            &payload,
            progress,
            Some("example.org"),
            target,
        )
        .expect("send via fallback group");

        assert_eq!(server.join().expect("join fallback server"), payload);
    }

    #[test]
    fn connect_socket_respects_timeout() {
        use super::routing::connect_socket;
        use std::time::{Duration, Instant};

        // 192.0.2.1 is RFC 5737 TEST-NET-1 — guaranteed non-routable.
        let target = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 1)), 80);
        let timeout = Duration::from_secs(1);
        let start = Instant::now();

        let result = connect_socket(target, IpAddr::V4(Ipv4Addr::UNSPECIFIED), None, false, Some(timeout));
        let elapsed = start.elapsed();

        assert!(result.is_err(), "connect to TEST-NET should fail");
        assert!(elapsed < Duration::from_secs(5), "connect should respect the 1s timeout, but took {elapsed:?}");
    }

    #[cfg(any(target_os = "linux", target_os = "android"))]
    #[test]
    fn window_clamp_applied_on_connected_socket() {
        use crate::platform::linux::{get_tcp_window_clamp, set_tcp_window_clamp};

        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let addr = listener.local_addr().expect("local_addr");
        let client = TcpStream::connect(addr).expect("connect");

        set_tcp_window_clamp(&client, 2).expect("set clamp");
        let clamp = get_tcp_window_clamp(&client).expect("get clamp");
        // Kernel may round the value up, but it must be small.
        assert!(clamp <= 128, "expected small clamp, got {clamp}");

        // Restore: set to 0 (removes clamp).
        set_tcp_window_clamp(&client, 0).expect("remove clamp");
        let restored = get_tcp_window_clamp(&client).expect("get restored");
        assert!(restored == 0 || restored > 128, "expected clamp removed, got {restored}");
    }
}
