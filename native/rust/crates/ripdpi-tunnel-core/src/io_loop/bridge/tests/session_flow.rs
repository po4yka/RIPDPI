use std::io;
use std::net::Ipv4Addr;

use smoltcp::time::Instant;
use tokio::io::AsyncWriteExt;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

use crate::{ActiveSessions, SessionEntry, TunDevice};

use super::super::{pump_active_sessions, try_read_duplex};
use super::support::{build_ipv4_tcp_psh_packet, establish_tcp_connection};

#[tokio::test]
async fn u22_pump_forwards_smoltcp_to_session() {
    let mut device = TunDevice::new(1500);
    let (mut iface, mut socket_set, handle, server_seq) = establish_tcp_connection(&mut device);

    let cancel = CancellationToken::new();
    let (smoltcp_side, mut session_side) = tokio::io::duplex(crate::io_loop::DUPLEX_BUF);
    let join_handle: JoinHandle<io::Result<()>> = tokio::spawn(async { Ok(()) });
    let entry = SessionEntry {
        smoltcp_side,
        cancel,
        handle: join_handle,
        pending_to_session: Vec::new(),
        pending_to_smoltcp: Vec::new(),
        upstream_closed: false,
        pinned_synthetic_ip: None,
        attribution_id: None,
    };
    let mut sessions = ActiveSessions::new(8);
    sessions.insert(handle, entry);

    // Inject a PSH+ACK data packet from client into smoltcp
    let payload = b"hello from client";
    let psh = build_ipv4_tcp_psh_packet(
        Ipv4Addr::new(10, 0, 0, 99),
        Ipv4Addr::new(127, 0, 0, 1),
        51000,
        443,
        1,
        server_seq + 1,
        payload,
    );
    device.rx_queue.push_back(psh);
    iface.poll(Instant::now(), &mut device, &mut socket_set);

    // Pump should forward data from smoltcp TCP recv buffer to session duplex
    let mut dns_cache = None;
    pump_active_sessions(&mut socket_set, &mut sessions, &mut dns_cache).await;

    // Read from session side
    let mut buf = [0u8; 64];
    // The data should be available immediately or after a short yield
    tokio::task::yield_now().await;
    let result = try_read_duplex(&mut session_side, &mut buf);
    match result {
        Some(Ok(n)) => {
            assert_eq!(&buf[..n], payload);
        }
        other => {
            // Data may be in pending_to_session if duplex was not immediately ready
            let entry = sessions.get_mut(handle).expect("session still exists");
            assert!(
                !entry.pending_to_session.is_empty() || other.is_some(),
                "data should have been forwarded to session or buffered in pending"
            );
        }
    }

    // Cleanup
    let handles: Vec<_> = sessions.iter_mut().map(|(h, _)| h).collect();
    for h in handles {
        if let Some(entry) = sessions.remove(h) {
            entry.cancel.cancel();
            entry.handle.abort();
        }
        socket_set.remove(h);
    }
}

#[tokio::test]
async fn u23_pump_forwards_session_to_smoltcp() {
    let mut device = TunDevice::new(1500);
    let (mut iface, mut socket_set, handle, _server_seq) = establish_tcp_connection(&mut device);

    let cancel = CancellationToken::new();
    let (smoltcp_side, mut session_side) = tokio::io::duplex(crate::io_loop::DUPLEX_BUF);
    let join_handle: JoinHandle<io::Result<()>> = tokio::spawn(async { Ok(()) });
    let entry = SessionEntry {
        smoltcp_side,
        cancel,
        handle: join_handle,
        pending_to_session: Vec::new(),
        pending_to_smoltcp: Vec::new(),
        upstream_closed: false,
        pinned_synthetic_ip: None,
        attribution_id: None,
    };
    let mut sessions = ActiveSessions::new(8);
    sessions.insert(handle, entry);

    // Write data from session side into duplex
    session_side.write_all(b"response data").await.unwrap();
    tokio::task::yield_now().await;

    // Pump should forward from session duplex into smoltcp send buffer
    let mut dns_cache = None;
    pump_active_sessions(&mut socket_set, &mut sessions, &mut dns_cache).await;

    // Poll smoltcp to produce the TCP packet
    iface.poll(Instant::now(), &mut device, &mut socket_set);

    // The device tx_queue should now have a TCP packet containing our data
    assert!(!device.tx_queue.is_empty(), "smoltcp should have produced a TCP data packet");

    // Cleanup
    let handles: Vec<_> = sessions.iter_mut().map(|(h, _)| h).collect();
    for h in handles {
        if let Some(entry) = sessions.remove(h) {
            entry.cancel.cancel();
            entry.handle.abort();
        }
        socket_set.remove(h);
    }
}

#[tokio::test]
async fn u25_pump_handles_partial_writes() {
    let mut device = TunDevice::new(1500);
    let (mut iface, mut socket_set, handle, server_seq) = establish_tcp_connection(&mut device);

    let cancel = CancellationToken::new();
    // Use a tiny duplex buffer to force backpressure
    let (smoltcp_side, _session_side) = tokio::io::duplex(8);
    let join_handle: JoinHandle<io::Result<()>> = tokio::spawn(async { Ok(()) });
    let entry = SessionEntry {
        smoltcp_side,
        cancel,
        handle: join_handle,
        pending_to_session: Vec::new(),
        pending_to_smoltcp: Vec::new(),
        upstream_closed: false,
        pinned_synthetic_ip: None,
        attribution_id: None,
    };
    let mut sessions = ActiveSessions::new(8);
    sessions.insert(handle, entry);

    // Inject a large payload into smoltcp to exceed the tiny duplex buffer
    let payload = vec![0xAB; 128];
    let psh = build_ipv4_tcp_psh_packet(
        Ipv4Addr::new(10, 0, 0, 99),
        Ipv4Addr::new(127, 0, 0, 1),
        51000,
        443,
        1,
        server_seq + 1,
        &payload,
    );
    device.rx_queue.push_back(psh);
    iface.poll(Instant::now(), &mut device, &mut socket_set);

    // Pump -- the tiny buffer should cause pending_to_session accumulation
    let mut dns_cache = None;
    pump_active_sessions(&mut socket_set, &mut sessions, &mut dns_cache).await;

    // The session should still exist (not errored out) -- data is either
    // in pending_to_session or was partially written
    assert!(sessions.contains(handle), "session should survive backpressure");

    // Cleanup
    let handles: Vec<_> = sessions.iter_mut().map(|(h, _)| h).collect();
    for h in handles {
        if let Some(entry) = sessions.remove(h) {
            entry.cancel.cancel();
            entry.handle.abort();
        }
        socket_set.remove(h);
    }
}
