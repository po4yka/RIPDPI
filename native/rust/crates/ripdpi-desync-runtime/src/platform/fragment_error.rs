use std::io;

/// Emission state supplied by a TCP fragment sender.
#[derive(Debug)]
pub struct TcpFragmentSendError {
    pub source: io::Error,
    /// False only when the sender proves that emission did not start.
    pub payload_commit_uncertain: bool,
}

impl std::fmt::Display for TcpFragmentSendError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        std::fmt::Display::fmt(&self.source, formatter)
    }
}

impl std::error::Error for TcpFragmentSendError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        Some(&self.source)
    }
}

impl TcpFragmentSendError {
    pub(crate) fn classify(source: io::Error) -> io::Error {
        if Self::from_error(&source).is_some() {
            return source;
        }
        io::Error::new(source.kind(), Self { source, payload_commit_uncertain: true })
    }

    pub(crate) fn from_error(error: &io::Error) -> Option<&Self> {
        error.get_ref()?.downcast_ref()
    }

    pub(crate) fn before_send(error: &io::Error) -> bool {
        Self::from_error(error).is_some_and(|phase| !phase.payload_commit_uncertain)
    }
}
