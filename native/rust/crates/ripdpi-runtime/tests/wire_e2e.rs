// Wire-level E2E tests that verify DPI-evasion manipulations are visible
// at the packet/datagram level, not just at the socket option level.
//
// Layer 1: Application-level verification (cross-platform, no privileges)
// Layer 2: AF_PACKET raw capture (Linux + CAP_NET_RAW, gated by env var)
#![cfg(not(feature = "loom"))]

mod support;

use local_network_fixture::{FixtureConfig, FixtureStack};
#[cfg(target_os = "linux")]
use ripdpi_config::{OffsetExpr, TcpChainStep, TcpChainStepKind};
use ripdpi_config::{UdpChainStep, UdpChainStepKind};
#[cfg(target_os = "linux")]
use ripdpi_packets::IS_TCP;
use ripdpi_packets::{parse_quic_initial, IS_UDP};
use std::sync::{Mutex, MutexGuard, OnceLock, PoisonError};
use std::time::Duration;

use support::proxy::{ephemeral_proxy_config, start_proxy};
use support::socks5::{socks_udp_associate, udp_proxy_client, udp_proxy_roundtrip_with_socket};
use support::wire::RecordingUdpServer;

#[allow(dead_code)]
#[path = "../../ripdpi-packets/tests/rust_packet_seeds.rs"]
mod rust_packet_seeds;

static TEST_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

const WIRE_TCP_ECHO_PORT: u16 = 47401;
const WIRE_UDP_ECHO_PORT: u16 = 47402;
const WIRE_TLS_ECHO_PORT: u16 = 47403;
const WIRE_DNS_UDP_PORT: u16 = 47453;
const WIRE_DNS_HTTP_PORT: u16 = 47454;
const WIRE_SOCKS5_PORT: u16 = 47480;
const WIRE_CONTROL_PORT: u16 = 47490;

fn test_guard() -> MutexGuard<'static, ()> {
    TEST_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap_or_else(PoisonError::into_inner)
}

fn wire_e2e_enabled() -> bool {
    matches!(std::env::var("RIPDPI_RUN_WIRE_E2E").as_deref(), Ok("1"))
}

#[cfg(target_os = "linux")]
fn packet_capture_e2e_enabled() -> bool {
    wire_e2e_enabled() && matches!(std::env::var("RIPDPI_RUN_PACKET_CAPTURE_E2E").as_deref(), Ok("1"))
}

fn wire_fixture_config() -> FixtureConfig {
    FixtureConfig {
        tcp_echo_port: WIRE_TCP_ECHO_PORT,
        udp_echo_port: WIRE_UDP_ECHO_PORT,
        tls_echo_port: WIRE_TLS_ECHO_PORT,
        dns_udp_port: WIRE_DNS_UDP_PORT,
        dns_http_port: WIRE_DNS_HTTP_PORT,
        socks5_port: WIRE_SOCKS5_PORT,
        control_port: WIRE_CONTROL_PORT,
        ..FixtureConfig::default()
    }
}

fn udp_fake_burst_config() -> ripdpi_config::RuntimeConfig {
    let mut config =
        ephemeral_proxy_config(&["--ip", "127.0.0.1", "--udp-fake", "3", "--fake-quic-profile", "realistic_initial"]);
    config.groups[0].matches.proto = IS_UDP;
    config.groups[0].actions.ttl = Some(8);
    config
}

fn udp_quic_chain_config() -> ripdpi_config::RuntimeConfig {
    let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
    config.groups[0].matches.proto = IS_UDP;
    config.groups[0].actions.ttl = Some(8);
    config.groups[0].actions.quic_fake_version = 0x1a2b_3c4d;
    config.groups[0].actions.udp_chain = vec![
        UdpChainStep {
            kind: UdpChainStepKind::DummyPrepend,
            count: 1,
            split_bytes: 0,
            activation_filter: None,
            ip_frag_disorder: false,
            ipv6_hop_by_hop: false,
            ipv6_dest_opt: false,
            ipv6_dest_opt2: false,
            ipv6_frag_next_override: None,
        },
        UdpChainStep {
            kind: UdpChainStepKind::QuicSniSplit,
            count: 1,
            split_bytes: 0,
            activation_filter: None,
            ip_frag_disorder: false,
            ipv6_hop_by_hop: false,
            ipv6_dest_opt: false,
            ipv6_dest_opt2: false,
            ipv6_frag_next_override: None,
        },
        UdpChainStep {
            kind: UdpChainStepKind::QuicFakeVersion,
            count: 1,
            split_bytes: 0,
            activation_filter: None,
            ip_frag_disorder: false,
            ipv6_hop_by_hop: false,
            ipv6_dest_opt: false,
            ipv6_dest_opt2: false,
            ipv6_frag_next_override: None,
        },
    ];
    config
}

