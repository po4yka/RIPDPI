use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, SyncSender, TryRecvError, TrySendError};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use tungstenite::protocol::Message;
use tungstenite::WebSocket;

const CLIENT_READ_TIMEOUT: Duration = Duration::from_millis(250);
const CLOSE_HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
const OUTBOUND_QUEUE_CAPACITY: usize = 16;
const MAX_OUTBOUND_BURST: usize = 8;
const OUTBOUND_QUEUE_RETRY_DELAY: Duration = Duration::from_millis(1);

/// Bidirectional relay between a local TCP client and a WebSocket tunnel.
///
/// Sends `init_packet` as the first binary WS frame, then relays data in both
/// directions until either side closes or an error occurs.
///
/// Uses two threads:
/// - Main thread: owns the WebSocket, drains outbound frames, reads inbound WS
///   frames, and writes to client TCP
/// - Spawned thread: reads from client TCP and queues outbound WS binary frames
///
/// `tungstenite::WebSocket::read()` automatically replies to Ping frames with
/// Pong, so no explicit Ping/Pong handling is needed.
pub fn ws_relay<S: Read + Write + Send + 'static>(
    client: TcpStream,
    mut ws: WebSocket<S>,
    init_packet: &[u8; 64],
) -> io::Result<()> {
    let shutdown = Arc::new(AtomicBool::new(false));
    let (outbound_tx, outbound_rx) = mpsc::sync_channel(OUTBOUND_QUEUE_CAPACITY);

    // Send the 64-byte obfuscated2 init as the first WS frame.
    ws.send(Message::Binary(init_packet.to_vec().into()))
        .map_err(|e| io::Error::new(io::ErrorKind::BrokenPipe, format!("WS send init: {e}")))?;

    let client_reader = client.try_clone()?;
    let client_writer = client;

    // Spawn uplink thread: client TCP -> bounded outbound queue
    let shutdown_up = shutdown.clone();
    let uplink = thread::Builder::new()
        .name("ripdpi-ws-up".into())
        .spawn(move || uplink_loop(client_reader, outbound_tx, &shutdown_up))
        .map_err(|e| io::Error::other(format!("spawn ws-up: {e}")))?;

    // Main thread owns the WebSocket and performs both the downlink read path
    // and the queued outbound write path.
    let result = relay_loop(client_writer, &mut ws, &outbound_rx, &shutdown);

    shutdown.store(true, Ordering::Release);
    drop(outbound_rx);

    let uplink_panicked = uplink.join().is_err();
    drive_close_handshake(&mut ws);

    if uplink_panicked && result.is_ok() {
        return Err(io::Error::other("join ws-up: thread panicked"));
    }

    result
}

fn uplink_loop(mut reader: TcpStream, outbound_tx: SyncSender<Vec<u8>>, shutdown: &AtomicBool) {
    // Short read timeout so we can check the shutdown flag while keeping the
    // TCP reader responsive to relay shutdown.
    let _ = reader.set_read_timeout(Some(CLIENT_READ_TIMEOUT));
    let mut buf = [0u8; 16_384];

    'uplink: loop {
        if shutdown.load(Ordering::Acquire) {
            break;
        }
        match reader.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                let mut payload = buf[..n].to_vec();
                loop {
                    if shutdown.load(Ordering::Acquire) {
                        break 'uplink;
                    }
                    match outbound_tx.try_send(payload) {
                        Ok(()) => break,
                        Err(TrySendError::Full(returned_payload)) => {
                            payload = returned_payload;
                            thread::sleep(OUTBOUND_QUEUE_RETRY_DELAY);
                        }
                        Err(TrySendError::Disconnected(_)) => break 'uplink,
                    }
                }
            }
            Err(ref e) if e.kind() == io::ErrorKind::WouldBlock || e.kind() == io::ErrorKind::TimedOut => {
                continue;
            }
            Err(_) => break,
        }
    }
    shutdown.store(true, Ordering::Release);
}

