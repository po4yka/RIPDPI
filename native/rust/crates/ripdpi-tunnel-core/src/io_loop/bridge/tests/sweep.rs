use std::os::unix::net::UnixDatagram;
use std::sync::Arc;
use std::time::Duration;

use smoltcp::iface::{Interface, SocketSet};
use smoltcp::socket::tcp::{Socket as TcpSocket, SocketBuffer};
use smoltcp::time::Instant;
use tokio::io::AsyncReadExt;
use tokio_util::sync::CancellationToken;

use crate::device::PacketFd;
use crate::io_loop::IO_PHASE_WORK_BUDGET;
use crate::io_loop::setup::setup_io_loop;
use crate::io_loop::wait::wait_for_next_event;
use crate::{ActiveSessions, Stats, TunDevice};

use super::super::pump_active_sessions;
use super::half_close::{close_connection, completed_entry};
use super::support::{build_ipv4_tcp_psh_packet, establish_tcp_connection};

fn loop_state(
    device: TunDevice,
    iface: Interface,
    sockets: SocketSet<'static>,
    sessions: ActiveSessions,
) -> crate::io_loop::state::LoopState {
    let config = "schemaVersion: 2\nsocks5: {port: 1080, address: 127.0.0.1}\n".parse().expect("local config");
    setup_io_loop(
        device,
        iface,
        sockets,
        sessions,
        Arc::new(config),
        CancellationToken::new(),
        Arc::new(Stats::new()),
        None,
    )
    .expect("local loop state")
}

/// # Cancel safety:
/// cancel-safe: yield and the bounded pump keep local ownership; event waits and their timeouts
/// use cancel-safe readiness, Notify, and timer futures. The local state owns tasks and descriptors.
#[tokio::test(flavor = "current_thread", start_paused = true)]
async fn one_coalesced_bridge_wake_reaches_sessions_after_the_first_batch() {
    let mut device = TunDevice::new(1500);
    let (mut iface, mut sockets, time_wait, _, _) = close_connection(&mut device);
    let mut sessions = ActiveSessions::new(0);
    sessions.retire_socket(&mut sockets, time_wait);
    let handles: Vec<_> = (0..=IO_PHASE_WORK_BUDGET)
        .map(|_| {
            let handle = sockets.add(TcpSocket::new(SocketBuffer::new(vec![0; 64]), SocketBuffer::new(vec![0; 64])));
            sessions.insert(handle, completed_entry());
            handle
        })
        .collect();
    tokio::task::yield_now().await;
    sessions.next_work_batch(sessions.len()); // Finish the initial admission sweep before the shared wake.
    // No data or native timer can wake this idle table before the TIME-WAIT deadline.
    assert!(iface.poll_delay(Instant::now(), &sockets).expect("TIME-WAIT deadline").total_millis() > 1000);
    let mut state = loop_state(device, iface, sockets, sessions);
    let (fd, _peer) = UnixDatagram::pair().expect("idle packet pair");
    let tun = PacketFd::new(fd.into()).expect("packet descriptor");
    state.sessions.bridge_waker().wake_by_ref();
    state.sessions.bridge_waker().wake_by_ref();
    wait_for_next_event(&tun, &mut state, false).await.expect("coalesced bridge event");
    pump_active_sessions(&mut state.socket_set, &mut state.sessions, &mut None).await;
    let last = *handles.last().expect("session beyond work budget");
    assert!(state.sessions.contains(last), "the first bounded pump cannot visit session 65");
    tokio::time::timeout(Duration::from_millis(1), wait_for_next_event(&tun, &mut state, false))
        .await
        .expect("one shared wake must schedule the remaining bounded bridge work")
        .expect("immediate bridge continuation");
    pump_active_sessions(&mut state.socket_set, &mut state.sessions, &mut None).await;
    assert!(!state.sessions.contains(last), "the terminal session must be retired before the TCP timer");
    assert!(state.sessions.is_empty());
    assert!(
        tokio::time::timeout(Duration::from_millis(1), wait_for_next_event(&tun, &mut state, false)).await.is_err(),
        "a completed sweep must sleep instead of busy-spinning"
    );
}

/// # Cancel safety:
/// cancel-safe: the pump has no suspension; timed event waits use cancel-safe readiness and Notify.
/// A timeout can cancel read_exact, but its stream and byte buffer belong only to this test.
#[tokio::test(flavor = "current_thread", start_paused = true)]
async fn native_ingress_reaches_a_relay_after_the_first_work_batch() {
    check_native_ingress(false).await;
}

/// # Cancel safety:
/// cancel-safe: the helper owns all streams and state across its event-wait and read timeouts.
#[tokio::test(flavor = "current_thread", start_paused = true)]
async fn a_relay_admitted_after_native_ingress_gets_its_first_work_batch() {
    check_native_ingress(true).await;
}

/// # Cancel safety:
/// cancel-safe: the pump does not suspend; event waits use cancel-safe readiness and Notify.
/// A timeout can cancel read_exact, but its stream and byte buffer belong only to this test.
async fn check_native_ingress(admit_after_poll: bool) {
    let mut device = TunDevice::new(1500);
    let (iface, mut sockets, target, server_seq) = establish_tcp_connection(&mut device);
    let mut sessions = ActiveSessions::new(0);
    for _ in 0..IO_PHASE_WORK_BUDGET {
        let handle = sockets.add(TcpSocket::new(SocketBuffer::new(vec![0; 64]), SocketBuffer::new(vec![0; 64])));
        sessions.insert(handle, completed_entry());
    }
    let (smoltcp_side, mut relay) = tokio::io::duplex(64);
    let mut entry = completed_entry();
    entry.smoltcp_side = smoltcp_side;
    entry.upstream_closed = false;
    let mut entry = Some(entry);
    if !admit_after_poll {
        sessions.insert(target, entry.take().expect("early admission"));
    }
    sessions.next_work_batch(sessions.len()); // Isolate the new native event from initial admission.
    sockets.get_mut::<TcpSocket>(target).set_ack_delay(None);
    let mut state = loop_state(device, iface, sockets, sessions);
    let payload = b"native accepted bytes";
    state.device.rx_queue.push_back(build_ipv4_tcp_psh_packet(
        std::net::Ipv4Addr::new(10, 0, 0, 99),
        std::net::Ipv4Addr::LOCALHOST,
        51000,
        443,
        1,
        server_seq.wrapping_add(1),
        payload,
    ));
    crate::io_loop::phases::poll_smoltcp(&mut state);
    assert!(state.socket_set.get::<TcpSocket>(target).can_recv());
    if admit_after_poll {
        state.sessions.insert(target, entry.take().expect("admission after native poll"));
    }
    pump_active_sessions(&mut state.socket_set, &mut state.sessions, &mut None).await;
    state.device.tx_queue.clear(); // The production loop flushes TUN output before it waits.
    let (fd, _peer) = UnixDatagram::pair().expect("idle packet pair");
    let tun = PacketFd::new(fd.into()).expect("packet descriptor");
    tokio::time::timeout(Duration::from_millis(1), wait_for_next_event(&tun, &mut state, false))
        .await
        .expect("native socket change must schedule every affected relay")
        .expect("immediate native continuation");
    pump_active_sessions(&mut state.socket_set, &mut state.sessions, &mut None).await;
    let mut received = [0; 21];
    tokio::time::timeout(Duration::from_millis(1), relay.read_exact(&mut received))
        .await
        .expect("native bytes reach relay before a poll timer")
        .expect("relay bytes");
    assert_eq!(&received, payload);
}
