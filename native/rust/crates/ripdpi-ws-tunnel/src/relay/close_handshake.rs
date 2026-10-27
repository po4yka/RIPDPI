use std::io::{self, Read, Write};
use std::time::{Duration, Instant};

use tungstenite::WebSocket;
use tungstenite::protocol::Message;

const CLOSE_HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);

pub(super) fn drive_close_handshake<S: Read + Write>(ws: &mut WebSocket<S>) {
    // An I/O error here is a write that timed out: the path is gone, so skip the handshake.
    if let Err(tungstenite::Error::Io(_)) = ws.close(None) {
        return;
    }
    let deadline = Instant::now() + CLOSE_HANDSHAKE_TIMEOUT;

    while Instant::now() < deadline {
        match ws.read() {
            Ok(Message::Close(_)) => break,
            Ok(_) => continue,
            Err(tungstenite::Error::Io(ref err))
                if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) =>
            {
                if ws.flush().is_err() {
                    break;
                }
                continue;
            }
            Err(tungstenite::Error::ConnectionClosed | tungstenite::Error::AlreadyClosed) => break,
            Err(_) => break,
        }
    }
}
