use std::io;

use thiserror::Error;

#[derive(Debug, Error)]
pub enum HysteriaError {
    #[error("invalid address: {0}")]
    InvalidAddress(String),
    #[error("QUIC connect error: {0}")]
    QuicConnect(#[from] quinn::ConnectError),
    #[error("QUIC connection error: {0}")]
    QuicConnection(#[from] quinn::ConnectionError),
    #[error("QUIC write error: {0}")]
    QuicWrite(#[from] quinn::WriteError),
    #[error("QUIC datagram send error: {0}")]
    QuicDatagram(#[from] quinn::SendDatagramError),
    #[error("QUIC stream closed")]
    QuicClosed(#[from] quinn::ClosedStream),
    #[error("I/O error: {0}")]
    Io(#[from] io::Error),
    #[error("HTTP/3 connection error: {0}")]
    H3Connection(#[from] h3::error::ConnectionError),
    #[error("HTTP/3 stream error: {0}")]
    H3Stream(#[from] h3::error::StreamError),
    #[error("URL parse error: {0}")]
    UrlParse(#[from] url::ParseError),
    #[error("authentication failed")]
    AuthFailed,
    #[error("UDP relay is not available on this server")]
    UdpNotSupported,
    #[error("TCP connect failed: {0}")]
    TcpConnect(String),
    #[error("invalid UDP datagram: {0}")]
    InvalidDatagram(String),
}

pub type Result<T> = std::result::Result<T, HysteriaError>;
