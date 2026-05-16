//! Parity tests — each of the five TcpDesyncPlatform sub-traits exercised
//! independently via TestTcpDesyncPlatform, plus a compile-time blanket-impl
//! guard.  No live network sockets are created here; all assertions concern
//! the deterministic behaviour documented for TestTcpDesyncPlatform.

use std::io;
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::time::Duration;

use super::r#trait::{
    TcpDesyncPlatform, TcpFakeSender, TcpFragmentSender, TcpPayloadSender, TcpPlatformCapabilities, TcpSocketOptions,
};
use super::test_support::TestTcpDesyncPlatform;
use super::types::{FakeTcpOptions, OrderedTcpSegment, TcpFlagOverrides, TcpPayloadSegment, TcpStageWait};

// ── Compile-time guard ────────────────────────────────────────────────────────
// If the blanket impl breaks for any concrete implementor the function below
// will stop compiling.
#[allow(dead_code)]
fn _assert_impl<T: TcpDesyncPlatform>() {}

#[allow(dead_code)]
fn _assert_test_platform_impl() {
    _assert_impl::<TestTcpDesyncPlatform>();
}

// ── Helpers ───────────────────────────────────────────────────────────────────

fn connected_pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind");
    let addr = listener.local_addr().expect("addr");
    let client = TcpStream::connect(addr).expect("connect");
    let (server, _) = listener.accept().expect("accept");
    (client, server)
}

fn no_wait() -> TcpStageWait {
    (false, Duration::from_millis(0))
}

fn no_flags() -> TcpFlagOverrides {
    TcpFlagOverrides::default()
}

// ── TcpPlatformCapabilities ───────────────────────────────────────────────────

#[test]
fn capabilities_detect_default_ttl_returns_64() {
    let p = TestTcpDesyncPlatform;
    assert_eq!(p.detect_default_ttl(), Some(64));
}

#[test]
fn capabilities_seqovl_supported_is_false() {
    let p = TestTcpDesyncPlatform;
    assert!(!p.seqovl_supported());
}

#[test]
fn capabilities_supports_fake_retransmit_matches_platform() {
    let p = TestTcpDesyncPlatform;
    let expected = cfg!(any(target_os = "linux", target_os = "android"));
    assert_eq!(p.supports_fake_retransmit(), expected);
}

#[test]
fn capabilities_tcp_segment_hint_returns_none() {
    let p = TestTcpDesyncPlatform;
    let (client, _server) = connected_pair();
    let result = p.tcp_segment_hint(&client);
    assert_eq!(result.unwrap(), None);
}

#[test]
fn capabilities_tcp_activation_state_returns_none() {
    let p = TestTcpDesyncPlatform;
    let (client, _server) = connected_pair();
    let result = p.tcp_activation_state(&client);
    assert_eq!(result.unwrap(), None);
}

// ── TcpSocketOptions ──────────────────────────────────────────────────────────

#[test]
fn socket_options_set_tcp_md5sig_unsupported_off_linux() {
    let p = TestTcpDesyncPlatform;
    let (client, _server) = connected_pair();
    let result = p.set_tcp_md5sig(&client, 16);
    // TestTcpDesyncPlatform always returns Unsupported for md5sig.
    assert_eq!(result.unwrap_err().kind(), io::ErrorKind::Unsupported);
}

#[test]
fn socket_options_wait_tcp_stage_unsupported_off_linux() {
    let p = TestTcpDesyncPlatform;
    let (client, _server) = connected_pair();
    let result = p.wait_tcp_stage(&client, false, Duration::from_millis(1));
    // Returns Unsupported on non-Linux; on Linux it would still return
    // Unsupported from TestTcpDesyncPlatform (it never does real kernel work).
    assert_eq!(result.unwrap_err().kind(), io::ErrorKind::Unsupported);
}

// ── TcpFakeSender ─────────────────────────────────────────────────────────────

#[test]
fn fake_sender_send_fake_rst_returns_unsupported() {
    let p = TestTcpDesyncPlatform;
    let (client, _server) = connected_pair();
    let result = p.send_fake_rst(&client, 64, None, no_flags(), None);
    assert_eq!(result.unwrap_err().kind(), io::ErrorKind::Unsupported);
}

#[test]
fn fake_sender_send_fake_tcp_plain_writes_original_prefix() {
    use std::io::Read;
    let p = TestTcpDesyncPlatform;
    let (client, mut server) = connected_pair();
    let original = b"hello";
    let fake = b"xxxx";
    let options = FakeTcpOptions::default(); // no flag overrides
    let result = p.send_fake_tcp(&client, original, fake, 64, false, 64, options, None, no_wait());
    assert!(result.is_ok(), "send_fake_tcp should succeed: {:?}", result.err());

    let mut buf = vec![0u8; original.len()];
    server.read_exact(&mut buf).expect("read");
    assert_eq!(&buf, original);
}

