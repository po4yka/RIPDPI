use super::super::super::udp_assoc::UdpEvent;
use super::*;

/// # Cancel safety:
/// cancel-safe: the only await is listener binding, before any association exists.
async fn queued_error_state(port: u16) -> (LoopState, SocketAddr, u64, Vec<u8>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
    state.runtime.proxy_sockaddr = listener.local_addr().unwrap();
    let config = ripdpi_tunnel_config::TunnelConfig { ipv4: Some("10.0.0.1/24".into()), ..Default::default() };
    state.runtime.udp_error_originator = super::super::super::udp_errors::UdpErrorOriginator::new(&config);
    let mut packet = Vec::new();
    etherparse::PacketBuilder::ipv4([10, 0, 0, 2], [203, 0, 113, 9], 37)
        .udp(port, 443)
        .write(&mut packet, b"original")
        .unwrap();
    route_tun_packet(&packet, &mut state);
    let src = SocketAddr::from(([10, 0, 0, 2], port));
    let association = &state.udp_associations[&src];
    let id = 1;
    assert!(association.pending_error.is_some());
    (state, src, id, packet)
}

/// # Cancel safety:
/// NOT cancel-safe: dropping this test before shutdown can detach a worker until runtime teardown.
#[tokio::test]
async fn local_error_requires_current_generation_uid_admission_and_live_session() {
    for (index, rejected) in ["none", "association", "registration", "uid", "cancel"].into_iter().enumerate() {
        let (mut state, src, id, original) = queued_error_state(53181 + index as u16).await;
        match rejected {
            "registration" => {
                let registration = state.udp_associations[&src].pending_error.as_ref().unwrap().0;
                ripdpi_flow_app_attribution::evict_flow_if_current(registration);
            }
            "uid" => state.runtime.uid_policy = crate::uid_policy::UidFlowPolicy::enforcing(HashSet::new()),
            "cancel" => state.cancel.cancel(),
            _ => {}
        }
        handle_wait_event(
            &mut state,
            WaitEvent::Udp(Some(UdpEvent::SetupFailed {
                src,
                association_id: if rejected == "association" { id.wrapping_add(1) } else { id },
            })),
        );
        let output = state.device.tx_queue.pop_front();
        let association_remains = state.udp_associations.contains_key(&src);
        state.cancel.cancel();
        state.shutdown().await;
        if rejected == "none" {
            let packet = output.expect("admitted local error");
            assert_eq!(&packet[20..22], &[3, 1]);
            assert_eq!(&packet[28..], &original);
        } else {
            assert!(output.is_none(), "must reject {rejected}");
        }
        assert_eq!(association_remains, rejected == "association");
    }
}

/// # Cancel safety:
/// NOT cancel-safe: dropping this test before shutdown can detach a worker until runtime teardown.
#[tokio::test]
async fn reassembled_udp_never_supplies_a_fabricated_original_quote() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let src = SocketAddr::from(([10, 0, 0, 2], 53190));
    let mut state = test_loop_state(Box::new(RecordingEgressHandler::new(false, Arc::new(Mutex::new(Vec::new())))));
    state.runtime.proxy_sockaddr = listener.local_addr().unwrap();
    let config = ripdpi_tunnel_config::TunnelConfig { ipv4: Some("10.0.0.1/24".into()), ..Default::default() };
    state.runtime.udp_error_originator = super::super::super::udp_errors::UdpErrorOriginator::new(&config);
    let mut packet = Vec::new();
    etherparse::PacketBuilder::ipv4([10, 0, 0, 2], [203, 0, 113, 9], 37)
        .udp(src.port(), 443)
        .write(&mut packet, &[7; 64])
        .unwrap();
    for fragment in ipv4_fragments(&packet, 24) {
        route_tun_packet(&fragment, &mut state);
    }
    let retained = state.udp_associations.get(&src).is_some_and(|a| a.pending_error.is_some());
    let forwarded = state.udp_associations.contains_key(&src);
    state.cancel.cancel();
    state.shutdown().await;
    assert!(forwarded);
    assert!(!retained);
}
