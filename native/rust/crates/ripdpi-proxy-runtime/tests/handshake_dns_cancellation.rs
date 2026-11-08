#![cfg(not(feature = "loom"))]

#[path = "support/stalled_dns.rs"]
mod stalled_dns;

use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpStream};
use std::sync::{Arc, mpsc};
use std::thread;
use std::time::{Duration, Instant};

use ripdpi_proxy_runtime::{create_listener, prepare_embedded, run_proxy_with_embedded_control_receipt};
use ripdpi_proxy_runtime_adapter::model::config::RuntimeConfig;
use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;
use stalled_dns::StalledDns;

#[test]
fn runtime_stop_cancels_socks4a_handshake_dns() {
    check(false);
}

#[test]
fn absolute_handshake_deadline_cancels_socks4a_dns() {
    check(true);
}

fn check(deadline: bool) {
    prepare_embedded();
    let dns = StalledDns::start();
    let mut config = RuntimeConfig::default();
    config.network.listen.listen_ip = Ipv4Addr::LOCALHOST.into();
    config.network.listen.listen_port = 0;
    config.network.ipv6 = false;
    let listener = create_listener(&config).expect("proxy listener");
    let address = listener.local_addr().expect("proxy address");
    let control = Arc::new(EmbeddedProxyControl::new_with_context(None, Some(dns.context())));
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
    let mut client = TcpStream::connect(address).expect("proxy client");
    client.set_read_timeout(Some(Duration::from_secs(1))).expect("client timeout");
    // Start the reader's absolute deadline before the rest of the request arrives.
    client.write_all(&[4]).expect("version");
    if deadline {
        thread::sleep(Duration::from_millis(4400));
    }
    client.write_all(b"\x01\x01\xbb\0\0\0\x01\0loc04.test\0").expect("SOCKS4a request");
    dns.started.recv_timeout(Duration::from_secs(2)).expect("local DNS request");
    let stopped = Instant::now();
    if !deadline {
        control.request_shutdown();
    }
    let dns_closed = dns.closed.recv_timeout(Duration::from_secs(1));
    let mut reply = [0; 8];
    let reply_result = if deadline { Some(client.read_exact(&mut reply)) } else { None };
    control.request_shutdown();
    let early_result = done_rx.recv_timeout(Duration::from_secs(2));
    // Release the fixture even when the implementation fails cancellation.
    let _ = dns.release.send(());
    let receipt = early_result
        .unwrap_or_else(|_| done_rx.recv_timeout(Duration::from_secs(5)).expect("runtime cleanup"))
        .expect("runtime result");
    runtime.join().expect("runtime worker");
    assert!(dns_closed.expect("handshake DNS must close within one second"), "DNS was closed by fixture release");
    assert!(!receipt.forced_abort(), "DNS stop must finish in the graceful window");
    assert!(!receipt.worker_panicked());
    assert!(stopped.elapsed() < Duration::from_secs(3));
    if let Some(result) = reply_result {
        result.expect("failure reply after DNS deadline");
        assert_eq!(reply, [0, 0x5b, 0, 0, 0, 0, 0, 0]);
    }
}
