use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use tungstenite::protocol::Message;
use tungstenite::WebSocket;

/// Bidirectional relay between a local TCP client and a WebSocket tunnel.
///
/// Sends `init_packet` as the first binary WS frame, then relays data in both
/// directions until either side closes or an error occurs.
///
/// Uses two threads:
/// - Main thread: reads WS frames, writes to client TCP
/// - Spawned thread: reads from client TCP, sends as WS binary frames
///
/// `tungstenite::WebSocket::read()` automatically replies to Ping frames with
/// Pong, so no explicit Ping/Pong handling is needed.
pub fn ws_relay<S: Read + Write + Send + 'static>(
    client: TcpStream,
    ws: WebSocket<S>,
    init_packet: &[u8; 64],
) -> io::Result<()> {
    let ws = Arc::new(Mutex::new(ws));
    let shutdown = Arc::new(AtomicBool::new(false));

    // Send the 64-byte obfuscated2 init as the first WS frame.
    {
        let mut ws_guard = ws.lock().map_err(|_| io::Error::other("ws mutex poisoned"))?;
        ws_guard
            .send(Message::Binary(init_packet.to_vec()))
            .map_err(|e| io::Error::new(io::ErrorKind::BrokenPipe, format!("WS send init: {e}")))?;
    }

    let client_reader = client.try_clone()?;
    let client_writer = client;

    // Spawn uplink thread: client TCP -> WS binary frames
    let ws_up = ws.clone();
    let shutdown_up = shutdown.clone();
    let uplink = thread::Builder::new()
        .name("ripdpi-ws-up".into())
        .spawn(move || uplink_loop(client_reader, &ws_up, &shutdown_up))
        .map_err(|e| io::Error::other(format!("spawn ws-up: {e}")))?;

    // Main thread runs downlink: WS -> client TCP
    let result = downlink_loop(client_writer, &ws, &shutdown);

    // Signal uplink to stop and wait for it
    shutdown.store(true, Ordering::Release);
    let _ = uplink.join();

    // Try to close the WebSocket cleanly
    if let Ok(mut ws_guard) = ws.lock() {
        let _ = ws_guard.close(None);
        // Drain any remaining frames to complete the close handshake
        loop {
            match ws_guard.read() {
                Ok(Message::Close(_)) | Err(_) => break,
                _ => {}
            }
        }
    }

    result
}

fn uplink_loop<S: Read + Write>(
    mut reader: TcpStream,
    ws: &Mutex<WebSocket<S>>,
    shutdown: &AtomicBool,
) {
    // Short read timeout so we can check the shutdown flag
    let _ = reader.set_read_timeout(Some(Duration::from_millis(250)));
    let mut buf = [0u8; 16_384];

    loop {
        if shutdown.load(Ordering::Acquire) {
            break;
        }
        match reader.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                let msg = Message::Binary(buf[..n].to_vec());
                let Ok(mut ws_guard) = ws.lock() else { break };
                if ws_guard.send(msg).is_err() {
                    break;
                }
            }
            Err(ref e)
                if e.kind() == io::ErrorKind::WouldBlock
                    || e.kind() == io::ErrorKind::TimedOut =>
            {
                continue;
            }
            Err(_) => break,
        }
    }
    shutdown.store(true, Ordering::Release);
}

fn downlink_loop<S: Read + Write>(
    mut writer: TcpStream,
    ws: &Mutex<WebSocket<S>>,
    shutdown: &AtomicBool,
) -> io::Result<()> {
    loop {
        if shutdown.load(Ordering::Acquire) {
            break;
        }
        let msg = {
            let Ok(mut ws_guard) = ws.lock() else {
                return Err(io::Error::other("ws mutex poisoned"));
            };
            ws_guard.read()
        };
        match msg {
            Ok(Message::Binary(data)) => {
                writer.write_all(&data)?;
            }
            // Ping is handled automatically by tungstenite before returning
            Ok(Message::Ping(_) | Message::Pong(_)) => {}
            Ok(Message::Close(_)) => break,
            Ok(_) => {} // Text frames etc. -- ignore
            Err(tungstenite::Error::Io(ref e))
                if e.kind() == io::ErrorKind::WouldBlock
                    || e.kind() == io::ErrorKind::TimedOut =>
            {
                continue;
            }
            Err(_) => break,
        }
    }
    Ok(())
}
