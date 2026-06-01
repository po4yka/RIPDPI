use std::io::{self, Read, Write};
use std::time::{Duration, Instant};

use tungstenite::WebSocket;
use tungstenite::protocol::Message;

const CLOSE_HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);

pub(super) fn drive_close_handshake<S: Read + Write>(ws: &mut WebSocket<S>) {
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
