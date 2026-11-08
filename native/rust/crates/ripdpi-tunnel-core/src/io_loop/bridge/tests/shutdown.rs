use std::io;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use smoltcp::iface::SocketSet;
use smoltcp::socket::tcp::{self, Socket as TcpSocket};
use tokio::task::JoinHandle;
use tokio::time::Instant;
use tokio_util::sync::CancellationToken;

use crate::{ActiveSessions, SessionEntry, Stats, TunDevice};

use super::super::shutdown_active_sessions;
use super::support::establish_tcp_connection;

struct TaskAlive(Arc<AtomicBool>);

impl Drop for TaskAlive {
    fn drop(&mut self) {
        self.0.store(false, Ordering::Release);
    }
}

fn insert_stalled_session(
    sessions: &mut ActiveSessions,
    socket_set: &mut SocketSet<'static>,
    _target_addr: &str,
) -> (CancellationToken, Arc<AtomicBool>, tokio::sync::oneshot::Receiver<()>) {
    let socket = TcpSocket::new(tcp::SocketBuffer::new(vec![0; 256]), tcp::SocketBuffer::new(vec![0; 256]));
    let socket_handle = socket_set.add(socket);
    let cancel = CancellationToken::new();
    let alive = Arc::new(AtomicBool::new(true));
    let task_alive = TaskAlive(alive.clone());
    let (started_tx, started_rx) = tokio::sync::oneshot::channel();
    let task = tokio::spawn(async move {
        let _task_alive = task_alive;
        let _ = started_tx.send(());
        std::future::pending::<io::Result<()>>().await
    });
    let (smoltcp_side, _session_side) = tokio::io::duplex(crate::io_loop::DUPLEX_BUF);
    let entry = SessionEntry {
        smoltcp_side,
        cancel: cancel.clone(),
        handle: task,
        pending_to_session: Vec::new(),
        pending_to_smoltcp: Vec::new(),
        upstream_closed: false,
        upstream_failed: std::sync::Arc::default(),
        pinned_synthetic_ip: None,
        attribution_id: None,
    };
    assert!(sessions.insert(socket_handle, entry).is_none());
    (cancel, alive, started_rx)
}

#[tokio::test]
async fn u27_shutdown_cancels_all() {
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

    assert!(!child_cancel.is_cancelled());

    let mut dns_cache = None;
    shutdown_active_sessions(&mut sessions, &mut socket_set, &mut dns_cache).await;

    assert!(child_cancel.is_cancelled(), "session cancel token must be cancelled after shutdown");
    assert!(sessions.is_empty(), "all sessions should be removed after shutdown");
}

