mod close_handshake;
mod downlink;
mod error_mapping;
mod outbound_queue;
mod seed_framing;
mod uplink;

#[cfg(test)]
mod tests;

use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use tungstenite::WebSocket;

use self::close_handshake::drive_close_handshake;
use self::downlink::relay_loop;
use self::outbound_queue::bounded_outbound_queue;
use self::seed_framing::send_seed_frames;
use self::uplink::spawn_uplink_thread;

/// Bidirectional relay between a local TCP client and a WebSocket tunnel.
///
/// Sends the first 64 bytes of `seed_request` as the MTProto init frame, then
/// forwards any additional bytes already consumed from the client before
/// relaying the rest of the bidirectional stream.
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
    seed_request: &[u8],
) -> io::Result<()> {
    send_seed_frames(&mut ws, seed_request)?;

    let shutdown = Arc::new(AtomicBool::new(false));
    let (outbound_tx, outbound_rx) = bounded_outbound_queue();

    let client_reader = client.try_clone()?;
    let client_writer = client;
    let uplink = spawn_uplink_thread(client_reader, outbound_tx, shutdown.clone())?;

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
