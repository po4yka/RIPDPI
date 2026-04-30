use std::io;

#[derive(Debug)]
pub(in crate::runtime::routing) struct ConnectAttemptError {
    pub(in crate::runtime::routing) source: io::Error,
    pub(in crate::runtime::routing) tcp_total_retransmissions: Option<u32>,
    pub(in crate::runtime::routing) tcp_fast_open_enabled: bool,
}

impl ConnectAttemptError {
    pub(in crate::runtime::routing) fn into_io_error(self) -> io::Error {
        self.source
    }
}
