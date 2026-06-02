use std::net::TcpStream;
use std::time::Duration;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct StreamSocketSettings {
    pub(crate) nodelay: Option<bool>,
    pub(crate) read_timeout: Option<Option<Duration>>,
    pub(crate) write_timeout: Option<Option<Duration>>,
}

pub(crate) fn capture_stream_socket_settings(stream: &TcpStream) -> StreamSocketSettings {
    StreamSocketSettings {
        nodelay: stream.nodelay().ok(),
        read_timeout: stream.read_timeout().ok(),
        write_timeout: stream.write_timeout().ok(),
    }
}

pub(crate) fn apply_stream_socket_settings(stream: &TcpStream, settings: StreamSocketSettings) {
    if let Some(nodelay) = settings.nodelay
        && let Err(error) = stream.set_nodelay(nodelay)
    {
        tracing::debug!("failed to restore TCP_NODELAY after ipfrag2 socket handoff: {error}");
    }
    if let Some(timeout) = settings.read_timeout
        && let Err(error) = stream.set_read_timeout(timeout)
    {
        tracing::debug!("failed to restore read timeout after ipfrag2 socket handoff: {error}");
    }
    if let Some(timeout) = settings.write_timeout
        && let Err(error) = stream.set_write_timeout(timeout)
    {
        tracing::debug!("failed to restore write timeout after ipfrag2 socket handoff: {error}");
    }
}
