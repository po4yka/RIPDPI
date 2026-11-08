use std::collections::{BinaryHeap, HashMap};
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::Duration;

use tokio_util::sync::CancellationToken;

use super::destination_expiry::expire_udp_destinations_at;
use super::event_handling::{UdpEvent, handle_udp_event};
use super::forward_udp_payload;
use super::shutdown::shutdown_udp_associations;
use super::worker::spawn_udp_association;
use crate::dns_cache::DnsCache;
use crate::session::Auth;
use crate::session::udp::UdpMemoryBudget;
use crate::{Stats, TunDevice};

/// # Cancel safety:
/// NOT cancel-safe: the test must complete association shutdown to join its worker.
#[tokio::test]
async fn idle_destination_releases_lease_without_retiring_active_peer() {
    let src: SocketAddr = "10.0.0.2:53992".parse().unwrap();
    let resolved: SocketAddr = "203.0.113.1:443".parse().unwrap();
    let proxy: SocketAddr = "127.0.0.1:9".parse().unwrap();
    let budget = UdpMemoryBudget::for_tunnel_mtu(1500);
    let cancel = CancellationToken::new();
    let (events, _events_rx) = tokio::sync::mpsc::channel(4);
    let association = spawn_udp_association(
        proxy,
        Auth::NoAuth,
        src,
        resolved,
        1,
        Duration::from_secs(60),
        Duration::from_secs(5),
        None,
        budget.clone(),
        cancel.clone(),
        events.clone(),
    );
    let mut associations = HashMap::from([(src, association)]);
    let mut cache = Some(DnsCache::new(0xc612_0000, 0xfffe_0000, 2).unwrap());
    let mut heap = BinaryHeap::new();
    let stats = Arc::new(Stats::new());
    let mut next_id = 2;
    let mut destinations = Vec::new();

    for host in ["idle.example", "active.example"] {
        let ip = cache.as_mut().unwrap().find(host, 0xcb00_7101).unwrap().0;
        let dst = SocketAddr::new(Ipv4Addr::from(ip).into(), 443);
        destinations.push(dst);
        let registration = ripdpi_flow_app_attribution::note_flow(17, src, dst).registration_id;
        assert!(
            forward_udp_payload(
                proxy,
                &Auth::NoAuth,
                src,
                dst,
                resolved,
                Some(host),
                Some(ip),
                b"x",
                None,
                &mut cache,
                &mut Vec::new(),
                &mut associations,
                &mut heap,
                &budget,
                &mut next_id,
                Duration::from_secs(60),
                Duration::from_secs(5),
                None,
                &cancel,
                &events,
                &stats,
                registration,
            )
            .is_some()
        );
    }
    let idle = Arc::clone(&associations[&src].destinations[&destinations[0]]);
    let active = Arc::clone(&associations[&src].destinations[&destinations[1]]);
    idle.last_activity.store(100, Ordering::Relaxed);
    active.last_activity.store(150, Ordering::Relaxed);

    expire_udp_destinations_at(&mut associations, &mut cache, Duration::from_millis(75), 200);

    let idle_ip = u32::from(match destinations[0].ip() {
        std::net::IpAddr::V4(ip) => ip,
        std::net::IpAddr::V6(_) => unreachable!(),
    });
    let active_ip = u32::from(match destinations[1].ip() {
        std::net::IpAddr::V4(ip) => ip,
        std::net::IpAddr::V6(_) => unreachable!(),
    });
    assert_eq!(cache.as_ref().unwrap().lease_count(idle_ip), 0, "idle destination must release its mapping");
    assert_eq!(cache.as_ref().unwrap().lease_count(active_ip), 1, "active peer must retain its mapping");
    assert!(!idle.is_live());
    assert!(active.is_live());
    let replacement = cache.as_mut().unwrap().find("replacement.example", 0xcb00_7102).unwrap().0;
    assert_ne!(replacement, idle_ip, "a retired synthetic address must not be reassigned");
    assert_eq!(associations[&src].destinations.len(), 1);
    let mut device = TunDevice::new(1500);
    let old_response = crate::io_loop::packet::build_udp_response(destinations[0], src, b"old");
    handle_udp_event(
        &mut device,
        &stats,
        &mut associations,
        &mut heap,
        &mut cache,
        &mut Vec::new(),
        UdpEvent::Packet { src, association_id: 1, destination: idle, raw: old_response },
        |_| true,
    );
    assert!(device.tx_queue.is_empty(), "an old queued reply must not use a reassigned synthetic mapping");
    shutdown_udp_associations(&mut associations, &mut cache).await;
}

/// # Cancel safety:
/// NOT cancel-safe: the test must complete association shutdown to join its worker.
#[tokio::test]
async fn mapping_stays_leased_until_its_last_live_destination_expires() {
    let src: SocketAddr = "10.0.0.2:53993".parse().unwrap();
    let resolved: SocketAddr = "203.0.113.1:443".parse().unwrap();
    let budget = UdpMemoryBudget::for_tunnel_mtu(1500);
    let (events, _events_rx) = tokio::sync::mpsc::channel(1);
    let association = spawn_udp_association(
        "127.0.0.1:9".parse().unwrap(),
        Auth::NoAuth,
        src,
        resolved,
        1,
        Duration::from_secs(60),
        Duration::from_secs(5),
        None,
        budget,
        CancellationToken::new(),
        events,
    );
    let mut associations = HashMap::from([(src, association)]);
    let mut cache = Some(DnsCache::new(0xc612_0000, 0xfffe_0000, 1).unwrap());
    let ip = cache.as_mut().unwrap().find("shared.example", 0xcb00_7101).unwrap().0;
    cache.as_mut().unwrap().pin(ip);
    associations.get_mut(&src).unwrap().leased_synthetic_ips.insert(ip);
    for (port, last) in [(443, 100), (8443, 150)] {
        let dst = SocketAddr::new(Ipv4Addr::from(ip).into(), port);
        let target = SocketAddr::new(resolved.ip(), port);
        let destination = Arc::new(super::association_state::UdpDestination::new(
            crate::session::TargetAddr::ResolvedDomain("shared.example".into(), target),
            target,
            dst,
            Some(ip),
        ));
        destination.last_activity.store(last, Ordering::Relaxed);
        associations.get_mut(&src).unwrap().destinations.insert(dst, destination);
    }

    expire_udp_destinations_at(&mut associations, &mut cache, Duration::from_millis(75), 200);
    assert_eq!(cache.as_ref().unwrap().lease_count(ip), 1, "the other port still owns the same mapping");
    assert!(cache.as_mut().unwrap().find("unrelated.example", 0xcb00_7102).is_err());
    expire_udp_destinations_at(&mut associations, &mut cache, Duration::from_millis(75), 225);
    assert_eq!(cache.as_ref().unwrap().lease_count(ip), 0, "release the one shared pin after the last port expires");
    assert!(cache.as_mut().unwrap().find("unrelated.example", 0xcb00_7102).is_ok());
    shutdown_udp_associations(&mut associations, &mut cache).await;
}