#[cfg(target_os = "linux")]
fn tcp_multidisorder_config(first_offset: i64, second_offset: i64) -> ripdpi_config::RuntimeConfig {
    let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
    config.groups[0].matches.proto = IS_TCP;
    config.groups[0].actions.tcp_chain = vec![
        TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::absolute(first_offset)),
        TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::absolute(second_offset)),
    ];
    config.network.delay_conn = true;
    config
}

// ── Layer 1: Application-level verification ──

#[test]
fn udp_fake_burst_delivers_expected_datagram_count() {
    if !wire_e2e_enabled() {
        eprintln!("skipping: RIPDPI_RUN_WIRE_E2E!=1");
        return;
    }
    let _guard = test_guard();
    let fixture = FixtureStack::start(wire_fixture_config()).expect("start fixture");

    let config = udp_fake_burst_config();
    let proxy = start_proxy(config, None);
    let (_control, relay) = socks_udp_associate(proxy.port);
    let udp = udp_proxy_client();

    let quic_payload = rust_packet_seeds::quic_initial_with_host(0x0000_0001, "wire.example.test");
    let echoed = udp_proxy_roundtrip_with_socket(&udp, relay, fixture.manifest().udp_echo_port, &quic_payload);
    assert_eq!(echoed, quic_payload, "real payload should echo back intact");

    // Allow time for all echo events to be recorded
    std::thread::sleep(Duration::from_millis(200));
    drop(proxy);

    let events = fixture.events().snapshot();
    let udp_echo_events: Vec<_> =
        events.iter().filter(|e| e.service == "udp_echo" && e.protocol == "udp" && e.detail == "echo").collect();

    // 3 fake datagrams + 1 real = at least 4 echo events
    assert!(
        udp_echo_events.len() >= 4,
        "expected >= 4 UDP echo events (3 fake + 1 real), got {}",
        udp_echo_events.len()
    );
}

#[test]
fn udp_fake_burst_packets_have_quic_long_header() {
    if !wire_e2e_enabled() {
        eprintln!("skipping: RIPDPI_RUN_WIRE_E2E!=1");
        return;
    }
    let _guard = test_guard();
    let recording = RecordingUdpServer::start(WIRE_UDP_ECHO_PORT).expect("start recording server");

    let config = udp_fake_burst_config();
    let proxy = start_proxy(config, None);
    let (_control, relay) = socks_udp_associate(proxy.port);
    let udp = udp_proxy_client();

    let real_payload = rust_packet_seeds::quic_initial_with_host(0x0000_0001, "wire.example.test");
    let echoed = udp_proxy_roundtrip_with_socket(&udp, relay, recording.port(), &real_payload);
    assert_eq!(echoed, real_payload, "real payload should echo back");

    std::thread::sleep(Duration::from_millis(200));
    drop(proxy);

    let packets = recording.snapshot();
    assert!(packets.len() >= 4, "expected >= 4 packets (3 fake + 1 real), got {}", packets.len());

    // With realistic_initial profile, fake packets should have the QUIC long-header bit
    let fakes = &packets[..packets.len() - 1];
    for (i, pkt) in fakes.iter().enumerate() {
        assert!(
            pkt.len() >= 5 && (pkt[0] & 0x80) != 0,
            "fake packet {i}: expected QUIC long header bit, first byte: 0x{:02x}, len: {}",
            pkt.first().copied().unwrap_or(0),
            pkt.len()
        );
    }

    // The last packet should be the real QUIC Initial (proxy sends fakes first)
    assert_eq!(packets.last().unwrap(), &real_payload, "last packet should be the real QUIC Initial");
}

