use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::path::Path;

use ripdpi_config::IpIdMode;

use crate::capability::{CapabilityOutcome, CapabilityUnavailable, RuntimeCapability};
use crate::ipv4_ids::{Ipv4IdAllocator, reserve_ipv4_identifications};

#[test]
fn ipv4_id_allocator_seq_is_contiguous_per_flow() {
    let source = SocketAddrV4::new(Ipv4Addr::new(192, 0, 2, 10), 40000);
    let target = SocketAddrV4::new(Ipv4Addr::new(198, 51, 100, 20), 443);
    let mut allocator = Ipv4IdAllocator::default();

    assert_eq!(allocator.reserve(source, target, IpIdMode::Seq, 3), vec![1, 2, 3]);
    assert_eq!(allocator.reserve(source, target, IpIdMode::Seq, 2), vec![4, 5]);
}

#[test]
fn ipv4_id_allocator_seqgroup_uses_same_sequential_scheme() {
    let source = SocketAddrV4::new(Ipv4Addr::new(192, 0, 2, 11), 40001);
    let target = SocketAddrV4::new(Ipv4Addr::new(198, 51, 100, 21), 443);
    let mut allocator = Ipv4IdAllocator::default();

    assert_eq!(allocator.reserve(source, target, IpIdMode::SeqGroup, 2), vec![1, 2]);
    assert_eq!(allocator.reserve(source, target, IpIdMode::SeqGroup, 1), vec![3]);
}

#[test]
fn ipv4_id_allocator_evicts_least_recently_used_flow_at_capacity() {
    let target = SocketAddrV4::new(Ipv4Addr::new(198, 51, 100, 30), 443);
    let flow = |port: usize| SocketAddrV4::new(Ipv4Addr::new(192, 0, 2, 30), u16::try_from(port).unwrap());
    let mut allocator = Ipv4IdAllocator::default();
    let capacity = crate::ipv4_ids::MAX_TRACKED_FLOWS;

    for port in 0..capacity {
        allocator.reserve(flow(port), target, IpIdMode::Seq, 1);
    }
    // Touch flow 0 so flow 1 becomes the least recently used one.
    assert_eq!(allocator.reserve(flow(0), target, IpIdMode::Seq, 1), vec![2]);
    allocator.reserve(flow(capacity), target, IpIdMode::Seq, 1);

    assert_eq!(allocator.tracked_flows(), capacity);
    assert_eq!(allocator.reserve(flow(0), target, IpIdMode::Seq, 1), vec![3], "recent flow keeps its sequence");
    assert_eq!(allocator.reserve(flow(1), target, IpIdMode::Seq, 1), vec![1], "evicted flow restarts");
}

#[cfg(any(target_os = "linux", target_os = "android"))]
#[test]
fn fragment_senders_never_send_ipv4_id_zero() {
    use crate::root_helper::tests::{FakeHelper, TEST_MUTEX};

    let _lock = TEST_MUTEX.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    let upstream = std::net::UdpSocket::bind("127.0.0.1:0").expect("bind udp");
    let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind tcp");
    let stream = std::net::TcpStream::connect(listener.local_addr().expect("listener addr")).expect("connect");
    let target = SocketAddr::from((Ipv4Addr::LOCALHOST, 9));
    let ext = ripdpi_ipfrag::Ipv6ExtHeaders::default();

    // Preflight plus command for each send.
    let helper = FakeHelper::register(serde_json::Value::Null, 4);
    crate::ip_fragmentation::send_ip_fragmented_udp_reserved(
        &upstream,
        target,
        b"payload",
        3,
        64,
        None,
        false,
        ext,
        Some(0),
    )
    .expect("udp fragments");
    crate::ip_fragmentation::send_ip_fragmented_tcp_reserved(
        &stream,
        b"payload",
        3,
        64,
        None,
        false,
        ext,
        crate::TcpFlagOverrides::default(),
        Some(0),
    )
    .expect("tcp fragments");
    let requests = helper.finish();

    let ids = requests
        .iter()
        .filter(|request| request.command != ripdpi_root_helper_protocol::CMD_PROTOCOL_PREFLIGHT)
        .map(|request| request.params["ipv4_identification"].clone())
        .collect::<Vec<_>>();
    assert_eq!(ids, vec![serde_json::Value::Null, serde_json::Value::Null], "an ID of 0 must not reach the sender");
}

