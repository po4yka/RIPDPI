use std::future::poll_fn;
use std::os::fd::RawFd;
use std::time::Duration;

use ripdpi_native_protect::{
    ProtectCallback, SocketProtectionPolicy, register_protect_callback, unregister_protect_callback,
};
use tokio::net::UdpSocket;
use tokio::time::timeout;

use super::*;

fn spec(policy: SocketProtectionPolicy) -> ClientSocketSpec {
    ClientSocketSpec { ipv6: false, bind_low_port: false, salamander_key: None, socket_protection: policy }
}

// cancel-safe: sends consume one whole datagram or report WouldBlock without partial state.
async fn send(socket: &Arc<HoppingSocket>, payload: &[u8]) {
    let mut poller = socket.clone().create_io_poller();
    let transmit = quinn::udp::Transmit {
        destination: socket.logical_peer,
        ecn: None,
        contents: payload,
        segment_size: None,
        src_ip: None,
    };
    timeout(
        Duration::from_secs(2),
        poll_fn(|cx| {
            loop {
                match socket.try_send(&transmit) {
                    Ok(()) => return Poll::Ready(Ok(())),
                    Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                        std::task::ready!(poller.as_mut().poll_writable(cx))?;
                    }
                    Err(error) => return Poll::Ready(Err(error)),
                }
            }
        }),
    )
    .await
    .unwrap()
    .unwrap();
}

// cancel-safe: poll_recv reports a consumed datagram in the same ready poll.
async fn receive(socket: &HoppingSocket) -> (Vec<u8>, SocketAddr) {
    let mut bytes = [0u8; 2048];
    let mut buffers = [io::IoSliceMut::new(&mut bytes)];
    let mut meta = [quinn::udp::RecvMeta::default()];
    timeout(Duration::from_secs(2), poll_fn(|cx| socket.poll_recv(cx, &mut buffers, &mut meta)))
        .await
        .unwrap()
        .unwrap();
    (bytes[..meta[0].len].to_vec(), meta[0].addr)
}

// cancel-safe: the test owns the timer; rotation does not contain an await.
async fn expire_timer(socket: &HoppingSocket) -> io::Result<()> {
    socket.timer.lock().unwrap().as_mut().unwrap().as_mut().reset(Instant::now());
    tokio::time::sleep(Duration::from_millis(2)).await;
    poll_fn(|cx| Poll::Ready(socket.poll_hop(cx))).await
}

// cancel-safe: all socket owners are local; no test task is detached.
#[tokio::test]
async fn rotation_keeps_previous_receive_path_filters_sources_and_rebind_preserves_policy() {
    let peers = [UdpSocket::bind("127.0.0.1:0").await.unwrap(), UdpSocket::bind("127.0.0.1:0").await.unwrap()];
    let ports = format!("{},{}", peers[0].local_addr().unwrap().port(), peers[1].local_addr().unwrap().port());
    let policy = PortHoppingConfig::from_wire(Some(&ports), Some(5_000), None).unwrap().unwrap();
    let spec = spec(SocketProtectionPolicy::Inactive);
    let socket = Arc::new(
        HoppingSocket::new(
            "127.0.0.1:443".parse().unwrap(),
            policy,
            spec.clone(),
            build_client_udp_socket(&spec).unwrap(),
        )
        .unwrap(),
    );
    let stop = StopHoppingOnDrop::new(Some(socket.clone()));
    let initial_port = socket.policy.ports()[socket.lock().unwrap().remote_index];
    let first = peers.iter().find(|peer| peer.local_addr().unwrap().port() == initial_port).unwrap();
    let second = peers.iter().find(|peer| peer.local_addr().unwrap().port() != initial_port).unwrap();
    let mut packet = [0u8; 64];
    send(&socket, b"first path").await;
    let (size, first_local) = timeout(Duration::from_secs(2), first.recv_from(&mut packet)).await.unwrap().unwrap();
    assert_eq!(&packet[..size], b"first path");
    expire_timer(&socket).await.unwrap();
    send(&socket, b"second path").await;
    let (size, second_local) = timeout(Duration::from_secs(2), second.recv_from(&mut packet)).await.unwrap().unwrap();
    assert_eq!(&packet[..size], b"second path");
    assert_ne!(first_local.port(), second_local.port());

    first.send_to(b"delayed prior reply", first_local).await.unwrap();
    assert_eq!(receive(&socket).await, (b"delayed prior reply".to_vec(), socket.logical_peer));
    let unrelated = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    unrelated.send_to(b"unrelated", second_local).await.unwrap();
    second.send_to(b"current reply", second_local).await.unwrap();
    assert_eq!(receive(&socket).await, (b"current reply".to_vec(), socket.logical_peer));

    let index = socket.lock().unwrap().remote_index;
    socket.rebind(build_client_udp_socket(&spec).unwrap()).unwrap();
    assert_eq!(socket.lock().unwrap().remote_index, index);
    expire_timer(&socket).await.unwrap();
    assert_ne!(socket.lock().unwrap().remote_index, index);
    drop(stop);
    assert!(socket.local_addr().is_err());
    assert!(socket.timer.lock().unwrap().is_none());
    std::net::UdpSocket::bind(first_local).expect("old path descriptor released");
    std::net::UdpSocket::bind(second_local).expect("second path descriptor released");
}