#[test]
fn udp_quic_chain_steps_emit_dummy_split_and_fake_version_packets() {
    if !wire_e2e_enabled() {
        eprintln!("skipping: RIPDPI_RUN_WIRE_E2E!=1");
        return;
    }
    let _guard = test_guard();
    let recording = RecordingUdpServer::start(WIRE_UDP_ECHO_PORT).expect("start recording server");

    let config = udp_quic_chain_config();
    let proxy = start_proxy(config, None);
    let (_control, relay) = socks_udp_associate(proxy.port);
    let udp = udp_proxy_client();

    let real_payload = rust_packet_seeds::quic_initial_with_host(0x0000_0001, "wire.example.test");
    let echoed = udp_proxy_roundtrip_with_socket(&udp, relay, recording.port(), &real_payload);
    assert_eq!(echoed, real_payload, "real payload should echo back");

    std::thread::sleep(Duration::from_millis(200));
    drop(proxy);

    let packets = recording.snapshot();
    assert!(packets.len() >= 4, "expected >= 4 packets (3 prelude + 1 real), got {}", packets.len());

    assert_eq!(packets[0].len(), 64);
    assert_eq!(packets[0][0] & 0x80, 0, "dummy prepend should not look like QUIC long header");

    let split = parse_quic_initial(&packets[1]).expect("parse split SNI prelude");
    assert_eq!(split.host(), b"wire.example.test");

    assert_eq!(&packets[2][1..5], &0x1a2b_3c4du32.to_be_bytes());
    assert_eq!(packets.last().unwrap(), &real_payload, "last packet should be the real QUIC Initial");
}

// ── Layer 2: AF_PACKET capture (Linux + CAP_NET_RAW) ──

#[cfg(target_os = "linux")]
mod af_packet_tests {
    use super::*;
    use support::socks5::socks_connect_ip_round_trip_with_retry;
    use support::wire::capture::LoopbackCapture;
    use support::wire::tcp_options_contain_kind;

    #[test]
    fn af_packet_tcp_window_clamp_produces_small_window() {
        if !packet_capture_e2e_enabled() {
            eprintln!("skipping: RIPDPI_RUN_PACKET_CAPTURE_E2E!=1");
            return;
        }
        let _guard = test_guard();
        let fixture = FixtureStack::start(wire_fixture_config()).expect("start fixture");

        let capture = LoopbackCapture::start(fixture.manifest().tcp_echo_port).expect("start loopback capture");

        let proxy = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1", "--window-clamp", "2"]), None);

        let body =
            socks_connect_ip_round_trip_with_retry(proxy.port, fixture.manifest().tcp_echo_port, b"clamp wire test");
        assert_eq!(body, b"clamp wire test");

        std::thread::sleep(Duration::from_millis(100));
        drop(proxy);

        let tcp_packets = capture.packets_to_port(fixture.manifest().tcp_echo_port);
        assert!(!tcp_packets.is_empty(), "should have captured TCP packets");

        // Non-SYN segments from the proxy should advertise a small window
        let data_segments: Vec<_> = tcp_packets
            .iter()
            .filter(|p| p.tcp_flags.is_some_and(|f| f & 0x02 == 0)) // exclude SYN
            .collect();

        let has_small_window = data_segments.iter().any(|p| p.tcp_window.unwrap_or(u16::MAX) <= 128);

        assert!(
            has_small_window,
            "expected at least one non-SYN segment with window <= 128 (clamp=2), windows: {:?}",
            data_segments.iter().map(|p| p.tcp_window).collect::<Vec<_>>()
        );
    }

    #[test]
    fn af_packet_tcp_strip_timestamps_removes_ts_option() {
        if !packet_capture_e2e_enabled() {
            eprintln!("skipping: RIPDPI_RUN_PACKET_CAPTURE_E2E!=1");
            return;
        }
        let _guard = test_guard();
        let fixture = FixtureStack::start(wire_fixture_config()).expect("start fixture");

        let capture = LoopbackCapture::start(fixture.manifest().tcp_echo_port).expect("start loopback capture");

        let proxy = start_proxy(ephemeral_proxy_config(&["--ip", "127.0.0.1", "--strip-timestamps"]), None);

        let body =
            socks_connect_ip_round_trip_with_retry(proxy.port, fixture.manifest().tcp_echo_port, b"ts wire test");
        assert_eq!(body, b"ts wire test");

        std::thread::sleep(Duration::from_millis(100));
        drop(proxy);

        let tcp_packets = capture.packets_to_port(fixture.manifest().tcp_echo_port);
        assert!(!tcp_packets.is_empty(), "should have captured TCP packets");

        // No segment from proxy should contain TCP Timestamps option (kind=8)
        let has_timestamps =
            tcp_packets.iter().any(|p| p.tcp_options.as_ref().is_some_and(|opts| tcp_options_contain_kind(opts, 8)));

        assert!(
            !has_timestamps,
            "no TCP segment to echo port should contain Timestamps option (kind=8) after stripping"
        );
    }