#[test]
fn ipv4_id_allocator_zero_returns_zeroes() {
    let source = SocketAddrV4::new(Ipv4Addr::new(192, 0, 2, 12), 40002);
    let target = SocketAddrV4::new(Ipv4Addr::new(198, 51, 100, 22), 443);
    let mut allocator = Ipv4IdAllocator::default();

    assert_eq!(allocator.reserve(source, target, IpIdMode::Zero, 3), vec![0, 0, 0]);
}

#[test]
fn ipv4_id_allocator_rnd_returns_non_zero_values() {
    let source = SocketAddrV4::new(Ipv4Addr::new(192, 0, 2, 13), 40003);
    let target = SocketAddrV4::new(Ipv4Addr::new(198, 51, 100, 23), 443);
    let mut allocator = Ipv4IdAllocator::default();

    let values = allocator.reserve(source, target, IpIdMode::Rnd, 8);

    assert_eq!(values.len(), 8);
    assert!(values.iter().all(|value| *value != 0));
}

#[test]
fn reserve_ipv4_identifications_skips_ipv6_flows() {
    let source = SocketAddr::from((Ipv4Addr::new(192, 0, 2, 14), 40004));
    let target = SocketAddr::from(([0u16, 0, 0, 0, 0, 0, 0, 1], 443));

    assert!(reserve_ipv4_identifications(source, target, Some(IpIdMode::SeqGroup), 2).is_empty());
}

#[test]
fn runtime_does_not_own_linux_platform_implementation() {
    let crate_root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let forbidden_paths = [
        "src/platform/linux.rs",
        "src/platform/linux",
        "src/platform/linux/fake_send.rs",
        "src/platform/linux/ip_fragmentation.rs",
        "src/platform/linux/experimental_tier3.rs",
    ];

    for path in forbidden_paths {
        assert!(
            !crate_root.join(path).exists(),
            "privileged platform operations must live in ripdpi-privileged-ops, not {path}",
        );
    }
}

fn vpn_protect_outcome_when_unregistered() -> CapabilityOutcome<()> {
    use ripdpi_native_protect::protect_socket_via_callback;

    match protect_socket_via_callback(-1) {
        Ok(()) => CapabilityOutcome::Available(()),
        Err(err) if err.kind() == std::io::ErrorKind::NotConnected => CapabilityOutcome::Unavailable {
            capability: RuntimeCapability::VpnProtectCallback,
            reason: CapabilityUnavailable::NotProbed,
        },
        Err(err) => {
            CapabilityOutcome::ProbeFailed { capability: RuntimeCapability::VpnProtectCallback, error: err.to_string() }
        }
    }
}

#[test]
fn vpn_protect_callback_absent_produces_unavailable_outcome() {
    use std::sync::Mutex;

    static PROTECT_TEST_MUTEX: Mutex<()> = Mutex::new(());
    let _guard = PROTECT_TEST_MUTEX.lock().expect("protect test mutex");

    ripdpi_native_protect::unregister_protect_callback();
    assert!(!ripdpi_native_protect::has_protect_callback(), "precondition: no callback registered");

    let outcome = vpn_protect_outcome_when_unregistered();
    match outcome {
        CapabilityOutcome::Unavailable { capability, reason } => {
            assert_eq!(capability, RuntimeCapability::VpnProtectCallback);
            assert_eq!(reason, CapabilityUnavailable::NotProbed);
        }
        other => panic!("expected Unavailable{{VpnProtectCallback, NotProbed}}, got {other:?}"),
    }
}
