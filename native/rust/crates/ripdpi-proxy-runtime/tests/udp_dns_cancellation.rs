#![cfg(not(feature = "loom"))]

use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::sync::{Arc, mpsc};
use std::thread;
use std::time::{Duration, Instant};

use ripdpi_proxy_runtime::{create_listener, prepare_embedded, run_proxy_with_embedded_control_receipt};
use ripdpi_proxy_runtime_adapter::model::config::{RuntimeConfig, UpstreamSocksConfig};
use ripdpi_proxy_runtime_adapter::model::proxy_config::{ProxyEncryptedDnsContext, ProxyRuntimeContext};
use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;

#[derive(Clone, Copy)]
enum Phase {
    Datagram,
    BoundRelay,
}

#[derive(Clone, Copy)]
enum Stop {
    Runtime,
    LocalFin,
    Deadline,
}

#[test]
fn runtime_stop_cancels_stalled_udp_target_dns() {
    check(Phase::Datagram, Stop::Runtime);
}

#[test]
fn local_fin_cancels_stalled_udp_target_dns() {
    check(Phase::Datagram, Stop::LocalFin);
}

#[test]
fn runtime_stop_cancels_stalled_udp_bound_relay_dns() {
    check(Phase::BoundRelay, Stop::Runtime);
}

#[test]
fn local_fin_cancels_stalled_udp_bound_relay_dns() {
    check(Phase::BoundRelay, Stop::LocalFin);
}

#[test]
fn setup_deadline_cancels_stalled_udp_bound_relay_dns() {
    check(Phase::BoundRelay, Stop::Deadline);
}

fn check(phase: Phase, stop: Stop) {
    prepare_embedded();
    let dns = StalledDns::start();
    let upstream = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("SOCKS upstream");
    let sink = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("UDP sink");
    sink.set_read_timeout(Some(Duration::from_millis(100))).expect("sink timeout");
    let mut config = RuntimeConfig::default();
    config.network.listen.listen_port = 0;
    config.timeouts.connect_timeout_ms = if matches!(stop, Stop::Deadline) { 250 } else { 0 };
    if matches!(phase, Phase::BoundRelay) {
        config.groups[0].policy.ext_socks =
            Some(UpstreamSocksConfig { addr: upstream.local_addr().expect("upstream address"), auth: None });
    }
    let listener = create_listener(&config).expect("proxy listener");
    let address = listener.local_addr().expect("proxy address");
    let control = Arc::new(EmbeddedProxyControl::new_with_context(None, Some(dns.context())));
    let worker_control = control.clone();
    let (done_tx, done_rx) = mpsc::channel();
    let runtime = thread::spawn(move || {
        done_tx
            .send(run_proxy_with_embedded_control_receipt(
                config,
                listener,
                worker_control,
                Arc::new(ripdpi_ws_tunnel::TelegramWsTransport),
            ))
            .expect("runtime result");
    });
    let (client, relay) = associate(address);
    let datagram = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("client UDP");
    let frame = match phase {
        Phase::Datagram => {
            let host = b"fixture.test";
            let mut frame = vec![0, 0, 0, 3, host.len() as u8];
            frame.extend_from_slice(host);
            frame.extend_from_slice(&sink.local_addr().expect("sink address").port().to_be_bytes());
            frame.extend_from_slice(b"queued-before-cancel");
            frame
        }
        Phase::BoundRelay => b"\0\0\0\x01\xc0\0\x02\x0a\0\x2aqueued-before-cancel".to_vec(),
    };
    datagram.send_to(&frame, relay).expect("send UDP frame");
    let mut upstream_control = if matches!(phase, Phase::BoundRelay) {
        let mut stream = accept_with_deadline(&upstream);
        stream.set_read_timeout(Some(Duration::from_secs(1))).expect("upstream timeout");
        let mut greeting = [0; 3];
        stream.read_exact(&mut greeting).expect("greeting");
        assert_eq!(greeting, [5, 1, 0]);
        stream.write_all(&[5, 0]).expect("no auth");
        let mut request = [0; 10];
        stream.read_exact(&mut request).expect("ASSOCIATE");
        assert_eq!(request, [5, 3, 0, 1, 0, 0, 0, 0, 0, 0]);
        let host = b"relay.fixture.test";
        let mut reply = vec![5, 0, 0, 3, host.len() as u8];
        reply.extend_from_slice(host);
        reply.extend_from_slice(&sink.local_addr().expect("relay address").port().to_be_bytes());
        stream.write_all(&reply).expect("domain relay reply");
        Some(stream)
    } else {
        None
    };
    dns.started.recv_timeout(Duration::from_secs(2)).expect("UDP path reached local DNS");
    let stopped_at = Instant::now();
    let dns_cancelled_before_runtime_stop = match stop {
        Stop::Runtime => {
            control.request_shutdown();
            None
        }
        Stop::LocalFin => {
            drop(client);
            Some(dns.closed.recv_timeout(Duration::from_secs(2)))
        }
        Stop::Deadline => Some(dns.closed.recv_timeout(Duration::from_secs(2))),
    };
    let early_stop_elapsed = stopped_at.elapsed();
    if !matches!(stop, Stop::Runtime) {
        control.request_shutdown();
    }
    let early_result = done_rx.recv_timeout(Duration::from_secs(2));
    let bounded_runtime = early_result.is_ok();
    let dns_cancelled_before_runtime_stop = dns_cancelled_before_runtime_stop
        .or_else(|| bounded_runtime.then(|| dns.closed.recv_timeout(Duration::from_secs(1))));
    // Failure cleanup releases the fixture so a regression cannot leave test workers alive.
    let _ = dns.release.send(());
    let receipt = early_result
        .unwrap_or_else(|_| done_rx.recv_timeout(Duration::from_secs(5)).expect("cleanup result"))
        .expect("runtime success");
    runtime.join().expect("runtime thread");
    let dns_closed =
        dns_cancelled_before_runtime_stop.unwrap_or_else(|| dns.closed.recv_timeout(Duration::from_secs(1)));
    assert!(bounded_runtime, "DNS must not retain the runtime worker past stop");
    assert!(!receipt.forced_abort(), "DNS cancellation must finish within the graceful window");
    assert!(!receipt.worker_panicked());
    assert!(
        dns_closed.expect("DNS connection must close on caller cancellation"),
        "fixture release closed DNS instead of cancellation"
    );
    if !matches!(stop, Stop::Runtime) {
        assert!(early_stop_elapsed < Duration::from_secs(2));
    }
    if let Some(stream) = upstream_control.as_mut() {
        assert_eq!(stream.read(&mut [0; 1]).expect("upstream setup closed"), 0);
    }
    assert!(
        matches!(sink.recv_from(&mut [0; 128]), Err(ref error) if matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut))
    );
}