    #[test]
    fn af_packet_tcp_multidisorder_sends_payload_segments_in_reverse_sequence_order() {
        if !packet_capture_e2e_enabled() {
            eprintln!("skipping: RIPDPI_RUN_PACKET_CAPTURE_E2E!=1");
            return;
        }
        let _guard = test_guard();
        let fixture = FixtureStack::start(wire_fixture_config()).expect("start fixture");
        let capture = LoopbackCapture::start(fixture.manifest().tcp_echo_port).expect("start loopback capture");
        let payload = b"multidisorder wire payload";

        let proxy = start_proxy(tcp_multidisorder_config(5, 14), None);
        let body = socks_connect_ip_round_trip_with_retry(proxy.port, fixture.manifest().tcp_echo_port, payload);
        assert_eq!(body, payload);

        std::thread::sleep(Duration::from_millis(200));
        drop(proxy);

        let tcp_packets = capture.packets_to_port(fixture.manifest().tcp_echo_port);
        let data_segments: Vec<_> = tcp_packets.iter().filter(|packet| !packet.payload.is_empty()).collect();
        assert!(data_segments.len() >= 3, "expected at least 3 outbound payload segments, got {}", data_segments.len());

        let first_three = &data_segments[..3];
        assert_eq!(
            first_three.iter().map(|packet| packet.payload.as_slice()).collect::<Vec<_>>(),
            vec![&payload[14..], &payload[5..14], &payload[..5]]
        );

        let sequence_numbers = first_three
            .iter()
            .map(|packet| packet.tcp_sequence_number.expect("tcp sequence number"))
            .collect::<Vec<_>>();
        assert!(
            sequence_numbers[0] > sequence_numbers[1] && sequence_numbers[1] > sequence_numbers[2],
            "expected descending TCP sequence numbers for reverse send order, got {sequence_numbers:?}"
        );

        let reassembled =
            first_three.iter().rev().flat_map(|packet| packet.payload.iter().copied()).collect::<Vec<_>>();
        assert_eq!(reassembled, payload);
    }

    #[test]
    fn af_packet_udp_fake_burst_ttl_differs_from_real() {
        if !packet_capture_e2e_enabled() {
            eprintln!("skipping: RIPDPI_RUN_PACKET_CAPTURE_E2E!=1");
            return;
        }
        let _guard = test_guard();
        let fixture = FixtureStack::start(wire_fixture_config()).expect("start fixture");

        let capture = LoopbackCapture::start(fixture.manifest().udp_echo_port).expect("start loopback capture");

        let config = udp_fake_burst_config();
        let proxy = start_proxy(config, None);
        let (_control, relay) = socks_udp_associate(proxy.port);
        let udp = udp_proxy_client();

        let quic_payload = rust_packet_seeds::quic_initial_with_host(0x0000_0001, "ttl.example.test");
        let echoed = udp_proxy_roundtrip_with_socket(&udp, relay, fixture.manifest().udp_echo_port, &quic_payload);
        assert_eq!(echoed, quic_payload);

        std::thread::sleep(Duration::from_millis(200));
        drop(proxy);

        let udp_packets = capture.packets_to_port(fixture.manifest().udp_echo_port);
        assert!(udp_packets.len() >= 4, "expected >= 4 UDP packets (3 fake + 1 real), got {}", udp_packets.len());

        // Fakes are sent first with TTL=8, real packet follows with default TTL
        for (i, pkt) in udp_packets.iter().take(3).enumerate() {
            assert_eq!(pkt.ttl, 8, "fake packet {i} should have TTL=8, got {}", pkt.ttl);
        }

        let real_pkt = udp_packets.last().unwrap();
        assert!(real_pkt.ttl > 8, "real packet should have default TTL > 8, got {}", real_pkt.ttl);
    }
}