#[test]
fn fake_sender_send_fake_tcp_with_flags_returns_unsupported() {
    let p = TestTcpDesyncPlatform;
    let (client, _server) = connected_pair();
    let options = FakeTcpOptions { fake_flags: TcpFlagOverrides { set: 1, unset: 0 }, ..Default::default() };
    let result = p.send_fake_tcp(&client, b"a", b"b", 64, false, 64, options, None, no_wait());
    assert_eq!(result.unwrap_err().kind(), io::ErrorKind::Unsupported);
}

// ── TcpPayloadSender ──────────────────────────────────────────────────────────

#[test]
fn payload_sender_send_ordered_tcp_segments_writes_non_fake_timestamp_segments() {
    use std::io::Read;
    let p = TestTcpDesyncPlatform;
    let (client, mut server) = connected_pair();
    let segments = [
        OrderedTcpSegment { payload: b"ab", ttl: 64, flags: no_flags(), sequence_offset: 0, use_fake_timestamp: false },
        OrderedTcpSegment {
            payload: b"skipped",
            ttl: 64,
            flags: no_flags(),
            sequence_offset: 2,
            use_fake_timestamp: true,
        },
        OrderedTcpSegment { payload: b"cd", ttl: 64, flags: no_flags(), sequence_offset: 9, use_fake_timestamp: false },
    ];
    let result = p.send_ordered_tcp_segments(&client, &segments, 4, 64, None, false, None, None, no_wait());
    assert!(result.is_ok(), "send_ordered_tcp_segments should succeed: {:?}", result.err());

    let mut buf = vec![0u8; 4];
    server.read_exact(&mut buf).expect("read");
    assert_eq!(&buf, b"abcd");
}

#[test]
fn payload_sender_send_flagged_tcp_payload_returns_unsupported() {
    let p = TestTcpDesyncPlatform;
    let (client, _server) = connected_pair();
    let result = p.send_flagged_tcp_payload(&client, b"data", 64, None, false, no_flags(), None);
    assert_eq!(result.unwrap_err().kind(), io::ErrorKind::Unsupported);
}

#[test]
fn payload_sender_send_seqovl_tcp_returns_unsupported() {
    let p = TestTcpDesyncPlatform;
    let (client, _server) = connected_pair();
    let result = p.send_seqovl_tcp(&client, b"real", b"fake", 64, None, false, no_flags(), None);
    assert_eq!(result.unwrap_err().kind(), io::ErrorKind::Unsupported);
}

// ── TcpFragmentSender ─────────────────────────────────────────────────────────

#[test]
fn fragment_sender_send_ip_fragmented_tcp_returns_unsupported() {
    let p = TestTcpDesyncPlatform;
    let (client, _server) = connected_pair();
    let result = p.send_ip_fragmented_tcp(
        &client,
        b"payload",
        2,
        64,
        None,
        false,
        ripdpi_ipfrag::Ipv6ExtHeaders::default(),
        no_flags(),
        None,
    );
    assert_eq!(result.unwrap_err().kind(), io::ErrorKind::Unsupported);
}

#[test]
fn fragment_sender_send_multi_disorder_tcp_returns_unsupported() {
    let p = TestTcpDesyncPlatform;
    let (client, _server) = connected_pair();
    let segments = [TcpPayloadSegment { start: 0, end: 3 }];
    let result = p.send_multi_disorder_tcp(&client, b"abc", &segments, 64, None, 0, false, no_flags(), None);
    assert_eq!(result.unwrap_err().kind(), io::ErrorKind::Unsupported);
}

// ── Blanket impl: TcpDesyncPlatform methods reachable via unified trait ────────
//
// These verify that a value typed as `&dyn TcpDesyncPlatform` can call into
// each sub-trait method — proving the blanket impl composes correctly.

#[test]
fn blanket_impl_capabilities_reachable_via_unified_trait() {
    let p = TestTcpDesyncPlatform;
    let dyn_p: &dyn TcpDesyncPlatform = &p;
    assert_eq!(dyn_p.detect_default_ttl(), Some(64));
    assert!(!dyn_p.seqovl_supported());
}

#[test]
fn blanket_impl_fake_sender_reachable_via_unified_trait() {
    let p = TestTcpDesyncPlatform;
    let dyn_p: &dyn TcpDesyncPlatform = &p;
    let (client, _server) = connected_pair();
    let err = dyn_p.send_fake_rst(&client, 64, None, no_flags(), None).unwrap_err();
    assert_eq!(err.kind(), io::ErrorKind::Unsupported);
}

#[test]
fn blanket_impl_fragment_sender_reachable_via_unified_trait() {
    let p = TestTcpDesyncPlatform;
    let dyn_p: &dyn TcpDesyncPlatform = &p;
    let (client, _server) = connected_pair();
    let segments: &[TcpPayloadSegment] = &[];
    let err = dyn_p.send_multi_disorder_tcp(&client, b"x", segments, 64, None, 0, false, no_flags(), None).unwrap_err();
    assert_eq!(err.kind(), io::ErrorKind::Unsupported);
}
