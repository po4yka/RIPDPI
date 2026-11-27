use super::*;
use crate::session::test_socket::{TestSocketPath, UNIX_SOCKET_TEST_LOCK};
use ripdpi_socks5_core::descriptor::recv_fd;
use ripdpi_socks5_core::udp_feedback::{self, Frame, RemoteError};
use std::os::fd::AsFd;
use tokio::io::{AsyncReadExt, AsyncWriteExt, Interest};
use tokio::net::UnixListener;
use tokio::sync::oneshot;

const NONCE: udp_feedback::Nonce = *b"abcdefghijklmnop";
const DESTINATION: &str = "192.0.2.7:443";

fn auth() -> Auth {
    Auth::UserPass { username: "u".into(), password: "p".into() }
}

fn target() -> TargetAddr {
    TargetAddr::Ip(DESTINATION.parse().unwrap())
}

fn budget() -> UdpMemoryBudget {
    UdpMemoryBudget::new(MAX_UDP_DATAGRAM_LEN, 1, DEFAULT_QUEUED_UDP_BYTES)
}

/// # Cancel safety:
/// NOT cancel-safe: the test authentication consumes a complete control exchange.
async fn authenticate(stream: &mut UnixStream) {
    let mut credentials = [0; 8];
    stream.read_exact(&mut credentials[..3]).await.unwrap();
    assert_eq!(credentials[..3], [5, 1, 2]);
    stream.write_all(&[5, 2]).await.unwrap();
    stream.read_exact(&mut credentials[..5]).await.unwrap();
    assert_eq!(credentials[..5], [1, 1, b'u', 1, b'p']);
    stream.write_all(&[1, 0]).await.unwrap();
}

/// # Cancel safety:
/// NOT cancel-safe: the test peer reads a descriptor with one non-repeatable `recvmsg`.
async fn receive_datagram_socket(control: &UnixStream) -> UnixDatagram {
    let fd = loop {
        control.readable().await.unwrap();
        match control.try_io(Interest::READABLE, || recv_fd(control.as_fd())) {
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => {}
            result => break result.unwrap(),
        }
    };
    let socket = std::os::unix::net::UnixDatagram::from(fd);
    socket.set_nonblocking(true).unwrap();
    UnixDatagram::from_std(socket).unwrap()
}

/// # Cancel safety:
/// NOT cancel-safe: the test peer consumes a complete negotiation.
async fn accept_association(listener: &UnixListener, ready: oneshot::Sender<()>) -> (UnixStream, UnixDatagram) {
    let (mut control, _) = listener.accept().await.unwrap();
    authenticate(&mut control).await;
    let mut request = [0; 10];
    control.read_exact(&mut request).await.unwrap();
    assert_eq!(request, [5, 0x80, 0, 1, 0, 0, 0, 0, 0, 0]);
    control.write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0]).await.unwrap();
    control.write_all(&udp_feedback::encode_preface(&NONCE)).await.unwrap();
    let datagrams = receive_datagram_socket(&control).await;
    let _ = ready.send(());
    (control, datagrams)
}

async fn connect(socket: &TestSocketPath) -> io::Result<UdpSession> {
    let upstream = ProxyUpstream::Unix(socket.path().to_owned());
    let session = UdpSession::connect_with_memory_budget(&upstream, auth(), None, budget()).await?;
    Ok(session.with_recv_timeout(Duration::from_secs(2)))
}

