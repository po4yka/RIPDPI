#[derive(thiserror::Error, Debug)]
pub enum SocksServerError {
    #[error("i/o error when {context}: {source}")]
    Io { source: io::Error, context: &'static str },
    #[error("string error when {context}: {source}")]
    FromUtf8 { source: FromUtf8Error, context: &'static str },
    #[error(transparent)]
    ConnectError(#[from] ConnectError),
    #[error(transparent)]
    UdpHeaderError(#[from] UdpHeaderError),
    #[error(transparent)]
    AddrError(#[from] AddrError),
    #[error("BUG: {0}")] // should be unreachable
    Bug(&'static str),
    #[error("Auth method unacceptable `{0:?}`.")]
    AuthMethodUnacceptable(Vec<u8>),
    #[error("Unsupported SOCKS version `{0}`.")]
    UnsupportedSocksVersion(u8),
    #[error("Unsupported SOCKS command `{0}`.")]
    UnknownCommand(u8),
    #[error("Unexpected garbage received on TCP stream used for UDP proxy keep-alive: `{0}`")]
    UnexpectedUdpControlGarbage(u8),
    #[error("Empty username received")]
    EmptyUsername,
    #[error("Empty password received")]
    EmptyPassword,
    #[error("Authentication rejected")]
    AuthenticationRejected,
    #[error("End of stream")]
    EOF,
}

impl SocksServerError {
    pub fn to_reply_error(&self) -> ReplyError {
        match self {
            SocksServerError::UnknownCommand(_) => ReplyError::CommandNotSupported,
            SocksServerError::AddrError(err) => err.to_reply_error(),
            _ => ReplyError::GeneralFailure,
        }
    }
}

pub trait ErrorContext<T> {
    fn err_when(self, context: &'static str) -> Result<T, SocksServerError>;
}

impl<T> ErrorContext<T> for Result<T, io::Error> {
    fn err_when(self, context: &'static str) -> Result<T, SocksServerError> {
        self.map_err(|source| SocksServerError::Io { source, context })
    }
}

impl<T> ErrorContext<T> for Result<T, FromUtf8Error> {
    fn err_when(self, context: &'static str) -> Result<T, SocksServerError> {
        self.map_err(|source| SocksServerError::FromUtf8 { source, context })
    }
}
