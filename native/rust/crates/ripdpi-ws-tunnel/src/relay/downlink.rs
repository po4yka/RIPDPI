use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, Ordering};

use tungstenite::WebSocket;
use tungstenite::protocol::Message;

use super::error_mapping::{WsReadErrorAction, map_ws_read_error};
use super::outbound_queue::{OutboundReceiver, drain_outbound_frames};

pub(super) fn relay_loop<S: Read + Write>(
    mut writer: TcpStream,
    ws: &mut WebSocket<S>,
    outbound_rx: &OutboundReceiver,
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
            Err(err) => match map_ws_read_error(&err) {
                WsReadErrorAction::Retry => continue,
                WsReadErrorAction::Stop => {
                    shutdown.store(true, Ordering::Release);
                    break;
                }
            },
        }
    }
    Ok(())
}
