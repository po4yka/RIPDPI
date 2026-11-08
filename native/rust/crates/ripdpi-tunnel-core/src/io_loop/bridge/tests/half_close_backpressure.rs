use std::io;
use std::net::Ipv4Addr;
use std::sync::Arc;

use smoltcp::iface::{Interface, SocketHandle, SocketSet};
use smoltcp::socket::tcp::{self, Socket as TcpSocket};
use smoltcp::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncWriteExt, DuplexStream};
use tokio_util::sync::CancellationToken;

use crate::{ActiveSessions, SessionEntry, TunDevice};

use super::super::{pump_active_sessions, remove_session};
use super::half_close::completed_entry;
use super::support::{
    build_ipv4_tcp_ack_packet, build_ipv4_tcp_fin_packet, build_ipv4_tcp_psh_packet, establish_tcp_connection,
};

struct ClosingFlow {
    device: TunDevice,
    iface: Interface,
    sockets: SocketSet<'static>,
    sessions: ActiveSessions,
    handle: SocketHandle,
    sink: DuplexStream,
    payload: Vec<u8>,
    now: Instant,
}

impl ClosingFlow {
    /// # Cancel safety:
    /// cancel-safe: local streams own shutdown state, and the table owns the task before the pump.
    async fn new(payload_len: usize, duplex_size: usize, app_first: bool) -> Self {
        let mut device = TunDevice::new(1500);
        let (mut iface, mut sockets, handle, server_seq) = establish_tcp_connection(&mut device);
        let (smoltcp_side, mut session_side) = tokio::io::duplex(duplex_size);
        session_side.shutdown().await.expect("upstream EOF");
        let (mut writer, sink) = tokio::io::duplex(1);
        let cancel = CancellationToken::new();
        let task_cancel = cancel.clone();
        let task = tokio::spawn(async move {
            let result = tokio::select! {
                biased;
                () = task_cancel.cancelled() => Err(io::ErrorKind::Interrupted.into()),
                result = tokio::io::copy(&mut session_side, &mut writer) => result,
            };
            writer.shutdown().await?;
            result.map(|_| ())
        });
        let mut sessions = ActiveSessions::new(1);
        sessions.insert(
            handle,
            SessionEntry {
                smoltcp_side,
                cancel,
                handle: task,
                pending_to_session: Vec::new(),
                pending_to_smoltcp: Vec::new(),
                upstream_closed: !app_first,
                upstream_failed: Arc::default(),
                pinned_synthetic_ip: None,
                attribution_id: None,
            },
        );
        if !app_first {
            pump_active_sessions(&mut sockets, &mut sessions, &mut None).await;
        }
        let now = Instant::now();
        iface.poll(now, &mut device, &mut sockets);
        device.tx_queue.clear();
        let payload: Vec<u8> = (0..payload_len).map(|value| (value % 251) as u8).collect();
        for (index, chunk) in payload.chunks(1000).enumerate() {
            device.rx_queue.push_back(build_ipv4_tcp_psh_packet(
                Ipv4Addr::new(10, 0, 0, 99),
                Ipv4Addr::LOCALHOST,
                51000,
                443,
                1 + index as u32 * 1000,
                server_seq.wrapping_add(if app_first { 1 } else { 2 }),
                chunk,
            ));
        }
        device.rx_queue.push_back(build_ipv4_tcp_fin_packet(
            Ipv4Addr::new(10, 0, 0, 99),
            Ipv4Addr::LOCALHOST,
            51000,
            443,
            1 + payload_len as u32,
            server_seq.wrapping_add(if app_first { 1 } else { 2 }),
        ));
        iface.poll(now, &mut device, &mut sockets);
        assert_eq!(
            sockets.get::<TcpSocket>(handle).state(),
            if app_first { tcp::State::CloseWait } else { tcp::State::TimeWait }
        );
        pump_active_sessions(&mut sockets, &mut sessions, &mut None).await;
        if app_first {
            assert_eq!(sockets.get::<TcpSocket>(handle).state(), tcp::State::LastAck);
            iface.poll(now, &mut device, &mut sockets);
            device.rx_queue.push_back(build_ipv4_tcp_ack_packet(
                Ipv4Addr::new(10, 0, 0, 99),
                Ipv4Addr::LOCALHOST,
                51000,
                443,
                2 + payload_len as u32,
                server_seq.wrapping_add(2),
            ));
            iface.poll(now, &mut device, &mut sockets);
            assert_eq!(sockets.get::<TcpSocket>(handle).state(), tcp::State::Closed);
        }
        tokio::task::yield_now().await;
        Self { device, iface, sockets, sessions, handle, sink, payload, now }
    }