fn relay_loop<S: Read + Write>(
    mut writer: TcpStream,
    ws: &mut WebSocket<S>,
    outbound_rx: &Receiver<Vec<u8>>,
    shutdown: &AtomicBool,
) -> io::Result<()> {
    loop {
        let channel_disconnected = drain_outbound_frames(ws, outbound_rx, shutdown);
        if channel_disconnected && shutdown.load(Ordering::Acquire) {
            break;
        }

        match ws.read() {
            Ok(Message::Binary(data)) => {
                writer.write_all(&data)?;
            }
            // Ping is handled automatically by tungstenite before returning
            Ok(Message::Ping(_) | Message::Pong(_)) => {}
            Ok(Message::Close(_)) => {
                shutdown.store(true, Ordering::Release);
                break;
            }
            Ok(_) => {} // Text frames etc. -- ignore
            Err(tungstenite::Error::Io(ref e))
                if e.kind() == io::ErrorKind::WouldBlock || e.kind() == io::ErrorKind::TimedOut =>
            {
                continue;
            }
            Err(tungstenite::Error::ConnectionClosed | tungstenite::Error::AlreadyClosed) => {
                shutdown.store(true, Ordering::Release);
                break;
            }
            Err(_) => {
                shutdown.store(true, Ordering::Release);
                break;
            }
        }
    }
    Ok(())
}

fn drain_outbound_frames<S: Read + Write>(
    ws: &mut WebSocket<S>,
    outbound_rx: &Receiver<Vec<u8>>,
    shutdown: &AtomicBool,
) -> bool {
    let mut disconnected = false;
    for _ in 0..MAX_OUTBOUND_BURST {
        match outbound_rx.try_recv() {
            Ok(payload) => {
                if ws.send(Message::Binary(payload.into())).is_err() {
                    shutdown.store(true, Ordering::Release);
                    return true;
                }
            }
            Err(TryRecvError::Empty) => break,
            Err(TryRecvError::Disconnected) => {
                disconnected = true;
                break;
            }
        }
    }
    disconnected
}

fn drive_close_handshake<S: Read + Write>(ws: &mut WebSocket<S>) {
    let _ = ws.close(None);
    let deadline = Instant::now() + CLOSE_HANDSHAKE_TIMEOUT;

    while Instant::now() < deadline {
        match ws.read() {
            Ok(Message::Close(_)) => break,
            Ok(_) => continue,
            Err(tungstenite::Error::Io(ref err))
                if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) =>
            {
                let _ = ws.flush();
                continue;
            }
            Err(tungstenite::Error::ConnectionClosed | tungstenite::Error::AlreadyClosed) => break,
            Err(_) => break,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::net::{Ipv4Addr, Shutdown, TcpListener};

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
        client_ws
            .get_mut()
            .set_read_timeout(Some(crate::connect::WS_READ_TIMEOUT))
            .expect("set client websocket timeout");

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
            assert!(
                Instant::now() < deadline,
                "timed out waiting for {expected_len} bytes, collected {}",
                collected.len()
            );
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
        let init = [0xAB; 64];

        let relay_thread = thread::spawn(move || ws_relay(relay_client, ws, &init));

        assert!(matches!(read_message_retry(&mut peer), Message::Binary(data) if data[..] == init));

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
        let init = [0xCD; 64];
        let expected = vec![0x5E; 16_384 * 4];

        let relay_thread = thread::spawn(move || ws_relay(relay_client, ws, &init));

        assert!(matches!(read_message_retry(&mut peer), Message::Binary(data) if data[..] == init));

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
        let init = [0x11; 64];

        let relay_thread = thread::spawn(move || ws_relay(relay_client, ws, &init));

        assert!(matches!(read_message_retry(&mut peer), Message::Binary(data) if data[..] == init));
        local_app.shutdown(Shutdown::Write).expect("shutdown local app write");

        wait_for_close(&mut peer);
        relay_thread.join().expect("join relay thread").expect("relay result");
    }

    #[test]
    fn ws_relay_exits_cleanly_when_remote_websocket_closes() {
        let (_local_app, relay_client) = tcp_pair();
        let (ws, mut peer) = websocket_pair();
        let init = [0x22; 64];

        let relay_thread = thread::spawn(move || ws_relay(relay_client, ws, &init));

        assert!(matches!(read_message_retry(&mut peer), Message::Binary(data) if data[..] == init));
        peer.close(None).expect("send websocket close");

        relay_thread.join().expect("join relay thread").expect("relay result");
    }

    #[test]
    #[ignore = "manual throughput comparison"]
    fn ws_relay_uplink_throughput_benchmark() {
        let (mut local_app, relay_client) = tcp_pair();
        let (ws, mut peer) = websocket_pair();
        let init = [0x33; 64];
        let payload = vec![0x7A; 8 * 1024 * 1024];
        let payload_len = payload.len();

        let relay_thread = thread::spawn(move || ws_relay(relay_client, ws, &init));

        assert!(matches!(read_message_retry(&mut peer), Message::Binary(data) if data[..] == init));

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
}
