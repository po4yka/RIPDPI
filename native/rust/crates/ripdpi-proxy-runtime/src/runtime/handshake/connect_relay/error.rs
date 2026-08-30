use std::io;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(in crate::runtime::handshake) enum ConnectPolicyRejection {
    OwnedStackRequired,
}

pub(in crate::runtime::handshake) struct ConnectRelayError {
    error: io::Error,
    success_reply_sent: bool,
    seed_request: Option<Vec<u8>>,
    policy_rejection: Option<ConnectPolicyRejection>,
}

impl ConnectRelayError {
    pub(in crate::runtime::handshake::connect_relay) fn new(error: io::Error, success_reply_sent: bool) -> Self {
        Self { error, success_reply_sent, seed_request: None, policy_rejection: None }
    }

    pub(in crate::runtime::handshake::connect_relay) fn with_seed_request(
        error: io::Error,
        success_reply_sent: bool,
        seed_request: Option<Vec<u8>>,
    ) -> Self {
        Self { error, success_reply_sent, seed_request, policy_rejection: None }
    }

    pub(in crate::runtime::handshake) fn owned_stack_required() -> Self {
        Self {
            error: io::Error::new(io::ErrorKind::PermissionDenied, "OWNED_STACK_REQUIRED"),
            success_reply_sent: false,
            seed_request: None,
            policy_rejection: Some(ConnectPolicyRejection::OwnedStackRequired),
        }
    }

    pub(in crate::runtime::handshake) fn kind(&self) -> io::ErrorKind {
        self.error.kind()
    }

    pub(in crate::runtime::handshake) fn success_reply_sent(&self) -> bool {
        self.success_reply_sent
    }

    pub(in crate::runtime::handshake) fn policy_rejection(&self) -> Option<ConnectPolicyRejection> {
        self.policy_rejection
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
            .field("policy_rejection", &self.policy_rejection)
            .finish()
    }
}

impl std::error::Error for ConnectRelayError {}

impl From<io::Error> for ConnectRelayError {
    fn from(error: io::Error) -> Self {
        Self::new(error, false)
    }
}