fn associate(address: SocketAddr) -> (TcpStream, SocketAddr) {
    let mut client = TcpStream::connect(address).expect("proxy client");
    client.set_read_timeout(Some(Duration::from_secs(2))).expect("client timeout");
    client.write_all(&[5, 1, 0]).expect("greeting");
    let mut method = [0; 2];
    client.read_exact(&mut method).expect("method");
    assert_eq!(method, [5, 0]);
    client.write_all(&[5, 3, 0, 1, 0, 0, 0, 0, 0, 0]).expect("ASSOCIATE");
    let mut reply = [0; 10];
    client.read_exact(&mut reply).expect("relay reply");
    assert_eq!(&reply[..4], &[5, 0, 0, 1]);
    let relay = SocketAddr::from(([reply[4], reply[5], reply[6], reply[7]], u16::from_be_bytes([reply[8], reply[9]])));
    (client, relay)
}

fn accept_with_deadline(listener: &TcpListener) -> TcpStream {
    listener.set_nonblocking(true).expect("nonblocking listener");
    let deadline = Instant::now() + Duration::from_secs(2);
    loop {
        match listener.accept() {
            Ok((stream, _)) => {
                stream.set_nonblocking(false).expect("blocking accepted stream");
                return stream;
            }
            Err(error) if error.kind() == io::ErrorKind::WouldBlock && Instant::now() < deadline => {
                thread::sleep(Duration::from_millis(10));
            }
            result => panic!("upstream connection: {result:?}"),
        }
    }
}

struct StalledDns {
    port: u16,
    started: mpsc::Receiver<()>,
    closed: mpsc::Receiver<bool>,
    release: mpsc::Sender<()>,
    worker: Option<thread::JoinHandle<()>>,
}

impl StalledDns {
    fn start() -> Self {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("local DNS");
        let port = listener.local_addr().expect("DNS address").port();
        listener.set_nonblocking(true).expect("nonblocking DNS listener");
        let (started_tx, started) = mpsc::channel();
        let (closed_tx, closed) = mpsc::channel();
        let (release, released) = mpsc::channel();
        let worker = thread::spawn(move || {
            let mut stream = loop {
                if released.try_recv().is_ok() {
                    return;
                }
                match listener.accept() {
                    Ok((stream, _)) => break stream,
                    Err(error) if error.kind() == io::ErrorKind::WouldBlock => thread::sleep(Duration::from_millis(10)),
                    error => panic!("DNS accept: {error:?}"),
                }
            };
            stream.set_nonblocking(false).expect("blocking DNS stream");
            stream.set_read_timeout(Some(Duration::from_millis(100))).expect("DNS timeout");
            let mut started_tx = Some(started_tx);
            let mut bytes = [0; 4096];
            loop {
                if released.try_recv().is_ok() {
                    let _ = closed_tx.send(false);
                    break;
                }
                match stream.read(&mut bytes) {
                    Ok(0) => {
                        let _ = closed_tx.send(true);
                        break;
                    }
                    Ok(_) => {
                        if let Some(started) = started_tx.take() {
                            let _ = started.send(());
                        }
                    }
                    Err(error) if matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
                    Err(error)
                        if matches!(
                            error.kind(),
                            io::ErrorKind::ConnectionReset | io::ErrorKind::ConnectionAborted
                        ) =>
                    {
                        let _ = closed_tx.send(true);
                        break;
                    }
                    Err(error) => panic!("DNS stream: {error}"),
                }
            }
        });
        Self { port, started, closed, release, worker: Some(worker) }
    }

    fn context(&self) -> ProxyRuntimeContext {
        ProxyRuntimeContext {
            encrypted_dns: Some(ProxyEncryptedDnsContext {
                resolver_id: Some("local-stalled-dns".to_string()),
                protocol: "doh".to_string(),
                host: "127.0.0.1".to_string(),
                port: self.port,
                tls_server_name: None,
                bootstrap_ips: vec!["127.0.0.1".to_string()],
                doh_url: Some(format!("http://127.0.0.1:{}/dns-query", self.port)),
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
            }),
            ..ProxyRuntimeContext::default()
        }
    }
}

impl Drop for StalledDns {
    fn drop(&mut self) {
        let _ = self.release.send(());
        self.worker.take().expect("fixture worker").join().expect("DNS fixture thread");
    }
}
