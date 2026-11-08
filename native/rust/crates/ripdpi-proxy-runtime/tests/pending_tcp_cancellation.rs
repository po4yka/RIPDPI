#![cfg(all(target_os = "linux", not(feature = "loom")))]

use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::sync::{Arc, Barrier, mpsc};
use std::thread;
use std::time::{Duration, Instant};

use ripdpi_proxy_runtime::{create_listener, prepare_embedded, run_proxy_with_embedded_control_receipt};
use ripdpi_proxy_runtime_adapter::failure::ClassifiedFailure;
use ripdpi_proxy_runtime_adapter::model::config::RuntimeConfig;
use ripdpi_proxy_runtime_adapter::model::runtime_api::{EmbeddedProxyControl, RuntimeTelemetrySink};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

#[test]
fn shutdown_cancels_a_pending_tcp_connect_with_or_without_a_deadline() {
    prepare_embedded();
    for connect_timeout_ms in [10_000, 0] {
        let target_socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP)).expect("target socket");
        target_socket.bind(&SockAddr::from(SocketAddr::from((Ipv4Addr::LOCALHOST, 0)))).expect("bind target");
        target_socket.listen(0).expect("single-entry Linux accept queue");
        let target_listener: TcpListener = target_socket.into();
        let target = target_listener.local_addr().expect("target address");
        let queued = TcpStream::connect(target).expect("fill accept queue");
        let probe = TcpStream::connect_timeout(&target, Duration::from_millis(100));
        assert!(probe.is_err(), "the target accept queue must be full before starting the runtime");

        let mut config = RuntimeConfig::default();
        config.network.listen.listen_port = 0;
        config.timeouts.connect_timeout_ms = connect_timeout_ms;
        let listener = create_listener(&config).expect("proxy listener");
        let proxy_addr = listener.local_addr().expect("proxy address");
        let (events_tx, events_rx) = mpsc::channel();
        let control = Arc::new(EmbeddedProxyControl::new(Some(Arc::new(StopTelemetry(events_tx, None)))));
        let worker_control = control.clone();
        let (done_tx, done_rx) = mpsc::channel();
        let runtime = thread::spawn(move || {
            let result = run_proxy_with_embedded_control_receipt(
                config,
                listener,
                worker_control,
                Arc::new(ripdpi_ws_tunnel::TelegramWsTransport),
            );
            let _ = done_tx.send(result);
        });
        let mut client = TcpStream::connect(proxy_addr).expect("proxy client");
        client.set_read_timeout(Some(Duration::from_secs(2))).expect("client read timeout");
        client.write_all(&[5, 1, 0]).expect("SOCKS greeting");
        let mut greeting = [0; 2];
        client.read_exact(&mut greeting).expect("SOCKS method selection");
        assert_eq!(greeting, [5, 0]);
        let port = target.port().to_be_bytes();
        client.write_all(&[5, 1, 0, 1, 127, 0, 0, 1, port[0], port[1]]).expect("SOCKS CONNECT");

        let pending_inode = wait_for_pending_socket(target);
        let stopped = Instant::now();
        control.request_shutdown();
        let early_result = done_rx.recv_timeout(Duration::from_secs(6));
        let elapsed = stopped.elapsed();
        let pending_fd_closed = pending_inode.as_ref().is_some_and(|inode| !process_owns_socket(inode));
        // Release the fixture and await fd close even when the old runtime detaches its worker.
        drop(target_listener);
        drop(queued);
        let cleanup_deadline = Instant::now() + Duration::from_secs(15);
        while pending_inode.as_ref().is_some_and(|inode| process_owns_socket(inode))
            && Instant::now() < cleanup_deadline
        {
            thread::sleep(Duration::from_millis(10));
        }
        let receipt = early_result
            .unwrap_or_else(|_| done_rx.recv_timeout(Duration::from_secs(15)).expect("runtime cleanup"))
            .expect("runtime result");
        runtime.join().expect("runtime worker");
        eprintln!(
            "timeout_ms={connect_timeout_ms} elapsed={elapsed:?} fd_closed={pending_fd_closed} forced_abort={}",
            receipt.forced_abort()
        );
        assert!(pending_inode.is_some(), "the proxy must reach a real pending SYN before shutdown");
        assert!(pending_fd_closed, "pending TCP fd must close before runtime returns, timeout={connect_timeout_ms}");
        assert!(!receipt.forced_abort(), "pending connect must stop within the graceful window");
        assert!(!receipt.worker_panicked());
        let events: Vec<_> = events_rx.try_iter().collect();
        assert!(events.is_empty(), "shutdown published failure telemetry: {events:?}");
        assert!(elapsed < Duration::from_secs(3), "shutdown waited {elapsed:?}");
    }
}

