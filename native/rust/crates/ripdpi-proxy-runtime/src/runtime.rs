mod adaptive;
mod desync;
mod handshake;
mod listeners;
mod morph;
mod relay;
mod reprobe;
mod retry;
mod routing;
mod state;
mod udp;
mod warmup;

use std::io;
use std::net::TcpListener;

use ripdpi_proxy_runtime_adapter::config::RuntimeConfig;

use self::listeners::{build_listener, run_proxy_with_listener_internal};
use ripdpi_proxy_runtime_adapter::runtime_api::EmbeddedProxyControl;

pub fn run_proxy(config: RuntimeConfig) -> io::Result<()> {
    let listener = create_listener(&config)?;
    run_proxy_with_listener(config, listener)
}

pub fn create_listener(config: &RuntimeConfig) -> io::Result<TcpListener> {
    ripdpi_proxy_runtime_adapter::raw_packet_requirements::validate_ip_fragmentation_support(config)?;
    build_listener(config)
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
    use crate::runtime::desync::send_with_group;
    use crate::runtime::routing::{advance_route_for_failure, select_route};
    use crate::runtime::state::RuntimeState;
    #[cfg(not(feature = "loom"))]
    use crate::sync::{Arc, AtomicUsize};
    use ripdpi_packets::{DEFAULT_FAKE_TLS, IS_HTTPS};
    use ripdpi_proxy_runtime_adapter::config::{
        DesyncGroup, OffsetExpr, TcpChainStep, TcpChainStepKind, DETECT_CONNECT, DETECT_HTTP_LOCAT,
    };
    use ripdpi_proxy_runtime_adapter::session::{
        encode_http_connect_reply, encode_socks4_reply, encode_socks5_reply, OutboundProgress, S_ATP_I4, S_ATP_I6,
        S_CMD_CONN, S_ER_CONN, S_VER5,
    };
    use std::io::Read;
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpListener, TcpStream};
    #[cfg(not(feature = "loom"))]
    use std::sync::atomic::Ordering;
    use std::thread;

    use super::routing::{encode_upstream_socks_connect, failure_penalizes_strategy, failure_trigger_mask};
    use ripdpi_proxy_runtime_adapter::failure::{ClassifiedFailure, FailureAction, FailureClass, FailureStage};

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
        use ripdpi_proxy_runtime_adapter::config::{
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

    // -----------------------------------------------------------------------
    // CapabilitySkipped routing invariants (slice 2.5 regression)
    // -----------------------------------------------------------------------

    /// Regression (slice 2.5): `FailureClass::CapabilitySkipped` must:
    ///   1. produce zero `failure_trigger_mask` (no wire-visible block signal),
    ///   2. return `false` from `failure_penalizes_strategy` (capability-skipped
    ///      runs must not penalise the strategy in downstream learning), and
    ///   3. serialize to the exact string `"capability_skipped"`.
    ///
    /// This pins the no-penalty contract that slice 2.6 will consume when
    /// excluding capability-skipped runs from strategy learning.
    #[test]
    fn capability_skipped_has_zero_trigger_mask_no_penalty_and_correct_str() {
        let failure = ClassifiedFailure::new(
            FailureClass::CapabilitySkipped,
            FailureStage::FirstWrite,
            FailureAction::RetryWithMatchingGroup,
            "capability unavailable: ttl_write",
        );

        assert_eq!(failure_trigger_mask(&failure), 0, "CapabilitySkipped must not trigger any block-detection signal");
        assert!(!failure_penalizes_strategy(&failure), "CapabilitySkipped must not penalise the strategy");
        assert_eq!(failure.class.as_str(), "capability_skipped", "CapabilitySkipped string form must be stable");
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

        let config = ripdpi_proxy_runtime_adapter::config::RuntimeConfig {
            groups: vec![primary, fallback],
            ..ripdpi_proxy_runtime_adapter::config::RuntimeConfig::default()
        };
        let state = RuntimeState::test(config.clone());

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
}
