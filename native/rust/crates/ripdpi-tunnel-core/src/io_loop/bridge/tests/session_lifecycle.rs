use std::io;
use std::net::Ipv4Addr;
use std::task::Waker;

use smoltcp::socket::tcp::{self, Socket as TcpSocket};
use smoltcp::time::Instant;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

use crate::{ActiveSessions, SessionEntry, TunDevice};

use super::super::session_cleanup::remove_session;
use super::super::{pump_active_sessions, try_read_duplex};
use super::support::{build_ipv4_tcp_fin_packet, establish_tcp_connection};

#[tokio::test]
async fn u24_pump_removes_closed_session() {
    let mut device = TunDevice::new(1500);
    let (_iface, mut socket_set, handle, _server_seq) = establish_tcp_connection(&mut device);

    let cancel = CancellationToken::new();
    let (smoltcp_side, session_side) = tokio::io::duplex(crate::io_loop::DUPLEX_BUF);
    let join_handle: JoinHandle<io::Result<()>> = tokio::spawn(async { Ok(()) });
    let entry = SessionEntry {
        smoltcp_side,
        cancel,
        handle: join_handle,
        pending_to_session: Vec::new(),
        pending_to_smoltcp: Vec::new(),
        upstream_closed: false,
        upstream_failed: std::sync::Arc::default(),
        pinned_synthetic_ip: None,
        attribution_id: None,
    };
    let mut sessions = ActiveSessions::new(8);
    sessions.insert(handle, entry);

    // Drop session side to cause EOF
    drop(session_side);
    tokio::task::yield_now().await;

    // Pump should detect the closed duplex
    let mut dns_cache = None;
    pump_active_sessions(&mut socket_set, &mut sessions, &mut dns_cache).await;

    // The session should have upstream_closed set to true, or be removed entirely
    // depending on whether the TCP socket is still active
    if let Some(entry) = sessions.get_mut(handle) {
        assert!(entry.upstream_closed, "upstream_closed should be set when session side is dropped");
    }
    // If session was removed, that's also valid
}

#[tokio::test]
async fn pump_forwards_app_half_close_as_session_eof() {
    let mut device = TunDevice::new(1500);
    let (mut iface, mut socket_set, handle, server_seq) = establish_tcp_connection(&mut device);
    let (smoltcp_side, mut session_side) = tokio::io::duplex(crate::io_loop::DUPLEX_BUF);
    let entry = SessionEntry {
        smoltcp_side,
        cancel: CancellationToken::new(),
        handle: tokio::spawn(async { Ok(()) }),
        pending_to_session: Vec::new(),
        pending_to_smoltcp: Vec::new(),
        upstream_closed: false,
        upstream_failed: std::sync::Arc::default(),
        pinned_synthetic_ip: None,
        attribution_id: None,
    };
    let mut sessions = ActiveSessions::new(8);
    sessions.insert(handle, entry);

    let fin = build_ipv4_tcp_fin_packet(
        Ipv4Addr::new(10, 0, 0, 99),
        Ipv4Addr::new(127, 0, 0, 1),
        51000,
        443,
        1,
        server_seq + 1,
    );
    device.rx_queue.push_back(fin);
    iface.poll(Instant::now(), &mut device, &mut socket_set);
    assert_eq!(socket_set.get::<TcpSocket>(handle).state(), tcp::State::CloseWait);

    let mut dns_cache = None;
    pump_active_sessions(&mut socket_set, &mut sessions, &mut dns_cache).await;

    let mut buf = [0u8; 8];
    assert!(
        matches!(try_read_duplex(&mut session_side, &mut buf, Waker::noop()), Some(Ok(0))),
        "the app FIN must reach the session as EOF so it can half-close upstream"
    );
    assert!(sessions.get_mut(handle).is_some(), "the session stays open for the upstream reply");
}