    fn poll_after(&mut self, seconds: u64) {
        self.iface.poll(self.now + Duration::from_secs(seconds), &mut self.device, &mut self.sockets);
        self.sessions.remove_retired_sockets(&mut self.sockets);
    }

    /// # Cancel safety:
    /// NOT cancel-safe: the test must drive its copy and bridge to EOF before dropping the streams.
    async fn drain(mut self) {
        let maximum_steps = self.payload.len() * 2 + 100;
        let drive = async {
            for _ in 0..maximum_steps {
                pump_active_sessions(&mut self.sockets, &mut self.sessions, &mut None).await;
                if self.sessions.is_empty() {
                    return;
                }
                tokio::task::yield_now().await;
            }
            panic!("normal close did not complete after the sink resumed");
        };
        let read = async {
            let mut received = Vec::new();
            self.sink.read_to_end(&mut received).await.expect("drain slow peer");
            received
        };
        let ((), received) = tokio::join!(drive, read);
        assert!(received == self.payload, "received {} of {} accepted bytes", received.len(), self.payload.len());
    }
}

/// # Cancel safety:
/// NOT cancel-safe: the isolated test runtime owns a deliberately blocked copy until drain completes.
#[tokio::test(flavor = "current_thread", start_paused = true)]
async fn normal_close_can_finish_after_the_shutdown_grace() {
    let mut flow = ClosingFlow::new(17, 64, false).await;
    tokio::time::advance(std::time::Duration::from_secs(6)).await;
    flow.poll_after(6);
    assert!(flow.sessions.contains(flow.handle), "normal slow drain must not use the five-second stop grace");
    flow.drain().await;
}

/// # Cancel safety:
/// NOT cancel-safe: the isolated test runtime owns a deliberately blocked copy until drain completes.
#[tokio::test(flavor = "current_thread", start_paused = true)]
async fn unread_close_bytes_survive_the_time_wait_timeout() {
    let mut flow = ClosingFlow::new(10_000, 8, false).await;
    tokio::time::advance(std::time::Duration::from_secs(11)).await;
    flow.poll_after(11);
    flow.drain().await;
}

/// # Cancel safety:
/// NOT cancel-safe: the isolated runtime owns the blocked writer until the final drain.
#[tokio::test(flavor = "current_thread", start_paused = true)]
async fn normal_close_outside_the_current_work_batch_keeps_its_outcome() {
    let mut flow = ClosingFlow::new(17, 64, false).await;
    let delayed = flow.sessions.remove(flow.handle).expect("move the blocked real relay behind the work batch");
    flow.sessions = ActiveSessions::new(crate::io_loop::IO_PHASE_WORK_BUDGET + 1);
    for _ in 0..crate::io_loop::IO_PHASE_WORK_BUDGET {
        let handle =
            flow.sockets.add(TcpSocket::new(tcp::SocketBuffer::new(vec![0; 64]), tcp::SocketBuffer::new(vec![0; 64])));
        flow.sessions.insert(handle, completed_entry());
    }
    flow.sessions.insert(flow.handle, delayed);
    pump_active_sessions(&mut flow.sockets, &mut flow.sessions, &mut None).await;
    tokio::time::advance(std::time::Duration::from_secs(11)).await;
    flow.poll_after(11);
    flow.drain().await;
}

/// # Cancel safety:
/// cancel-safe: explicit cancellation transfers the task to the existing bounded shutdown owner.
#[tokio::test(flavor = "current_thread", start_paused = true)]
async fn cancelled_close_discards_unread_native_bytes() {
    let mut flow = ClosingFlow::new(10_000, 8, false).await;
    remove_session(flow.handle, &mut flow.sessions, &mut flow.sockets, &mut None);
    assert!(flow.sessions.is_empty());
    assert_eq!(flow.sockets.iter().count(), 0, "cancelled unread RX must not retain an extending close timer");
    tokio::task::yield_now().await;
}

/// # Cancel safety:
/// NOT cancel-safe: the isolated runtime owns the blocked writer until the final drain.
#[tokio::test(flavor = "current_thread", start_paused = true)]
async fn app_first_last_ack_preserves_an_unfinished_forward_drain() {
    ClosingFlow::new(10_000, 8, true).await.drain().await;
}
