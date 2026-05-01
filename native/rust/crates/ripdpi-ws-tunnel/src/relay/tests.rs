use super::*;

use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, Shutdown, TcpListener};
use std::thread;
use std::time::{Duration, Instant};

use tungstenite::protocol::Message;

fn tcp_pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind tcp listener");
    let addr = listener.local_addr().expect("tcp listener addr");
    let client = TcpStream::connect(addr).expect("connect tcp pair");
    let (server, _) = listener.accept().expect("accept tcp pair");
    client.set_read_timeout(Some(Duration::from_secs(1))).expect("set client timeout");
    server.set_read_timeout(Some(Duration::from_secs(1))).expect("set server timeout");
    (client, server)
}

fn websocket_pair() -> (WebSocket<TcpStream>, WebSocket<TcpStream>) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind websocket listener");
    let addr = listener.local_addr().expect("websocket listener addr");
    let accept_thread = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("accept websocket connection");
        tungstenite::accept(stream).expect("accept websocket")
    });

    let stream = TcpStream::connect(addr).expect("connect websocket");
    stream.set_nodelay(true).expect("set client nodelay");
    let (mut client_ws, _response) =
        tungstenite::client(format!("ws://{addr}"), stream).expect("client websocket handshake");
    client_ws.get_mut().set_read_timeout(Some(crate::connect::WS_READ_TIMEOUT)).expect("set client websocket timeout");

    let mut server_ws = accept_thread.join().expect("join websocket accept thread");
    server_ws.get_mut().set_read_timeout(Some(Duration::from_millis(50))).expect("set server websocket timeout");

    (client_ws, server_ws)
}

fn read_message_retry(peer: &mut WebSocket<TcpStream>) -> Message {
    for _ in 0..20 {
        match peer.read() {
            Ok(message) => return message,
            Err(tungstenite::Error::Io(ref err))
                if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) =>
            {
                thread::sleep(Duration::from_millis(20));
                continue;
            }
            Err(err) => panic!("read websocket message: {err}"),
        }
    }
    panic!("timed out waiting for websocket message");
}

fn read_binary_payload(peer: &mut WebSocket<TcpStream>, expected_len: usize, timeout: Duration) -> Vec<u8> {
    let deadline = Instant::now() + timeout;
    let mut collected = Vec::with_capacity(expected_len);
    while collected.len() < expected_len {
        assert!(Instant::now() < deadline, "timed out waiting for {expected_len} bytes, collected {}", collected.len());
        match peer.read() {
            Ok(Message::Binary(data)) => collected.extend_from_slice(&data),
            Ok(Message::Ping(_) | Message::Pong(_)) => {}
            Ok(Message::Close(_)) => break,
            Ok(_) => {}
            Err(tungstenite::Error::Io(ref err))
                if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) =>
            {
                continue;
            }
            Err(err) => panic!("read binary payload: {err}"),
        }
    }
    collected
}

fn wait_for_close(peer: &mut WebSocket<TcpStream>) {
    let deadline = Instant::now() + Duration::from_secs(1);
    while Instant::now() < deadline {
        match peer.read() {
            Ok(Message::Close(_)) => return,
            Ok(_) => continue,
            Err(tungstenite::Error::ConnectionClosed | tungstenite::Error::AlreadyClosed) => return,
            Err(tungstenite::Error::Io(ref err))
                if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) =>
            {
                continue;
            }
            Err(err) => panic!("wait for close: {err}"),
        }
    }
    panic!("timed out waiting for websocket close");
}

#[test]
fn ws_relay_forwards_init_uplink_and_downlink_frames() {
    let (mut local_app, relay_client) = tcp_pair();
    let (ws, mut peer) = websocket_pair();
    let seed_request = vec![0xAB; 64];

    let relayed_seed = seed_request.clone();
    let relay_thread = thread::spawn(move || ws_relay(relay_client, ws, &relayed_seed));

    assert!(matches!(read_message_retry(&mut peer), Message::Binary(data) if data[..] == seed_request[..]));

    local_app.write_all(b"uplink").expect("write uplink");
    assert!(matches!(read_message_retry(&mut peer), Message::Binary(data) if data[..] == b"uplink"[..]));

    peer.send(Message::Ping(vec![1, 2, 3].into())).expect("send ping");
    peer.send(Message::Binary(b"downlink".to_vec().into())).expect("send downlink");
    peer.close(None).expect("send close");

    let mut downlink = [0u8; 8];
    local_app.read_exact(&mut downlink).expect("read downlink");
    assert_eq!(&downlink, b"downlink");

    relay_thread.join().expect("join relay thread").expect("relay result");
}

