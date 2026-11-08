#![cfg(not(feature = "loom"))]

use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, Shutdown, TcpListener, TcpStream};
use std::sync::{Arc, mpsc};
use std::thread;
use std::time::Duration;

use ripdpi_proxy_runtime::{create_listener, run_proxy_with_embedded_control_receipt};
use ripdpi_proxy_runtime_adapter::model::config::{RuntimeConfig, UpstreamSocksConfig};
use ripdpi_proxy_runtime_adapter::model::proxy_config::{ProxyPreferredEdge, ProxyRuntimeContext};
use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;

#[test]
fn ephemeral_plaintext_listener_is_excluded_after_preferred_edge_substitution() {
    let upstream = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind local SOCKS peer");
    let upstream_addr = upstream.local_addr().expect("SOCKS peer address");
    upstream.set_nonblocking(true).expect("nonblocking SOCKS listener");
    let (stop_tx, stop_rx) = mpsc::channel();
    let (targets_tx, targets_rx) = mpsc::channel();
    let fixture = thread::spawn(move || {
        while stop_rx.try_recv().is_err() {
            let (mut stream, _) = match upstream.accept() {
                Ok(connection) => connection,
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                    thread::sleep(Duration::from_millis(5));
                    continue;
                }
                Err(error) => panic!("accept SOCKS peer: {error}"),
            };
            stream.set_nonblocking(false).expect("blocking accepted SOCKS peer");
            stream.set_read_timeout(Some(Duration::from_secs(1))).expect("SOCKS peer read timeout");
            let mut greeting = [0; 3];
            stream.read_exact(&mut greeting).expect("SOCKS greeting");
            assert_eq!(greeting, [5, 1, 0]);
            stream.write_all(&[5, 0]).expect("select no auth");
            let mut request = [0; 10];
            stream.read_exact(&mut request).expect("SOCKS CONNECT");
            assert_eq!(&request[..4], &[5, 1, 0, 1]);
            targets_tx.send(request).expect("record independent wire bytes");
            // The fixture never dials the supplied TEST-NET address.
            stream.write_all(&[5, 1, 0, 1, 0, 0, 0, 0, 0, 0]).expect("reject CONNECT");
        }
    });

    let mut config = RuntimeConfig::default();
    config.network.listen.listen_port = 0;
    config.network.ipv6 = false;
    config.network.shadowsocks = true;
    config.max_route_retries = 0;
    config.groups[0].policy.ext_socks = Some(UpstreamSocksConfig { addr: upstream_addr, auth: None });
    let listener = create_listener(&config).expect("create ephemeral plaintext listener");
    let address = listener.local_addr().expect("actual bound address");
    let mut context = ProxyRuntimeContext::default();
    context.preferred_edges.insert(
        "192.0.2.7".to_string(),
        vec![ProxyPreferredEdge { ip: "127.0.0.1".to_string(), transport_kind: "tcp".to_string() }],
    );
    let control = Arc::new(EmbeddedProxyControl::new_with_context(None, Some(context)));
    let worker_control = control.clone();
    let (done_tx, done_rx) = mpsc::channel();
    let worker = thread::spawn(move || {
        let result = run_proxy_with_embedded_control_receipt(
            config,
            listener,
            worker_control,
            Arc::new(ripdpi_ws_tunnel::TelegramWsTransport),
        );
        let _ = done_tx.send(result);
    });
    let mut client = TcpStream::connect(address).expect("connect plaintext client");
    client.set_read_timeout(Some(Duration::from_secs(2))).expect("client read timeout");
    let mut request = b"\x03\x09192.0.2.7".to_vec();
    request.extend(address.port().to_be_bytes());
    request.extend(b"PAYLOAD");
    client.write_all(&request).expect("send independent plaintext request");
    let mut byte = [0; 1];
    let client_result = client.read(&mut byte);
    let _ = client.shutdown(Shutdown::Both);
    control.request_shutdown();
    let receipt =
        done_rx.recv_timeout(Duration::from_secs(5)).expect("bounded runtime cleanup").expect("runtime result");
    worker.join().expect("runtime thread");
    stop_tx.send(()).expect("stop SOCKS peer");
    fixture.join().expect("SOCKS peer thread");
    let targets: Vec<_> = targets_rx.into_iter().collect();
    let [high, low] = address.port().to_be_bytes();
    let expected = [5, 1, 0, 1, 192, 0, 2, 7, high, low];
    assert_eq!(targets, vec![expected], "only the original target may be sent to the upstream peer");
    assert!(
        matches!(client_result, Ok(0))
            || matches!(client_result, Err(ref error) if error.kind() == io::ErrorKind::ConnectionReset)
    );
    assert!(!receipt.worker_panicked());
}