#[tokio::test]
async fn u26_pump_upstream_closed_closes_tcp() {
    let mut device = TunDevice::new(1500);
    let (_iface, mut socket_set, handle, _server_seq) = establish_tcp_connection(&mut device);

    let cancel = CancellationToken::new();
    let (smoltcp_side, _session_side) = tokio::io::duplex(crate::io_loop::DUPLEX_BUF);
    let join_handle: JoinHandle<io::Result<()>> = tokio::spawn(async { Ok(()) });
    let entry = SessionEntry {
        smoltcp_side,
        cancel,
        handle: join_handle,
        pending_to_session: Vec::new(),
        pending_to_smoltcp: Vec::new(),
        upstream_closed: true,
        upstream_failed: std::sync::Arc::default(), // Simulate upstream already closed
        pinned_synthetic_ip: None,
        attribution_id: None,
    };
    let mut sessions = ActiveSessions::new(8);
    sessions.insert(handle, entry);

    // Pump should call tcp.close() since upstream_closed and pending empty
    let mut dns_cache = None;
    pump_active_sessions(&mut socket_set, &mut sessions, &mut dns_cache).await;

    // Check that the TCP socket was closed (state should transition from Established)
    // The socket might be removed or in a closing state
    if socket_set.iter().any(|(h, _)| h == handle) {
        let tcp = socket_set.get::<TcpSocket>(handle);
        assert_ne!(
            tcp.state(),
            tcp::State::Established,
            "TCP should no longer be in Established state after upstream close"
        );
    }

    // Cleanup
    let handles: Vec<_> = sessions.iter_mut().map(|(h, _)| h).collect();
    for h in handles {
        if let Some(entry) = sessions.remove(h) {
            entry.cancel.cancel();
            entry.handle.abort();
        }
    }
}

#[tokio::test]
async fn u28_pump_removal_cancels_session_task_token() {
    let mut device = TunDevice::new(1500);
    let (_iface, mut socket_set, handle, _server_seq) = establish_tcp_connection(&mut device);

    let cancel = CancellationToken::new();
    let child_cancel = cancel.child_token();
    let (smoltcp_side, _session_side) = tokio::io::duplex(crate::io_loop::DUPLEX_BUF);
    let session_cancel = child_cancel.clone();
    let join_handle: JoinHandle<io::Result<()>> = tokio::spawn(async move {
        session_cancel.cancelled().await;
        Ok(())
    });
    let entry = SessionEntry {
        smoltcp_side,
        cancel: child_cancel.clone(),
        handle: join_handle,
        pending_to_session: Vec::new(),
        pending_to_smoltcp: Vec::new(),
        upstream_closed: false,
        upstream_failed: std::sync::Arc::default(),
        pinned_synthetic_ip: None,
        attribution_id: None,
    };
    let mut sessions = ActiveSessions::new(8);
    sessions.insert(handle, entry);

    socket_set.get_mut::<TcpSocket>(handle).abort();

    let mut dns_cache = None;
    pump_active_sessions(&mut socket_set, &mut sessions, &mut dns_cache).await;

    assert!(sessions.is_empty(), "inactive TCP sockets should remove their session");
    assert!(child_cancel.is_cancelled(), "session removal must cancel the session task token");
}

#[tokio::test]
async fn removing_an_open_session_resets_the_app_connection() {
    let mut device = TunDevice::new(1500);
    let (mut iface, mut socket_set, handle, _server_seq) = establish_tcp_connection(&mut device);
    let (smoltcp_side, _session_side) = tokio::io::duplex(crate::io_loop::DUPLEX_BUF);
    let entry = SessionEntry {
        smoltcp_side,
        cancel: CancellationToken::new(),
        handle: tokio::spawn(async { Ok(()) }),
        pending_to_session: Vec::new(),
        pending_to_smoltcp: Vec::new(),
        upstream_closed: false,
        upstream_failed: std::sync::Arc::default(),
        pinned_synthetic_ip: None,
        attribution_id: None,
    };
    let mut sessions = ActiveSessions::new(8);
    sessions.insert(handle, entry);

    // A bridge error or a denial removes a session whose socket is still established.
    let mut dns_cache = None;
    remove_session(handle, &mut sessions, &mut socket_set, &mut dns_cache);
    iface.poll(Instant::now(), &mut device, &mut socket_set);
    sessions.remove_retired_sockets(&mut socket_set);

    const TCP_FLAG_RST: u8 = 0x04;
    let sent_rst =
        std::iter::from_fn(|| device.tx_queue.pop_front()).any(|pkt| pkt.len() > 33 && pkt[33] & TCP_FLAG_RST != 0);
    assert!(sent_rst, "the app must get a RST instead of a silent drop");
    assert_eq!(socket_set.iter().count(), 0, "the socket is removed after the poll that sent the RST");
}

