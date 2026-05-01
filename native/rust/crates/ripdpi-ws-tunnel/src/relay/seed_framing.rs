use std::io::{self, Read, Write};

use tungstenite::protocol::Message;
use tungstenite::WebSocket;

const MTPROTO_INIT_PACKET_LEN: usize = 64;

pub(super) fn send_seed_frames<S: Read + Write>(ws: &mut WebSocket<S>, seed_request: &[u8]) -> io::Result<()> {
    if seed_request.len() < MTPROTO_INIT_PACKET_LEN {
        return Err(io::Error::new(
            io::ErrorKind::UnexpectedEof,
            format!("WS relay requires 64-byte init packet, got {}", seed_request.len()),
        ));
    }

    ws.send(Message::Binary(seed_request[..MTPROTO_INIT_PACKET_LEN].to_vec().into()))
        .map_err(|e| io::Error::new(io::ErrorKind::BrokenPipe, format!("WS send init: {e}")))?;

    if seed_request.len() > MTPROTO_INIT_PACKET_LEN {
        ws.send(Message::Binary(seed_request[MTPROTO_INIT_PACKET_LEN..].to_vec().into()))
            .map_err(|e| io::Error::new(io::ErrorKind::BrokenPipe, format!("WS send initial remainder: {e}")))?;
    }

    Ok(())
}
