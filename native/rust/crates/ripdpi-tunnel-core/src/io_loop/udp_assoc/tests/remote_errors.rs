use super::*;
use crate::io_loop::packet::UdpErrorQuote;
use crate::uid_policy::{UidFlowPolicy, Verdict};

/// # Cancel safety:
/// NOT cancel-safe: normal completion aborts and joins each owned test worker.
#[tokio::test]
async fn remote_error_requires_live_destination_registration_uid_and_session() {
    for (index, rejected) in
        ["none", "association", "registration", "retired", "replaced", "uid", "cancel"].into_iter().enumerate()
    {
        let src = SocketAddr::from(([10, 0, 0, 2], 54110 + index as u16));
        let dst: SocketAddr = "203.0.113.9:443".parse().unwrap();
        let registration = ripdpi_flow_app_attribution::note_flow(17, src, dst).registration_id;
        let (mut association, _, _) = stalled_udp_association(7, dst);
        let destination = Arc::new(UdpDestination::new(crate::session::TargetAddr::Ip(dst), dst, dst, None));
        association.destinations.insert(dst, Arc::clone(&destination));
        association.attribution_ids.put(registration.request(), registration);
        let mut policy = UidFlowPolicy::disarmed();
        let cancel = CancellationToken::new();
        match rejected {
            "registration" => {
                ripdpi_flow_app_attribution::evict_flow_if_current(registration);
            }
            "retired" => destination.retire(),
            "replaced" => {
                association
                    .destinations
                    .insert(dst, Arc::new(UdpDestination::new(crate::session::TargetAddr::Ip(dst), dst, dst, None)));
            }
            "uid" => policy = UidFlowPolicy::enforcing(std::collections::HashSet::new()),
            "cancel" => cancel.cancel(),
            _ => {}
        }
        let mut original = Vec::new();
        etherparse::PacketBuilder::ipv4([10, 0, 0, 2], [203, 0, 113, 9], 37)
            .udp(src.port(), dst.port())
            .write(&mut original, b"original")
            .unwrap();
        let error = ripdpi_socks5_core::udp_feedback::RemoteError {
            source: "192.0.2.1".parse().unwrap(),
            target: dst,
            icmp_type: 3,
            icmp_code: 3,
            info: 0,
        };
        let raw = UdpErrorQuote::capture(&original).unwrap().build_remote(&error).unwrap();
        let mut associations = HashMap::from([(src, association)]);
        let mut device = TunDevice::new(1500);
        handle_udp_event(
            &mut device,
            &Stats::new(),
            &mut associations,
            &mut BinaryHeap::new(),
            &mut None,
            &mut Vec::new(),
            UdpEvent::RemoteError {
                src,
                association_id: if rejected == "association" { 8 } else { 7 },
                registration_id: registration,
                destination,
                raw,
            },
            |id| !cancel.is_cancelled() && policy.admit_registration(id) == Verdict::Allow,
        );
        assert_eq!(device.tx_queue.len(), usize::from(rejected == "none"), "must gate {rejected}");
        let association = associations.remove(&src).expect("remote error does not close association");
        association.cancel.cancel();
        association.worker.abort();
        let _ = association.worker.await;
    }
}
