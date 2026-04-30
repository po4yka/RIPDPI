use std::io;
use std::sync::Arc;

/// Callback invoked for each packet written during desync execution.
/// The bool parameter is `true` for outbound packets.
pub type PcapHook = Arc<dyn Fn(&[u8], bool) + Send + Sync>;

#[derive(Debug)]
pub struct OutboundSendOutcome {
    pub bytes_committed: usize,
    pub strategy_family: Option<&'static str>,
}

#[derive(Debug)]
pub enum OutboundSendError {
    Transport(io::Error),
    StrategyExecution {
        action: &'static str,
        strategy_family: &'static str,
        fallback: Option<&'static str>,
        bytes_committed: usize,
        source_errno: Option<i32>,
        source: io::Error,
    },
}

impl OutboundSendError {
    pub fn into_io_error(self) -> io::Error {
        let kind = self.kind();
        io::Error::new(kind, self)
    }

    pub fn kind(&self) -> io::ErrorKind {
        match self {
            Self::Transport(source) => source.kind(),
            Self::StrategyExecution { source, .. } => source.kind(),
        }
    }

    pub fn source_error(&self) -> &io::Error {
        match self {
            Self::Transport(source) => source,
            Self::StrategyExecution { source, .. } => source,
        }
    }
}

impl std::fmt::Display for OutboundSendError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Transport(source) => source.fmt(formatter),
            Self::StrategyExecution { action, strategy_family, fallback, bytes_committed, source, .. } => {
                write!(
                    formatter,
                    "desync action={action} strategy_family={strategy_family} bytes_committed={bytes_committed}"
                )?;
                if let Some(fallback) = fallback {
                    write!(formatter, " fallback={fallback}")?;
                }
                write!(formatter, ": {source}")
            }
        }
    }
}

impl std::error::Error for OutboundSendError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        Some(self.source_error())
    }
}

impl From<io::Error> for OutboundSendError {
    fn from(value: io::Error) -> Self {
        Self::Transport(value)
    }
}
