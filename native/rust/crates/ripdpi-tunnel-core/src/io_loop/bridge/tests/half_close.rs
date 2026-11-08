use std::io;
use std::net::Ipv4Addr;
use std::sync::Arc;

use smoltcp::iface::{Interface, SocketHandle, SocketSet};
use smoltcp::socket::tcp::{self, Socket as TcpSocket};
use smoltcp::time::{Duration, Instant};
use tokio_util::sync::CancellationToken;

use crate::{ActiveSessions, SessionEntry, TunDevice};

use super::super::pump_active_sessions;
use super::support::{build_ipv4_tcp_fin_packet, establish_tcp_connection};

pub(super) fn completed_entry() -> SessionEntry {
    let (smoltcp_side, session_side) = tokio::io::duplex(64);
    drop(session_side);
    SessionEntry {
        smoltcp_side,
        cancel: CancellationToken::new(),
        handle: tokio::spawn(async { Ok::<(), io::Error>(()) }),
        pending_to_session: Vec::new(),
        pending_to_smoltcp: Vec::new(),
        upstream_closed: true,
        upstream_failed: Arc::default(),
        pinned_synthetic_ip: None,
        attribution_id: None,
    }
}

fn close_connection(device: &mut TunDevice) -> (Interface, SocketSet<'static>, SocketHandle, Instant, Vec<u8>) {
    let (mut iface, mut sockets, handle, server_seq) = establish_tcp_connection(device);
    sockets.get_mut::<TcpSocket>(handle).close();
    let now = Instant::now();
    iface.poll(now, device, &mut sockets);
    device.tx_queue.clear();
    let fin = build_ipv4_tcp_fin_packet(
        Ipv4Addr::new(10, 0, 0, 99),
        Ipv4Addr::LOCALHOST,
        51000,
        443,
        1,
        server_seq.wrapping_add(2),
    );
    device.rx_queue.push_back(fin.clone());
    iface.poll(now, device, &mut sockets);
    assert_eq!(sockets.get::<TcpSocket>(handle).state(), tcp::State::TimeWait);
    device.tx_queue.clear();
    (iface, sockets, handle, now, fin)
}

/// # Cancel safety:
/// cancel-safe: local sockets and the isolated runtime own the test resources.
#[tokio::test(flavor = "current_thread")]
async fn completed_relay_releases_its_entry_but_keeps_bounded_time_wait() {
    let mut device = TunDevice::new(1500);
    let (mut iface, mut sockets, handle, now, fin) = close_connection(&mut device);
    let mut sessions = ActiveSessions::new(1);
    sessions.insert(handle, completed_entry());
    tokio::task::yield_now().await;

    pump_active_sessions(&mut sockets, &mut sessions, &mut None).await;
    assert!(sessions.is_empty(), "completed relay resources must be released before TIME-WAIT expires");
    assert_eq!(sockets.iter().count(), 1, "the TCP close state must remain after relay removal");
    device.rx_queue.push_back(fin);
    iface.poll(now + Duration::from_millis(20), &mut device, &mut sockets);
    let reply = device.tx_queue.pop_front().expect("ACK for repeated FIN");
    assert_eq!(reply[33] & 0x14, 0x10, "the repeated FIN must receive ACK, not RST");

    iface.poll(now + Duration::from_secs(11), &mut device, &mut sockets);
    sessions.remove_retired_sockets(&mut sockets);
    assert_eq!(sockets.iter().count(), 0, "expired TCP close state must be released");
}

/// # Cancel safety:
/// cancel-safe: local sockets and the isolated runtime own the test resources.
#[tokio::test(flavor = "current_thread")]
async fn new_sessions_evict_retained_close_state_before_live_sessions() {
    let mut device = TunDevice::new(1500);
    let (_iface, mut sockets, closed, _now, _fin) = close_connection(&mut device);
    let mut sessions = ActiveSessions::new(2);
    sessions.insert(closed, completed_entry());
    tokio::task::yield_now().await;
    pump_active_sessions(&mut sockets, &mut sessions, &mut None).await;
    let live = sockets.add(TcpSocket::new(tcp::SocketBuffer::new(vec![0; 64]), tcp::SocketBuffer::new(vec![0; 64])));
    sessions.insert(live, completed_entry());
    let newest = sockets.add(TcpSocket::new(tcp::SocketBuffer::new(vec![0; 64]), tcp::SocketBuffer::new(vec![0; 64])));
    assert_eq!(sessions.insert(newest, completed_entry()), Some((closed, None)));
    assert!(sessions.contains(live), "retained close state must use capacity before evicting a live relay");
    assert_eq!(sessions.len(), 2);
}
