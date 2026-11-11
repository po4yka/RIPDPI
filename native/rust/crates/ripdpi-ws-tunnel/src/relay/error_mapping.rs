use std::io;

pub(super) enum WsReadErrorAction {
    Retry,
    Stop,
}

pub(super) fn map_ws_read_error(error: tungstenite::Error) -> io::Result<WsReadErrorAction> {
    match error {
        tungstenite::Error::Io(ref err)
            if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) =>
        {
            Ok(WsReadErrorAction::Retry)
        }
        tungstenite::Error::ConnectionClosed | tungstenite::Error::AlreadyClosed => Ok(WsReadErrorAction::Stop),
        other => Err(ws_io_error(other)),
    }
}

pub(super) fn ws_io_error(error: tungstenite::Error) -> io::Error {
    match error {
        tungstenite::Error::Io(error) => error,
        tungstenite::Error::Capacity(_) | tungstenite::Error::Protocol(_) | tungstenite::Error::Utf8(_) => {
            io::Error::new(io::ErrorKind::InvalidData, error)
        }
        other => io::Error::other(other),
    }
}