#[test]
fn a_refused_connect_still_reports_failure_when_shutdown_races() {
    prepare_embedded();
    let reserved = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("reserve refused target");
    let target = reserved.local_addr().expect("target address");
    let mut config = RuntimeConfig::default();
    config.network.listen.listen_port = 0;
    let listener = create_listener(&config).expect("proxy listener");
    let proxy_addr = listener.local_addr().expect("proxy address");
    drop(reserved);
    let (events_tx, events_rx) = mpsc::channel();
    let failure_barrier = Arc::new(Barrier::new(2));
    let control =
        Arc::new(EmbeddedProxyControl::new(Some(Arc::new(StopTelemetry(events_tx, Some(failure_barrier.clone()))))));
    let worker_control = control.clone();
    let runtime = thread::spawn(move || {
        run_proxy_with_embedded_control_receipt(
            config,
            listener,
            worker_control,
            Arc::new(ripdpi_ws_tunnel::TelegramWsTransport),
        )
    });
    let mut client = TcpStream::connect(proxy_addr).expect("proxy client");
    client.set_read_timeout(Some(Duration::from_secs(2))).expect("read timeout");
    client.write_all(&[5, 1, 0]).expect("SOCKS greeting");
    let mut greeting = [0; 2];
    client.read_exact(&mut greeting).expect("method selection");
    assert_eq!(greeting, [5, 0]);
    let port = target.port().to_be_bytes();
    client.write_all(&[5, 1, 0, 1, 127, 0, 0, 1, port[0], port[1]]).expect("CONNECT request");
    assert_eq!(events_rx.recv_timeout(Duration::from_secs(2)).expect("connect failure"), "connect_failure");
    control.request_shutdown();
    failure_barrier.wait();
    let mut reply = [0; 10];
    let read = client.read_exact(&mut reply);
    let receipt = runtime.join().expect("runtime worker").expect("runtime result");
    read.expect("refused CONNECT reply");
    assert_eq!(reply[..3], [5, 5, 0]);
    assert!(!receipt.forced_abort());
    assert!(
        events_rx.try_iter().any(|event| event == "classified_failure"),
        "a real connect failure must remain classified when shutdown races"
    );
}

fn wait_for_pending_socket(target: SocketAddr) -> Option<String> {
    let remote = format!("0100007F:{:04X}", target.port());
    let deadline = Instant::now() + Duration::from_secs(2);
    loop {
        let tcp = std::fs::read_to_string("/proc/net/tcp").expect("Linux TCP state");
        if let Some(inode) = tcp.lines().skip(1).find_map(|line| {
            let fields: Vec<_> = line.split_whitespace().collect();
            (fields.get(2) == Some(&remote.as_str()) && fields.get(3) == Some(&"02"))
                .then(|| fields.get(9).map(ToString::to_string))
                .flatten()
                .filter(|inode| process_owns_socket(inode))
        }) {
            return Some(inode);
        }
        if Instant::now() >= deadline {
            return None;
        }
        thread::sleep(Duration::from_millis(10));
    }
}

fn process_owns_socket(inode: &str) -> bool {
    let link = format!("socket:[{inode}]");
    std::fs::read_dir("/proc/self/fd").expect("process descriptors").any(|entry| {
        entry
            .ok()
            .and_then(|entry| std::fs::read_link(entry.path()).ok())
            .is_some_and(|target| target.as_os_str() == std::ffi::OsStr::new(&link))
    })
}

struct StopTelemetry(mpsc::Sender<&'static str>, Option<Arc<Barrier>>);

impl RuntimeTelemetrySink for StopTelemetry {
    fn on_listener_started(&self, _: SocketAddr, _: usize, _: usize) {}
    fn on_listener_stopped(&self) {}
    fn on_client_accepted(&self) {}
    fn on_client_finished(&self) {}
    fn on_client_error(&self, _: &std::io::Error) {
        let _ = self.0.send("client_error");
    }
    fn on_host_autolearn_state(&self, _: bool, _: usize, _: usize, _: usize, _: Option<&str>, _: Option<&str>) {}
    fn on_host_autolearn_event(&self, _: &'static str, _: Option<&str>, _: Option<usize>) {}
    fn on_route_selected(&self, _: SocketAddr, _: usize, _: Option<&str>, _: &'static str) {}
    fn on_failure_classified(&self, _: SocketAddr, _: &ClassifiedFailure, _: Option<&str>) {
        let _ = self.0.send("classified_failure");
    }
    fn on_upstream_connect_failed(&self, _: SocketAddr, _: u64, _: std::io::ErrorKind) {
        let _ = self.0.send("connect_failure");
        if let Some(barrier) = &self.1 {
            barrier.wait();
        }
    }
    fn on_route_advanced(&self, _: SocketAddr, _: usize, _: usize, _: u32, _: Option<&str>) {
        let _ = self.0.send("route_advance");
    }
}
