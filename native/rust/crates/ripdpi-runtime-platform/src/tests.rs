use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::path::Path;

use ripdpi_config::IpIdMode;

use crate::ipv4_ids::{reserve_ipv4_identifications, Ipv4IdAllocator};
use crate::{CapabilityOutcome, CapabilityUnavailable, RuntimeCapability};

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
