use super::*;

fn addresses(ipv6: bool, port: u16) -> (SocketAddr, SocketAddr) {
    if ipv6 {
        (SocketAddr::from(([0x2001, 0xdb8, 0, 0, 0, 0, 0, 1], port)), "[2001:db8::2]:443".parse().unwrap())
    } else {
        (SocketAddr::from(([192, 0, 2, 1], port)), "198.51.100.2:443".parse().unwrap())
    }
}

#[test]
fn fragment_ids_share_the_ip_reassembly_namespace_across_ports() {
    let mut allocator = FragmentIdentifications::default();
    let now = Instant::now();
    let (source, destination) = addresses(false, 40000);
    let mut other_source = source;
    other_source.set_port(40001);
    let mut other_destination = destination;
    other_destination.set_port(8443);
    let first = allocator.reserve(source, destination, 6, Some(7), now).unwrap();
    let second = allocator.reserve(other_source, destination, 6, Some(7), now).unwrap();
    let third = allocator.reserve(source, other_destination, 6, Some(7), now).unwrap();
    assert_eq!([first, second, third], [7, 8, 9]);
    assert_eq!(allocator.reserve(source, destination, 17, Some(7), now).unwrap(), 7);
}

#[test]
fn fragment_ids_never_return_zero_and_skip_live_ids_after_wrap() {
    for ipv6 in [false, true] {
        let mut allocator = FragmentIdentifications::default();
        let now = Instant::now();
        let (source, destination) = addresses(ipv6, 40000);
        assert_ne!(allocator.reserve(source, destination, 6, Some(0), now).unwrap(), 0);
        let limit = if ipv6 { u32::MAX } else { u32::from(u16::MAX) };
        *if ipv6 { &mut allocator.next_ipv6 } else { &mut allocator.next_ipv4 } = limit;
        assert_eq!(allocator.reserve(source, destination, 6, None, now).unwrap(), limit);
        *if ipv6 { &mut allocator.next_ipv6 } else { &mut allocator.next_ipv4 } = limit;
        assert_eq!(allocator.reserve(source, destination, 6, None, now).unwrap(), 2);
    }
}

#[test]
fn ipv6_fragment_ids_are_shared_across_upper_layer_protocols() {
    let mut allocator = FragmentIdentifications::default();
    let now = Instant::now();
    let (source, destination) = addresses(true, 40000);
    let first = allocator.reserve(source, destination, 6, None, now).unwrap();
    allocator.next_ipv6 = first;
    let second = allocator.reserve(source, destination, 17, None, now).unwrap();
    assert_ne!(first, second);
}

#[test]
fn fragment_ids_are_not_reused_before_retention_expires() {
    let mut allocator = FragmentIdentifications::default();
    let now = Instant::now();
    let (source, destination) = addresses(false, 40000);
    assert_eq!(allocator.reserve(source, destination, 6, Some(7), now).unwrap(), 7);
    assert_ne!(
        allocator
            .reserve(source, destination, 6, Some(7), now + IDENTIFICATION_RETENTION - Duration::from_nanos(1))
            .unwrap(),
        7
    );
    assert_eq!(allocator.reserve(source, destination, 6, Some(7), now + IDENTIFICATION_RETENTION).unwrap(), 7);
}

#[test]
fn fragment_id_capacity_never_evicts_live_leases() {
    let mut allocator = FragmentIdentifications::default();
    let now = Instant::now();
    let (source, destination) = addresses(false, 40000);
    for _ in 0..MAX_ACTIVE_IDENTIFICATIONS {
        allocator.reserve(source, destination, 6, None, now).unwrap();
    }
    assert_eq!(allocator.active.len(), MAX_ACTIVE_IDENTIFICATIONS);
    assert_eq!(allocator.reserve(source, destination, 6, None, now).unwrap_err().kind(), io::ErrorKind::WouldBlock);
    assert_eq!(allocator.active.len(), MAX_ACTIVE_IDENTIFICATIONS);
    assert_eq!(allocator.reserve(source, destination, 6, None, now + IDENTIFICATION_RETENTION).unwrap(), 1);
    assert_eq!(allocator.active.len(), 1);
}

#[test]
fn concurrent_fragment_callers_do_not_reuse_preferred_ids() {
    let ids = std::thread::scope(|scope| {
        let handles = (0..16)
            .map(|index| {
                scope.spawn(move || {
                    let source = SocketAddr::from(([192, 0, 2, 222], 41000 + index));
                    let destination = SocketAddr::from(([198, 51, 100, 222], 443));
                    (0..128)
                        .map(|_| reserve_fragment_identification(source, destination, 6, Some(1)).unwrap())
                        .collect::<Vec<_>>()
                })
            })
            .collect::<Vec<_>>();
        handles.into_iter().flat_map(|handle| handle.join().unwrap()).collect::<Vec<_>>()
    });
    assert_eq!(ids.len(), 2048);
    assert_eq!(ids.iter().copied().collect::<HashSet<_>>().len(), ids.len());
}

#[test]
fn a_preferred_id_does_not_rewind_the_default_sequence() {
    let mut allocator = FragmentIdentifications::default();
    let now = Instant::now();
    let (source, destination) = addresses(false, 40000);
    assert_eq!(allocator.reserve(source, destination, 6, Some(100), now).unwrap(), 100);
    assert_eq!(allocator.reserve(source, destination, 6, Some(7), now).unwrap(), 7);
    assert_eq!(allocator.reserve(source, destination, 6, None, now).unwrap(), 101);
}

#[test]
fn invalid_fragment_families_do_not_consume_an_identification() {
    let mut allocator = FragmentIdentifications::default();
    let (source, _) = addresses(false, 40000);
    let (_, destination) = addresses(true, 40000);
    assert_eq!(
        allocator.reserve(source, destination, 6, None, Instant::now()).unwrap_err().kind(),
        io::ErrorKind::InvalidData
    );
    assert!(allocator.active.is_empty());
}
