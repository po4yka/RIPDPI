use super::*;
use crate::io_loop::udp_assoc::shutdown::shutdown_udp_associations;
use crate::io_loop::udp_assoc::worker::spawn_udp_association;
use crate::session::Auth;
use crate::session::udp::UdpMemoryBudget;
use tokio_util::sync::CancellationToken;

fn associations(src: SocketAddr) -> HashMap<SocketAddr, UdpAssociation> {
    let (events, _events_rx) = tokio::sync::mpsc::channel(1);
    let destination: SocketAddr = "203.0.113.1:443".parse().unwrap();
    HashMap::from([(
        src,
        spawn_udp_association(
            "127.0.0.1:9".parse().unwrap(),
            Auth::NoAuth,
            src,
            destination,
            1,
            Duration::from_secs(60),
            Duration::from_secs(5),
            None,
            UdpMemoryBudget::for_tunnel_mtu(1500),
            CancellationToken::new(),
            events,
        ),
    )])
}

/// # Cancel safety:
/// NOT cancel-safe: the test must complete association shutdown to join its worker.
#[tokio::test]
async fn idle_current_destination_gets_a_fresh_identity_before_enqueue() {
    let src: SocketAddr = "10.0.0.2:53994".parse().unwrap();
    let dst: SocketAddr = "203.0.113.1:443".parse().unwrap();
    let mut associations = associations(src);
    let retired = Arc::new(UdpDestination::new_at(TargetAddr::Ip(dst), dst, dst, None, 100));
    associations.get_mut(&src).unwrap().destinations.insert(dst, Arc::clone(&retired));

    let current = prepare_udp_destination(
        &mut associations,
        &mut None,
        src,
        TargetAddr::Ip(dst),
        dst,
        dst,
        None,
        Duration::from_millis(75),
        200,
    )
    .expect("an expired destination must not block a new datagram");

    assert!(!retired.is_live());
    assert!(current.is_live());
    assert!(!Arc::ptr_eq(&retired, &current));
    assert!(!current.is_idle_at(Duration::from_millis(75), 200));
    assert!(associations[&src].destinations.is_empty(), "preparation alone must not register an unqueued destination");
    shutdown_udp_associations(&mut associations, &mut None).await;
}

/// # Cancel safety:
/// NOT cancel-safe: the test must complete association shutdown to join its worker.
#[tokio::test]
async fn full_destination_admission_reclaims_only_expired_entries() {
    let src: SocketAddr = "10.0.0.2:53995".parse().unwrap();
    let mut associations = associations(src);
    let mut first = None;
    for port in 1..=256 {
        let dst = SocketAddr::new("203.0.113.1".parse().unwrap(), port);
        let last = if port == 1 { 100 } else { 150 };
        let destination = Arc::new(UdpDestination::new_at(TargetAddr::Ip(dst), dst, dst, None, last));
        if port == 1 {
            first = Some(Arc::clone(&destination));
        }
        associations.get_mut(&src).unwrap().destinations.insert(dst, destination);
    }
    let excess: SocketAddr = "203.0.113.2:443".parse().unwrap();
    assert!(
        prepare_udp_destination(
            &mut associations,
            &mut None,
            src,
            TargetAddr::Ip(excess),
            excess,
            excess,
            None,
            Duration::from_millis(75),
            200,
        )
        .is_some()
    );
    assert!(!first.unwrap().is_live());
    assert_eq!(associations[&src].destinations.len(), 255);
    assert!(associations[&src].destinations.values().all(|destination| destination.is_live()));
    shutdown_udp_associations(&mut associations, &mut None).await;
}
