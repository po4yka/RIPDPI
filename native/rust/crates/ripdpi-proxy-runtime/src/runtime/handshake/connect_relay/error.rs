use std::io;

pub(in crate::runtime::handshake) struct ConnectRelayError {
    error: io::Error,
    success_reply_sent: bool,
    seed_request: Option<Vec<u8>>,
}

impl ConnectRelayError {
    pub(in crate::runtime::handshake::connect_relay) fn new(error: io::Error, success_reply_sent: bool) -> Self {
        Self { error, success_reply_sent, seed_request: None }
    }

    pub(in crate::runtime::handshake::connect_relay) fn with_seed_request(
        error: io::Error,
        success_reply_sent: bool,
        seed_request: Option<Vec<u8>>,
    ) -> Self {
        Self { error, success_reply_sent, seed_request }
    }

    pub(in crate::runtime::handshake) fn kind(&self) -> io::ErrorKind {
        self.error.kind()
    }

    pub(in crate::runtime::handshake) fn success_reply_sent(&self) -> bool {
        self.success_reply_sent
    }

    pub(in crate::runtime::handshake::connect_relay) fn mark_success_reply_sent(&mut self) {
        self.success_reply_sent = true;
    }

    pub(in crate::runtime::handshake::connect_relay) fn seed_request(&self) -> Option<&[u8]> {
        self.seed_request.as_deref()
    }

    pub(in crate::runtime::handshake) fn into_io_error(self) -> io::Error {
        self.error
    }
}

impl std::fmt::Display for ConnectRelayError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.error.fmt(formatter)
    }
}

impl std::fmt::Debug for ConnectRelayError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("ConnectRelayError")
            .field("error", &self.error)
            .field("success_reply_sent", &self.success_reply_sent)
            .field("seed_request_len", &self.seed_request.as_ref().map(Vec::len))
            .finish()
    }
}

impl std::error::Error for ConnectRelayError {}

impl From<io::Error> for ConnectRelayError {
    fn from(error: io::Error) -> Self {
        Self::new(error, false)
    }
}