struct RejectReplacement(Mutex<Vec<RawFd>>);
impl ProtectCallback for RejectReplacement {
    fn protect(&self, fd: RawFd) -> io::Result<()> {
        let mut calls = self.0.lock().unwrap();
        calls.push(fd);
        if calls.len() == 1 { Ok(()) } else { Err(io::Error::new(io::ErrorKind::PermissionDenied, "test rejection")) }
    }
}

#[test]
fn replacement_protection_failure_stops_before_socket_can_send() {
    let _lock = crate::quic_transport::endpoint::protect_tests::PROTECT_TEST_LOCK.lock().unwrap();
    let callback = Arc::new(RejectReplacement(Mutex::new(Vec::new())));
    register_protect_callback(callback.clone());
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
    // cancel-safe: the local runtime and guard own every carrier; no spawned worker.
    let result = runtime.block_on(async {
        let spec = spec(SocketProtectionPolicy::VpnRequired);
        let policy = PortHoppingConfig::from_wire(Some("443,444"), Some(5_000), None).unwrap().unwrap();
        let socket = HoppingSocket::new(
            "127.0.0.1:443".parse().unwrap(),
            policy,
            spec.clone(),
            build_client_udp_socket(&spec).unwrap(),
        )
        .unwrap();
        let error = expire_timer(&socket).await.unwrap_err();
        let stopped = socket.local_addr().is_err();
        let timer_stopped = socket.timer.lock().unwrap().is_none();
        (error.kind(), stopped, timer_stopped)
    });
    unregister_protect_callback();
    assert_eq!(result, (io::ErrorKind::PermissionDenied, true, true));
    assert_eq!(callback.0.lock().unwrap().len(), 2);
}

// cancel-safe: local adapter guard owns shutdown and no task is spawned.
#[tokio::test]
async fn idle_writer_does_not_keep_retired_sockets_open() {
    let spec = spec(SocketProtectionPolicy::Inactive);
    let policy = PortHoppingConfig::from_wire(Some("443,444"), Some(5000), None).unwrap().unwrap();
    let socket = Arc::new(
        HoppingSocket::new(
            "127.0.0.1:443".parse().unwrap(),
            policy,
            spec.clone(),
            build_client_udp_socket(&spec).unwrap(),
        )
        .unwrap(),
    );
    let stop = StopHoppingOnDrop::new(Some(socket.clone()));
    let first = socket.local_addr().unwrap();
    let mut idle = socket.clone().create_io_poller();
    poll_fn(|cx| idle.as_mut().poll_writable(cx)).await.unwrap();
    expire_timer(&socket).await.unwrap();
    assert!(std::net::UdpSocket::bind(first).is_err(), "previous path stays open for delayed responses");
    expire_timer(&socket).await.unwrap();
    std::net::UdpSocket::bind(first).expect("idle poller must release twice-retired socket");
    let current = socket.local_addr().unwrap();
    poll_fn(|cx| idle.as_mut().poll_writable(cx)).await.unwrap();
    drop(stop);
    std::net::UdpSocket::bind(current).expect("stopped adapter releases socket despite retained poller");
    assert!(poll_fn(|cx| idle.as_mut().poll_writable(cx)).await.is_err());
}

