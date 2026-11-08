#![cfg(all(target_os = "linux", not(feature = "loom")))]
#![deny(unsafe_op_in_unsafe_fn)]
#![deny(clippy::undocumented_unsafe_blocks, clippy::multiple_unsafe_ops_per_block, clippy::missing_safety_doc)]

// Exercise the private helper without changing the library's unsafe prohibition.
#[path = "../src/platform/connect/cancel.rs"]
mod cancel;

use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::os::fd::AsRawFd;
use std::time::{Duration, Instant};

use ripdpi_proxy_runtime_adapter::platform::connect::enable_tcp_fastopen_connect;
use socket2::{Domain, Protocol, Socket, Type};

#[test]
fn deferred_fast_open_returns_before_first_write_and_delivers_exact_bytes() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    listener.set_nonblocking(true).expect("nonblocking listener");
    let socket = deferred_fast_open_socket();
    cancel::connect(
        &socket,
        listener.local_addr().expect("listener address"),
        Some(Duration::from_millis(300)),
        &|| false,
    )
    .expect("successful deferred Fast Open connect");
    assert!(!socket.nonblocking().expect("blocking mode"));
    let deferred = socket.peer_addr().expect_err("Fast Open still waits for its first write");
    assert_eq!(deferred.raw_os_error(), Some(libc::ENOTCONN));
    assert_eq!(listener.accept().expect_err("no SYN before first write").kind(), std::io::ErrorKind::WouldBlock);

    let mut client = TcpStream::from(socket);
    client.set_write_timeout(Some(Duration::from_secs(1))).expect("client write timeout");
    client.set_read_timeout(Some(Duration::from_secs(1))).expect("client read timeout");
    client.write_all(b"TFO-ORACLE").expect("first write starts TCP");
    let deadline = Instant::now() + Duration::from_secs(2);
    let mut origin = loop {
        match listener.accept() {
            Ok((origin, _)) => break origin,
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock && Instant::now() < deadline => {
                std::thread::sleep(Duration::from_millis(5));
            }
            Err(error) => panic!("first write did not establish TCP: {error}"),
        }
    };
    origin.set_read_timeout(Some(Duration::from_secs(1))).expect("origin read timeout");
    let mut request = [0; 10];
    origin.read_exact(&mut request).expect("literal first-write payload");
    assert_eq!(&request, b"TFO-ORACLE");
    origin.write_all(b"TFO-ACK").expect("origin response");
    let mut response = [0; 7];
    client.read_exact(&mut response).expect("blocking client response");
    assert_eq!(&response, b"TFO-ACK");
}

#[test]
fn cancellation_after_fast_open_autobind_prevents_success_and_first_write() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
    listener.set_nonblocking(true).expect("nonblocking listener");
    let socket = deferred_fast_open_socket();
    let cancelled = || socket.local_addr().expect("socket address").as_socket().expect("IP socket").port() != 0;
    assert!(!cancelled(), "a new socket has not been auto-bound");
    let error = cancel::connect(&socket, listener.local_addr().expect("address"), None, &cancelled)
        .expect_err("shutdown after the kernel accepts deferred connect");
    assert_eq!(error.kind(), std::io::ErrorKind::ConnectionAborted);
    assert_eq!(listener.accept().expect_err("cancelled before first write").kind(), std::io::ErrorKind::WouldBlock);
}

fn deferred_fast_open_socket() -> Socket {
    let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP)).expect("socket");
    enable_tcp_fastopen_connect(&socket).expect("TCP_FASTOPEN_CONNECT");
    let enabled: libc::c_int = 1;
    let len = libc::socklen_t::try_from(std::mem::size_of_val(&enabled)).expect("c_int option length");
    // SAFETY: socket owns a live TCP fd. TCP_FASTOPEN_NO_COOKIE reads one c_int;
    // enabled is initialized, aligned, and alive through this call, and len is its exact size.
    let result = unsafe {
        libc::setsockopt(
            socket.as_raw_fd(),
            libc::IPPROTO_TCP,
            libc::TCP_FASTOPEN_NO_COOKIE,
            (&enabled as *const libc::c_int).cast(),
            len,
        )
    };
    assert_eq!(result, 0, "TCP_FASTOPEN_NO_COOKIE: {}", std::io::Error::last_os_error());
    socket
}
