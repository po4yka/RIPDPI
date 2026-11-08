#![cfg(not(feature = "loom"))]

use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener, TcpStream};
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
fn forced_drain_closes_upstream_negotiation_before_returning() {
    for phase in [Phase::Auth, Phase::Reply, Phase::DripReply] {
        check_forced_drain(phase);
    }
}

fn check_forced_drain(phase: Phase) {
    prepare_embedded();
    let upstream_listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind upstream");
    let upstream_addr = upstream_listener.local_addr().expect("upstream address");
    let mut config = RuntimeConfig::default();
    config.network.listen.listen_port = 0;
    config.timeouts.connect_timeout_ms = 10_000;
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
    client.write_all(&[5, 1, 0, 1, 127, 0, 0, 1, 0, 80]).expect("send CONNECT");
    let (mut upstream, _) = upstream_listener.accept().expect("accept upstream");
    upstream.set_read_timeout(Some(Duration::from_secs(2))).expect("upstream read timeout");
    let mut greeting = [0; 3];
    upstream.read_exact(&mut greeting).expect("read upstream greeting");
    assert_eq!(greeting, [5, 1, 0]);
    if !matches!(phase, Phase::Auth) {
        upstream.write_all(&[5, 0]).expect("select no auth");
        let mut connect = [0; 10];
        upstream.read_exact(&mut connect).expect("read upstream CONNECT");
        assert_eq!(connect, [5, 1, 0, 1, 127, 0, 0, 1, 0, 80]);
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
    // Darwin can reject SO_RCVTIMEO after a reset; set it while the peer is connected.
    upstream.set_read_timeout(Some(Duration::from_millis(250))).expect("bound terminal EOF check");
    let stopped_at = Instant::now();
    control.request_shutdown();
    let receipt =
        done_rx.recv_timeout(Duration::from_secs(5)).expect("bounded runtime return").expect("runtime result");
    runtime.join().expect("join runtime thread");
    assert!(receipt.forced_abort(), "{phase:?} must use forced drain");
    assert!(!receipt.worker_panicked(), "{phase:?} must not panic");
    assert!(stopped_at.elapsed() < Duration::from_secs(5));
    let mut tail = [0; 10];
    let terminal_read = upstream.read(&mut tail);
    if let Some((stop, handle)) = drip {
        let _ = stop.send(());
        handle.join().expect("join drip writer");
    }
    assert!(
        match &terminal_read {
            Ok(length) => *length == 0,
            Err(error) => error.kind() == std::io::ErrorKind::ConnectionReset,
        },
        "{phase:?} negotiation socket must close with EOF or reset before terminal return: {terminal_read:?}"
    );
}