fn request_id_and_payload(wire: &[u8]) -> (u64, Vec<u8>) {
    let Frame::Request { id, payload } = udp_feedback::decode(wire, &NONCE).unwrap() else {
        panic!("expected a request frame");
    };
    (id, payload.to_vec())
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns a spawned peer that outlives cancellation until runtime teardown.
#[tokio::test]
async fn unix_private_association_round_trips_a_frame() {
    let _guard = UNIX_SOCKET_TEST_LOCK.lock().await;
    let socket = TestSocketPath::new();
    let listener = UnixListener::bind(socket.path()).unwrap();
    let (ready_tx, ready_rx) = oneshot::channel();
    let server = tokio::spawn(async move {
        let (control, datagrams) = accept_association(&listener, ready_tx).await;
        let mut wire = vec![0; MAX_UDP_DATAGRAM_LEN];
        let len = datagrams.recv(&mut wire).await.unwrap();
        let (id, payload) = request_id_and_payload(&wire[..len]);
        assert_eq!(id, 7);
        assert_eq!(decode_udp_target_frame(&payload).unwrap().1, b"ping");
        let reply = encode_udp_target_frame(&target(), b"pong").unwrap();
        datagrams.send(&udp_feedback::encode_response(&NONCE, &reply).unwrap()).await.unwrap();
        wait_for_close(control).await;
    });
    let session = connect(&socket).await.unwrap();
    ready_rx.await.unwrap();

    assert!(session.has_feedback());
    session.send_tagged(&target(), b"ping", 7).await.unwrap();
    let event = session.recv_event(CancellationToken::new()).await.unwrap().expect("a reply");
    let UdpSessionEvent::Datagram { payload, target: from } = event else { panic!("expected a datagram event") };
    assert_eq!((payload.as_slice(), from), (b"pong".as_slice(), target()));
    drop(session);
    server.await.unwrap();
}

async fn wait_for_close(mut control: UnixStream) {
    let _ = control.read(&mut [0]).await;
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns a spawned peer that outlives cancellation until runtime teardown.
#[tokio::test]
async fn unix_remote_error_frame_becomes_the_same_session_event() {
    let _guard = UNIX_SOCKET_TEST_LOCK.lock().await;
    let socket = TestSocketPath::new();
    let listener = UnixListener::bind(socket.path()).unwrap();
    let error = RemoteError {
        source: "198.51.100.1".parse().unwrap(),
        target: DESTINATION.parse().unwrap(),
        icmp_type: 3,
        icmp_code: 1,
        info: 0,
    };
    let (ready_tx, ready_rx) = oneshot::channel();
    let server = tokio::spawn(async move {
        let (control, datagrams) = accept_association(&listener, ready_tx).await;
        let mut wire = vec![0; MAX_UDP_DATAGRAM_LEN];
        let len = datagrams.recv(&mut wire).await.unwrap();
        let (id, _) = request_id_and_payload(&wire[..len]);
        datagrams.send(&udp_feedback::encode_error(&NONCE, id, &error).unwrap()).await.unwrap();
        wait_for_close(control).await;
    });
    let session = connect(&socket).await.unwrap();
    ready_rx.await.unwrap();

    session.send_tagged(&target(), b"probe", 9).await.unwrap();
    let event = session.recv_event(CancellationToken::new()).await.unwrap().expect("an error event");
    let UdpSessionEvent::RemoteError { id, error: received } = event else { panic!("expected a remote error") };
    assert_eq!((id, received), (9, error));
    drop(session);
    server.await.unwrap();
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns a spawned peer that outlives cancellation until runtime teardown.
#[tokio::test]
async fn unix_refusal_of_the_private_command_fails_without_a_second_attempt() {
    let _guard = UNIX_SOCKET_TEST_LOCK.lock().await;
    let socket = TestSocketPath::new();
    let listener = UnixListener::bind(socket.path()).unwrap();
    let server = tokio::spawn(async move {
        let (mut control, _) = listener.accept().await.unwrap();
        authenticate(&mut control).await;
        let mut request = [0; 10];
        control.read_exact(&mut request).await.unwrap();
        control.write_all(&[5, 7, 0, 1, 0, 0, 0, 0, 0, 0]).await.unwrap();
        drop(control);
        let second = tokio::time::timeout(Duration::from_millis(300), listener.accept()).await;
        assert!(second.is_err(), "a refusal must not be retried with a standard ASSOCIATE");
    });

    let error = connect(&socket).await.err().expect("a refusal fails the flow");

    assert_eq!(error.kind(), io::ErrorKind::ConnectionRefused);
    server.await.unwrap();
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns a spawned peer that outlives cancellation until runtime teardown.
#[tokio::test]
async fn unix_association_carries_a_maximum_size_frame() {
    let _guard = UNIX_SOCKET_TEST_LOCK.lock().await;
    let socket = TestSocketPath::new();
    let listener = UnixListener::bind(socket.path()).unwrap();
    let (ready_tx, ready_rx) = oneshot::channel();
    let server = tokio::spawn(async move {
        let (control, datagrams) = accept_association(&listener, ready_tx).await;
        let mut wire = vec![0; MAX_UDP_DATAGRAM_LEN];
        let len = datagrams.recv(&mut wire).await.unwrap();
        assert_eq!(len, udp_feedback::MAX_WIRE_LEN);
        let (_, payload) = request_id_and_payload(&wire[..len]);
        datagrams.send(&udp_feedback::encode_response(&NONCE, &payload).unwrap()).await.unwrap();
        wait_for_close(control).await;
    });
    let session = connect(&socket).await.unwrap();
    ready_rx.await.unwrap();
    let data = vec![0x5a; udp_feedback::MAX_WIRE_LEN - udp_feedback::HEADER_LEN - 10];

    session.send_tagged(&target(), &data, 1).await.unwrap();
    let event = session.recv_event(CancellationToken::new()).await.unwrap().expect("the echoed frame");
    let UdpSessionEvent::Datagram { payload, .. } = event else { panic!("expected a datagram event") };
    assert_eq!(payload, data);
    drop(session);
    server.await.unwrap();
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns a spawned peer that outlives cancellation until runtime teardown.
#[tokio::test]
async fn unix_control_eof_ends_the_session() {
    let _guard = UNIX_SOCKET_TEST_LOCK.lock().await;
    let socket = TestSocketPath::new();
    let listener = UnixListener::bind(socket.path()).unwrap();
    let (ready_tx, _ready_rx) = oneshot::channel();
    let server = tokio::spawn(async move {
        let (control, datagrams) = accept_association(&listener, ready_tx).await;
        drop(control);
        datagrams
    });
    let session = connect(&socket).await.unwrap();
    let _datagrams = server.await.unwrap();

    let received = tokio::time::timeout(Duration::from_secs(1), session.recv_event(CancellationToken::new()))
        .await
        .expect("control close must wake the receiver");
    assert!(received.is_err());
    assert!(session.send_tagged(&target(), b"late", 2).await.is_err());
}

/// # Cancel safety:
/// NOT cancel-safe: the test owns a spawned peer that outlives cancellation until runtime teardown.
#[tokio::test]
async fn unix_full_socket_drops_datagrams_and_recovers_after_the_reader_drains() {
    let _guard = UNIX_SOCKET_TEST_LOCK.lock().await;
    let socket = TestSocketPath::new();
    let listener = UnixListener::bind(socket.path()).unwrap();
    let (ready_tx, ready_rx) = oneshot::channel();
    let server = tokio::spawn(async move {
        let (control, datagrams) = accept_association(&listener, ready_tx).await;
        tokio::time::sleep(Duration::from_millis(600)).await;
        let mut wire = vec![0; MAX_UDP_DATAGRAM_LEN];
        while tokio::time::timeout(Duration::from_millis(100), datagrams.recv(&mut wire)).await.is_ok() {}
        let len = datagrams.recv(&mut wire).await.unwrap();
        assert_eq!(request_id_and_payload(&wire[..len]).0, 999);
        wait_for_close(control).await;
    });
    let session = connect(&socket).await.unwrap();
    ready_rx.await.unwrap();
    let data = vec![0x5a; 60_000];

    let started = std::time::Instant::now();
    for id in 1..200 {
        session.send_tagged(&target(), &data, id).await.expect("a full socket drops, it does not fail");
    }
    assert!(started.elapsed() < Duration::from_millis(500), "sends must not wait for the reader");
    tokio::time::sleep(Duration::from_millis(900)).await;
    session.send_tagged(&target(), b"after", 999).await.unwrap();
    drop(session);
    server.await.unwrap();
}