// cancel-safe: adapter and both reference socket owners stay in this future.
#[tokio::test]
async fn adapter_preserves_gro_gso_and_fragmentation_capabilities_across_hops() {
    for salamander in [false, true] {
        let mut spec = spec(SocketProtectionPolicy::Inactive);
        spec.salamander_key = salamander.then(|| "test-obfuscation".to_string());
        let reference = wrap_socket(build_client_udp_socket(&spec).unwrap(), &spec).unwrap();
        let policy = PortHoppingConfig::from_wire(Some("443,444"), Some(5000), None).unwrap().unwrap();
        let socket = HoppingSocket::new(
            "127.0.0.1:443".parse().unwrap(),
            policy,
            spec.clone(),
            build_client_udp_socket(&spec).unwrap(),
        )
        .unwrap();
        for _ in 0..3 {
            assert_eq!(socket.max_receive_segments(), reference.io.max_receive_segments());
            assert_eq!(socket.max_transmit_segments(), reference.io.max_transmit_segments());
            assert_eq!(socket.may_fragment(), reference.io.may_fragment());
            if cfg!(target_os = "linux") && !salamander {
                assert_eq!(socket.max_receive_segments(), 64, "Linux quinn-udp GRO requires full receive buffers");
            }
            if salamander {
                assert_eq!(socket.max_receive_segments(), 1);
            }
            expire_timer(&socket).await.unwrap();
        }
        socket.stop();
    }
}

#[derive(Clone, Copy, Debug)]
enum ReceiveResult {
    Data(u8),
    Error,
    Pending,
}

struct ReceiveProbe {
    io: Arc<dyn AsyncUdpSocket>,
    result: ReceiveResult,
    during_poll: Mutex<Option<Box<dyn FnOnce() + Send>>>,
}

impl fmt::Debug for ReceiveProbe {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.debug_struct("ReceiveProbe").field("result", &self.result).finish_non_exhaustive()
    }
}

impl AsyncUdpSocket for ReceiveProbe {
    fn create_io_poller(self: Arc<Self>) -> Pin<Box<dyn UdpPoller>> {
        self.io.clone().create_io_poller()
    }

    fn try_send(&self, transmit: &quinn::udp::Transmit<'_>) -> io::Result<()> {
        self.io.try_send(transmit)
    }