#[tokio::test]
async fn a_retired_socket_waits_for_tx_queue_room_to_send_its_reset() {
    let mut device = TunDevice::new(1500);
    let (mut iface, mut socket_set, handle, _server_seq) = establish_tcp_connection(&mut device);
    let (smoltcp_side, _session_side) = tokio::io::duplex(crate::io_loop::DUPLEX_BUF);
    let entry = SessionEntry {
        smoltcp_side,
        cancel: CancellationToken::new(),
        handle: tokio::spawn(async { Ok(()) }),
        pending_to_session: Vec::new(),
        pending_to_smoltcp: Vec::new(),
        upstream_closed: false,
        upstream_failed: std::sync::Arc::default(),
        pinned_synthetic_ip: None,
        attribution_id: None,
    };
    let mut sessions = ActiveSessions::new(8);
    sessions.insert(handle, entry);
    // Bulk flows filled the TUN TX queue, so the next poll cannot emit anything.
    while device.tx_queue.push_back(vec![1]) {}

    let mut dns_cache = None;
    remove_session(handle, &mut sessions, &mut socket_set, &mut dns_cache);
    iface.poll(Instant::now(), &mut device, &mut socket_set);
    sessions.remove_retired_sockets(&mut socket_set);
    assert_eq!(socket_set.iter().count(), 1, "the socket stays until its RST went out");

    device.tx_queue.clear();
    iface.poll(Instant::now(), &mut device, &mut socket_set);
    sessions.remove_retired_sockets(&mut socket_set);

    const TCP_FLAG_RST: u8 = 0x04;
    let sent_rst =
        std::iter::from_fn(|| device.tx_queue.pop_front()).any(|pkt| pkt.len() > 33 && pkt[33] & TCP_FLAG_RST != 0);
    assert!(sent_rst, "the app must get the RST once the queue has room");
    assert_eq!(socket_set.iter().count(), 0, "the socket is removed after the poll that sent the RST");
}

#[tokio::test]
async fn failed_upstream_resets_the_app_connection_instead_of_closing_it() {
    let mut device = TunDevice::new(1500);
    let (mut iface, mut socket_set, handle, _server_seq) = establish_tcp_connection(&mut device);
    let (smoltcp_side, session_side) = tokio::io::duplex(crate::io_loop::DUPLEX_BUF);
    let entry = SessionEntry {
        smoltcp_side,
        cancel: CancellationToken::new(),
        handle: tokio::spawn(async { Err(io::Error::from(io::ErrorKind::ConnectionRefused)) }),
        pending_to_session: Vec::new(),
        pending_to_smoltcp: Vec::new(),
        upstream_closed: false,
        upstream_failed: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(true)),
        pinned_synthetic_ip: None,
        attribution_id: None,
    };
    let mut sessions = ActiveSessions::new(8);
    sessions.insert(handle, entry);
    // The session task failed (the proxy refused the CONNECT) and closed its duplex end.
    drop(session_side);

    let mut dns_cache = None;
    pump_active_sessions(&mut socket_set, &mut sessions, &mut dns_cache).await;
    iface.poll(Instant::now(), &mut device, &mut socket_set);

    const TCP_FLAG_FIN: u8 = 0x01;
    const TCP_FLAG_RST: u8 = 0x04;
    let flags: Vec<u8> = std::iter::from_fn(|| device.tx_queue.pop_front()).map(|pkt| pkt[33]).collect();
    assert!(flags.iter().any(|f| f & TCP_FLAG_RST != 0), "a failed upstream must reach the app as a RST: {flags:?}");
    assert!(flags.iter().all(|f| f & TCP_FLAG_FIN == 0), "a failed upstream must not look like a clean close");
}