#[tokio::test(start_paused = true)]
async fn shutdown_uses_one_grace_period_and_aborts_stalled_session_tasks() {
    let mut socket_set = SocketSet::new(vec![]);
    let mut sessions = ActiveSessions::new(8);
    let (cancel1, alive1, started1) = insert_stalled_session(&mut sessions, &mut socket_set, "203.0.113.31:443");
    let (cancel2, alive2, started2) = insert_stalled_session(&mut sessions, &mut socket_set, "203.0.113.32:443");
    started1.await.expect("first stalled task should start");
    started2.await.expect("second stalled task should start");

    let started_at = Instant::now();
    let mut dns_cache = None;
    shutdown_active_sessions(&mut sessions, &mut socket_set, &mut dns_cache).await;

    assert_eq!(started_at.elapsed(), Duration::from_secs(5), "all tasks must share one shutdown grace period");
    assert!(cancel1.is_cancelled() && cancel2.is_cancelled(), "all session tokens must be cancelled together");
    assert!(!alive1.load(Ordering::Acquire), "first stalled task must be aborted and joined");
    assert!(!alive2.load(Ordering::Acquire), "second stalled task must be aborted and joined");
    assert!(sessions.is_empty(), "all sessions should be removed after shutdown");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
/// # Cancel safety:
///
/// NOT cancel-safe: the test must cancel and join the tunnel and its owned socket task before it returns.
async fn terminal_tun_write_error_retires_owned_session_socket() {
    use std::os::unix::net::UnixDatagram;

    let (tun, closed_peer) = UnixDatagram::pair().expect("packet fd pair");
    drop(closed_peer);
    assert_packet_failure_retires_session(tun.into(), true).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
/// # Cancel safety:
///
/// NOT cancel-safe: the helper must cancel and join all owned tasks before the test returns.
async fn packet_input_eof_retires_owned_session_socket() {
    let (tun, peer) = std::os::unix::net::UnixStream::pair().expect("packet fd pair");
    drop(peer);
    let error = assert_packet_failure_retires_session(tun.into(), false).await;
    assert_eq!(error.kind(), io::ErrorKind::UnexpectedEof);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
/// # Cancel safety:
///
/// NOT cancel-safe: the helper must cancel and join all owned tasks before the test returns.
async fn terminal_packet_read_error_retires_owned_session_socket() {
    use tokio::net::{TcpListener, TcpStream};

    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind packet input peer");
    let tun =
        TcpStream::connect(listener.local_addr().expect("packet input address")).await.expect("connect packet fd");
    let (peer, _) = listener.accept().await.expect("accept packet input peer");
    socket2::SockRef::from(&peer).set_linger(Some(Duration::ZERO)).expect("set abortive packet close");
    drop(peer);
    let error = assert_packet_failure_retires_session(tun.into_std().expect("take packet fd").into(), false).await;
    assert_eq!(error.kind(), io::ErrorKind::ConnectionReset);
}

/// # Cancel safety:
///
/// NOT cancel-safe: this helper must cancel and join the tunnel and its owned socket task before it returns.
async fn assert_packet_failure_retires_session(tun: std::os::fd::OwnedFd, queue_output: bool) -> io::Error {
    use ripdpi_tunnel_config::{Config, MiscConfig, Socks5Config, TunnelConfig};
    use tokio::io::AsyncReadExt;
    use tokio::net::{TcpListener, TcpStream};

    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind observed session peer");
    let mut peer = TcpStream::connect(listener.local_addr().expect("session address")).await.expect("connect peer");
    let (session_socket, _) = listener.accept().await.expect("accept session socket");
    let cancel = CancellationToken::new();
    let child_cancel = cancel.child_token();
    let task_cancel = child_cancel.clone();
    let (smoltcp_side, session_side) = tokio::io::duplex(crate::io_loop::DUPLEX_BUF);
    let (started_tx, started_rx) = tokio::sync::oneshot::channel();
    let session_task = tokio::spawn(async move {
        let _socket = session_socket;
        let _local = session_side;
        started_tx.send(()).expect("report owned task start");
        task_cancel.cancelled().await;
        Ok(())
    });
    started_rx.await.expect("owned task starts before packet failure");

    let mut device = TunDevice::new(1500);
    let (iface, socket_set, socket_handle, _) = establish_tcp_connection(&mut device);
    let mut sessions = ActiveSessions::new(8);
    sessions.insert(
        socket_handle,
        SessionEntry {
            smoltcp_side,
            cancel: child_cancel.clone(),
            handle: session_task,
            pending_to_session: Vec::new(),
            pending_to_smoltcp: Vec::new(),
            upstream_closed: false,
            upstream_failed: Arc::default(),
            pinned_synthetic_ip: None,
            attribution_id: None,
        },
    );
    if queue_output {
        let mut packet = Vec::new();
        etherparse::PacketBuilder::ipv4([127, 0, 0, 1], [10, 0, 0, 99], 64)
            .udp(53, 40000)
            .write(&mut packet, b"terminal write")
            .expect("build output packet");
        assert!(device.tx_queue.push_back(packet));
    }
    let tun = crate::device::PacketFd::new(tun).expect("register packet fd");
    let config = Arc::new(Config {
        tunnel: TunnelConfig::default(),
        socks5: Socks5Config {
            address: "127.0.0.1".into(),
            port: 1080,
            udp: None,
            udp_address: None,
            pipeline: None,
            username: None,
            password: None,
            mark: None,
        },
        mapdns: None,
        split_dns_policy: None,
        misc: MiscConfig::default(),
    });
    let loop_cancel = cancel.clone();
    let mut worker = tokio::spawn(async move {
        crate::io_loop::io_loop_task(
            &tun,
            device,
            iface,
            socket_set,
            sessions,
            config,
            loop_cancel,
            Arc::new(Stats::default()),
            None,
        )
        .await
    });
    let finished = tokio::time::timeout(Duration::from_secs(1), &mut worker).await;
    let session_cancelled = child_cancel.is_cancelled();
    let mut byte = [0];
    let socket_read = tokio::time::timeout(Duration::from_millis(200), peer.read(&mut byte)).await;
    cancel.cancel();
    if finished.is_err() {
        worker.await.expect("join cancelled worker").expect("cancelled worker exits cleanly");
    }
    child_cancel.cancel();
    assert!(finished.is_ok(), "terminal packet failure must end the loop without external cancellation");
    let error = finished.expect("worker finished").expect("worker did not panic").expect_err("terminal packet error");
    assert!(session_cancelled, "the loop must cancel owned sessions before it returns the packet error");
    assert!(matches!(socket_read, Ok(Ok(0))), "the owned loopback socket must close before the loop returns");
    error
}