    fn poll_recv(
        &self,
        _cx: &mut Context<'_>,
        buffers: &mut [io::IoSliceMut<'_>],
        metadata: &mut [quinn::udp::RecvMeta],
    ) -> Poll<io::Result<usize>> {
        let during_poll = self.during_poll.lock().unwrap().take();
        if let Some(during_poll) = during_poll {
            during_poll();
        }
        match self.result {
            ReceiveResult::Data(byte) => {
                buffers[0][0] = byte;
                metadata[0] = quinn::udp::RecvMeta {
                    addr: "127.0.0.1:443".parse().unwrap(),
                    len: 1,
                    stride: 1,
                    ..Default::default()
                };
                Poll::Ready(Ok(1))
            }
            ReceiveResult::Error => Poll::Ready(Err(io::Error::new(io::ErrorKind::ConnectionReset, "receive probe"))),
            ReceiveResult::Pending => Poll::Pending,
        }
    }

    fn local_addr(&self) -> io::Result<SocketAddr> {
        self.io.local_addr()
    }

    fn may_fragment(&self) -> bool {
        self.io.may_fragment()
    }
}

fn probe_path(result: ReceiveResult, during_poll: Option<Box<dyn FnOnce() + Send>>) -> Arc<PathSocket> {
    let spec = spec(SocketProtectionPolicy::Inactive);
    let path = wrap_socket(build_client_udp_socket(&spec).unwrap(), &spec).unwrap();
    Arc::new(PathSocket {
        io: Arc::new(ReceiveProbe { io: path.io.clone(), result, during_poll: Mutex::new(during_poll) }),
        raw: path.raw.try_clone().unwrap(),
    })
}

fn receive_adapter() -> Arc<HoppingSocket> {
    let spec = spec(SocketProtectionPolicy::Inactive);
    let policy = PortHoppingConfig::from_wire(Some("443,444"), Some(5000), None).unwrap().unwrap();
    Arc::new(
        HoppingSocket::new(
            "127.0.0.1:443".parse().unwrap(),
            policy,
            spec.clone(),
            build_client_udp_socket(&spec).unwrap(),
        )
        .unwrap(),
    )
}

fn poll_byte(socket: &HoppingSocket) -> Poll<io::Result<u8>> {
    let mut bytes = [0u8; 2048];
    let mut buffers = [io::IoSliceMut::new(&mut bytes)];
    let mut metadata = [quinn::udp::RecvMeta::default()];
    let mut context = Context::from_waker(Waker::noop());
    socket.poll_recv(&mut context, &mut buffers, &mut metadata).map(|result| result.map(|_| bytes[0]))
}

// cancel-safe: the probes complete each poll without a detached operation.
#[tokio::test]
async fn continuously_readable_previous_and_current_paths_both_receive() {
    let socket = receive_adapter();
    socket.replace_socket(probe_path(ReceiveResult::Data(1), None), false).unwrap();
    socket.replace_socket(probe_path(ReceiveResult::Data(2), None), false).unwrap();
    for expected in [2, 1, 2, 1, 2, 1] {
        assert!(matches!(poll_byte(&socket), Poll::Ready(Ok(byte)) if byte == expected));
    }
}

// cancel-safe: all receive results and rebinds are synchronous probes.
#[tokio::test]
async fn previous_receive_error_is_retired_but_current_error_is_fatal() {
    let socket = receive_adapter();
    socket.replace_socket(probe_path(ReceiveResult::Error, None), false).unwrap();
    socket.replace_socket(probe_path(ReceiveResult::Data(2), None), false).unwrap();
    socket.lock().unwrap().receive_previous_first = true;
    assert!(matches!(poll_byte(&socket), Poll::Ready(Ok(2))));
    assert!(socket.lock().unwrap().previous.is_none());
    socket.replace_socket(probe_path(ReceiveResult::Error, None), false).unwrap();
    assert!(matches!(poll_byte(&socket), Poll::Ready(Err(error)) if error.kind() == io::ErrorKind::ConnectionReset));
}

// cancel-safe: the hook changes the path within the poll and owns no async work.
#[tokio::test]
async fn current_snapshot_error_after_rebind_does_not_fail_the_new_current() {
    let socket = receive_adapter();
    let replacement = probe_path(ReceiveResult::Data(3), None);
    let weak = Arc::downgrade(&socket);
    let next = replacement.clone();
    let failing = probe_path(
        ReceiveResult::Error,
        Some(Box::new(move || weak.upgrade().unwrap().replace_socket(next, false).unwrap())),
    );
    socket.replace_socket(failing, false).unwrap();
    assert!(poll_byte(&socket).is_pending());
    assert!(socket.lock().unwrap().previous.is_none());
    assert!(Arc::ptr_eq(socket.lock().unwrap().current.as_ref().unwrap(), &replacement));
    assert!(matches!(poll_byte(&socket), Poll::Ready(Ok(3))));
}

// cancel-safe: the hook changes the path within the poll and owns no async work.
#[tokio::test]
async fn retired_snapshot_error_does_not_remove_the_new_previous() {
    let socket = receive_adapter();
    let replacement = probe_path(ReceiveResult::Data(3), None);
    let weak = Arc::downgrade(&socket);
    let next = replacement.clone();
    let failing = probe_path(
        ReceiveResult::Error,
        Some(Box::new(move || weak.upgrade().unwrap().replace_socket(next, false).unwrap())),
    );
    socket.replace_socket(failing, false).unwrap();
    let retained = probe_path(ReceiveResult::Pending, None);
    socket.replace_socket(retained.clone(), false).unwrap();
    socket.lock().unwrap().receive_previous_first = true;
    assert!(poll_byte(&socket).is_pending());
    assert!(Arc::ptr_eq(socket.lock().unwrap().previous.as_ref().unwrap(), &retained));
    assert!(Arc::ptr_eq(socket.lock().unwrap().current.as_ref().unwrap(), &replacement));
    assert!(matches!(poll_byte(&socket), Poll::Ready(Ok(3))));
}

// cancel-safe: stopping the local adapter drops all paths before the poll returns.
#[tokio::test]
async fn stop_during_receive_never_returns_data_or_pending() {
    for result in [ReceiveResult::Data(1), ReceiveResult::Error, ReceiveResult::Pending] {
        let socket = receive_adapter();
        let weak = Arc::downgrade(&socket);
        let path = probe_path(result, Some(Box::new(move || weak.upgrade().unwrap().stop())));
        socket.replace_socket(path, false).unwrap();
        assert!(matches!(poll_byte(&socket), Poll::Ready(Err(error)) if error.kind() == io::ErrorKind::NotConnected));
    }
}