#[test]
fn ws_relay_drains_outbound_queue_while_websocket_reader_is_idle() {
    let (mut local_app, relay_client) = tcp_pair();
    let (ws, mut peer) = websocket_pair();
    let seed_request = vec![0xCD; 64];
    let expected = vec![0x5E; 16_384 * 4];

    let relayed_seed = seed_request.clone();
    let relay_thread = thread::spawn(move || ws_relay(relay_client, ws, &relayed_seed));

    assert!(matches!(read_message_retry(&mut peer), Message::Binary(data) if data[..] == seed_request[..]));

    let started = Instant::now();
    local_app.write_all(&expected).expect("write uplink payload");
    let received = read_binary_payload(&mut peer, expected.len(), Duration::from_millis(300));

    assert_eq!(received, expected);
    assert!(
        started.elapsed() < Duration::from_millis(300),
        "uplink delivery should stay below the old 100ms-per-frame cadence: {:?}",
        started.elapsed()
    );

    local_app.shutdown(Shutdown::Write).expect("shutdown local app write");
    wait_for_close(&mut peer);
    relay_thread.join().expect("join relay thread").expect("relay result");
}

#[test]
fn ws_relay_exits_cleanly_when_client_closes_write_half() {
    let (local_app, relay_client) = tcp_pair();
    let (ws, mut peer) = websocket_pair();
    let seed_request = vec![0x11; 64];

    let relayed_seed = seed_request.clone();
    let relay_thread = thread::spawn(move || ws_relay(relay_client, ws, &relayed_seed));

    assert!(matches!(read_message_retry(&mut peer), Message::Binary(data) if data[..] == seed_request[..]));
    local_app.shutdown(Shutdown::Write).expect("shutdown local app write");

    wait_for_close(&mut peer);
    relay_thread.join().expect("join relay thread").expect("relay result");
}

#[test]
fn ws_relay_exits_cleanly_when_remote_websocket_closes() {
    let (_local_app, relay_client) = tcp_pair();
    let (ws, mut peer) = websocket_pair();
    let seed_request = vec![0x22; 64];

    let relayed_seed = seed_request.clone();
    let relay_thread = thread::spawn(move || ws_relay(relay_client, ws, &relayed_seed));

    assert!(matches!(read_message_retry(&mut peer), Message::Binary(data) if data[..] == seed_request[..]));
    peer.close(None).expect("send websocket close");

    relay_thread.join().expect("join relay thread").expect("relay result");
}

#[test]
#[ignore = "manual throughput comparison"]
fn ws_relay_uplink_throughput_benchmark() {
    let (mut local_app, relay_client) = tcp_pair();
    let (ws, mut peer) = websocket_pair();
    let seed_request = vec![0x33; 64];
    let payload = vec![0x7A; 8 * 1024 * 1024];
    let payload_len = payload.len();

    let relayed_seed = seed_request.clone();
    let relay_thread = thread::spawn(move || ws_relay(relay_client, ws, &relayed_seed));

    assert!(matches!(read_message_retry(&mut peer), Message::Binary(data) if data[..] == seed_request[..]));

    let started = Instant::now();
    let writer_thread = thread::spawn(move || {
        local_app.write_all(&payload).expect("write benchmark payload");
        local_app.shutdown(Shutdown::Write).expect("shutdown local app write");
    });

    let received = read_binary_payload(&mut peer, payload_len, Duration::from_secs(10));
    writer_thread.join().expect("join benchmark writer");
    let elapsed = started.elapsed();
    let throughput_mib = payload_len as f64 / elapsed.as_secs_f64() / (1024.0 * 1024.0);
    tracing::info!("WS relay uplink benchmark: {payload_len} bytes in {elapsed:?} ({throughput_mib:.2} MiB/s)");

    assert_eq!(received.len(), payload_len);
    wait_for_close(&mut peer);
    relay_thread.join().expect("join relay thread").expect("relay result");
}

#[test]
fn ws_relay_sends_consumed_remainder_before_socket_drain() {
    let (_local_app, relay_client) = tcp_pair();
    let (ws, mut peer) = websocket_pair();
    let mut seed_request = vec![0x44; 64];
    seed_request.extend_from_slice(b"rest");

    let relay_thread = thread::spawn(move || ws_relay(relay_client, ws, &seed_request));

    assert!(matches!(read_message_retry(&mut peer), Message::Binary(data) if data[..] == vec![0x44; 64][..]));
    assert!(matches!(read_message_retry(&mut peer), Message::Binary(data) if data[..] == b"rest"[..]));

    peer.close(None).expect("send websocket close");
    relay_thread.join().expect("join relay thread").expect("relay result");
}
