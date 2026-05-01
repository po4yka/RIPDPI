use std::io;

pub(super) enum WsReadErrorAction {
    Retry,
    Stop,
}

pub(super) fn map_ws_read_error(error: &tungstenite::Error) -> WsReadErrorAction {
    match error {
        tungstenite::Error::Io(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
            WsReadErrorAction::Retry
        }
        tungstenite::Error::ConnectionClosed | tungstenite::Error::AlreadyClosed => WsReadErrorAction::Stop,
        _ => WsReadErrorAction::Stop,
    }
}
