#![cfg(not(feature = "loom"))]

use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::sync::{Arc, mpsc};
use std::thread;
use std::time::{Duration, Instant};

use ripdpi_proxy_runtime::{create_listener, prepare_embedded, run_proxy_with_embedded_control_receipt};
use ripdpi_proxy_runtime_adapter::model::config::{RuntimeConfig, UpstreamSocksConfig};
use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;

#[derive(Clone, Copy, Debug)]
enum Phase {
    Auth,
    Reply,
    DripReply,
}

#[test]
fn stop_closes_pending_udp_setup_before_returning_even_without_a_timeout() {
    for timeout_ms in [10_000, 0] {
        for phase in [Phase::Auth, Phase::Reply, Phase::DripReply] {
            check_forced_drain(phase, timeout_ms, false);
        }
    }
}

#[test]
fn local_control_fin_cancels_pending_udp_setup() {
    for timeout_ms in [10_000, 0] {
        check_forced_drain(Phase::Auth, timeout_ms, true);
    }
}

fn check_forced_drain(phase: Phase, timeout_ms: u32, local_close: bool) {
    prepare_embedded();
    let upstream_listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream");
    let upstream_addr = upstream_listener.local_addr().expect("upstream address");
    let mut config = RuntimeConfig::default();
    config.network.listen.listen_port = 0;
    config.timeouts.connect_timeout_ms = timeout_ms;
    config.groups[0].policy.ext_socks = Some(UpstreamSocksConfig { addr: upstream_addr, auth: None });
    let listener = create_listener(&config).expect("proxy listener");
    let proxy_addr = listener.local_addr().expect("proxy address");
    let control = Arc::new(EmbeddedProxyControl::new(None));
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
            .expect("report runtime completion");
    });
    let mut client = TcpStream::connect(proxy_addr).expect("connect client");
    client.set_read_timeout(Some(Duration::from_secs(2))).expect("client read timeout");
    client.write_all(&[5, 1, 0]).expect("send greeting");
    let mut selected = [0; 2];
    client.read_exact(&mut selected).expect("read selected method");
    assert_eq!(selected, [5, 0]);
    client.write_all(&[5, 3, 0, 1, 0, 0, 0, 0, 0, 0]).expect("send ASSOCIATE");
    let mut reply = [0; 10];
    client.read_exact(&mut reply).expect("read ASSOCIATE reply");
    assert_eq!(&reply[..4], &[5, 0, 0, 1]);
    let relay = SocketAddr::from(([reply[4], reply[5], reply[6], reply[7]], u16::from_be_bytes([reply[8], reply[9]])));
    let udp = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind UDP client");
    udp.send_to(b"\0\0\0\x01\xc0\0\x02\x0a\x11\x5cqueued-before-stop", relay).expect("send pending datagram");
    let (mut upstream, _) = upstream_listener.accept().expect("accept upstream");
    upstream.set_read_timeout(Some(Duration::from_secs(2))).expect("upstream read timeout");
    let mut greeting = [0; 3];
    upstream.read_exact(&mut greeting).expect("read upstream greeting");
    assert_eq!(greeting, [5, 1, 0]);
    if !matches!(phase, Phase::Auth) {
        upstream.write_all(&[5, 0]).expect("select no auth");
        let mut connect = [0; 10];
        upstream.read_exact(&mut connect).expect("read upstream ASSOCIATE");
        assert_eq!(connect, [5, 3, 0, 1, 0, 0, 0, 0, 0, 0]);
        if matches!(phase, Phase::DripReply) {
            upstream.write_all(&[5]).expect("start fragmented reply");
        }
    }
    let drip = if matches!(phase, Phase::DripReply) {
        let mut writer = upstream.try_clone().expect("clone drip writer");
        let (stop_tx, stop_rx) = mpsc::channel::<()>();
        let handle = thread::spawn(move || {
            for byte in [0, 0, 1, 127, 0, 0, 1, 0, 80] {
                if !matches!(stop_rx.recv_timeout(Duration::from_secs(1)), Err(mpsc::RecvTimeoutError::Timeout)) {
                    break;
                }
                if writer.write_all(&[byte]).is_err() {
                    break;
                }
            }
        });
        Some((stop_tx, handle))
    } else {
        None
    };
    let local_terminal = if local_close {
        drop(client);
        let mut tail = [0; 10];
        Some(upstream.read(&mut tail))
    } else {
        None
    };
    let stopped_at = Instant::now();
    control.request_shutdown();
    let receipt =
        done_rx.recv_timeout(Duration::from_secs(5)).expect("bounded runtime return").expect("runtime result");
    runtime.join().expect("join runtime thread");

    assert!(!receipt.worker_panicked(), "{phase:?} must not panic");
    assert!(stopped_at.elapsed() < Duration::from_secs(5));
    upstream.set_read_timeout(Some(Duration::from_millis(250))).expect("bound terminal EOF check");
    let mut tail = [0; 10];
    let terminal_read = upstream.read(&mut tail);
    if let Some((stop, handle)) = drip {
        let _ = stop.send(());
        handle.join().expect("join drip writer");
    }
    if let Some(local_terminal) = local_terminal {
        assert_eq!(local_terminal.expect("local FIN must cancel upstream setup"), 0);
    }
    assert_eq!(terminal_read.expect("negotiation socket must close before terminal return"), 0, "{phase:?}");
    let sink = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("late UDP relay");
    sink.set_read_timeout(Some(Duration::from_millis(100))).expect("late relay timeout");
    if matches!(phase, Phase::Auth) {
        let _ = upstream.write_all(&[5, 0]);
    }
    let mut late_reply = vec![5, 0, 0, 1, 127, 0, 0, 1];
    late_reply.extend_from_slice(&sink.local_addr().expect("late relay address").port().to_be_bytes());
    let _ = upstream.write_all(&late_reply);
    let late_datagram = sink.recv_from(&mut [0; 128]);
    assert!(
        matches!(late_datagram, Err(ref error) if matches!(error.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut)),
        "stopped setup must not deliver its queued datagram: {late_datagram:?}"
    );
}
