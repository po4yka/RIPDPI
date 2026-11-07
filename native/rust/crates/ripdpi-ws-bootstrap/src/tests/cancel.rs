use super::fixture_runtime_context;
use std::cell::Cell;
use std::io::{self, Read};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};

use ripdpi_dns_resolver::{EncryptedDnsConnectHooks, EncryptedDnsResolver, EncryptedDnsTransport};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

fn stalled_server() -> (u16, mpsc::Receiver<()>, thread::JoinHandle<SocketAddr>) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("DNS listener");
    let port = listener.local_addr().expect("DNS address").port();
    let (received_tx, received_rx) = mpsc::channel();
    let server = thread::spawn(move || {
        let (mut stream, peer) = listener.accept().expect("DNS connection");
        stream.set_read_timeout(Some(Duration::from_secs(2))).expect("server timeout");
        let mut bytes = [0; 4096];
        assert!(stream.read(&mut bytes).expect("DNS request") > 0);
        received_tx.send(()).expect("request reached server");
        loop {
            if stream.read(&mut bytes).expect("cancelled DNS stream closes") == 0 {
                return peer;
            }
        }
    });
    (port, received_rx, server)
}

#[test]
fn host_resolution_cancels_a_stalled_dns_query() {
    let (port, received, server) = stalled_server();
    let context = fixture_runtime_context(port);
    let cancelled = Cell::new(false);
    let should_cancel = || {
        if received.try_recv().is_ok() {
            cancelled.set(true);
        }
        cancelled.get()
    };
    let started = Instant::now();
    let error =
        crate::resolve_host_via_encrypted_dns_with_cancel("fixture.test", Some(&context), None, false, &should_cancel)
            .expect_err("caller cancels DNS");
    assert_eq!(error.kind(), io::ErrorKind::Interrupted);
    assert!(started.elapsed() < Duration::from_secs(1));
    server.join().expect("DNS socket closed");
}

#[test]
fn asynchronous_socket_hook_uses_the_supplied_socket_and_cancels() {
    let (port, received, server) = stalled_server();
    let context = fixture_runtime_context(port);
    let (created_tx, created_rx) = mpsc::channel();
    let hooks = EncryptedDnsConnectHooks::new().require_direct_tcp_connector().with_direct_tcp_socket(move |_| {
        let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP))?;
        socket.bind(&SockAddr::from(SocketAddr::from((Ipv4Addr::LOCALHOST, 0))))?;
        let local = socket.local_addr()?.as_socket().expect("TCP socket address");
        created_tx.send(local.port()).expect("created socket source");
        Ok(TcpStream::from(socket))
    });
    let resolver = EncryptedDnsResolver::with_connect_hooks(
        crate::endpoint::encrypted_dns_endpoint(context.encrypted_dns.as_ref().expect("DNS context")).unwrap(),
        EncryptedDnsTransport::Direct,
        hooks,
    )
    .expect("resolver");
    let cancelled = Cell::new(false);
    let should_cancel = || {
        if received.try_recv().is_ok() {
            cancelled.set(true);
        }
        cancelled.get()
    };
    let query = crate::query::build_dns_query("fixture.test", crate::query::DNS_RECORD_TYPE_A, 7).unwrap();
    let started = Instant::now();
    let error = resolver.exchange_blocking_with_cancel(&query, &should_cancel).expect_err("cancelled exchange");
    assert!(error.to_string().contains("cancelled"), "{error}");
    assert!(started.elapsed() < Duration::from_secs(1));
    let peer = server.join().expect("cancel closes the owned socket");
    assert_eq!(peer.port(), created_rx.recv().expect("source of supplied socket"));
}
