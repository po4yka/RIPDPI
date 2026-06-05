use std::io;

use thiserror::Error;

/// Errors surfaced by the Mieru outbound engine (TCP carrier).
#[derive(Debug, Error)]
pub enum MieruError {
    /// The server port is outside the valid `1..=65535` range.
    #[error("Mieru server port must be in 1..=65535")]
    InvalidPort,
    /// The username is empty. Mieru authenticates with a username/password
    /// pair, so a blank username is rejected before any I/O.
    #[error("Mieru username must not be empty")]
    EmptyUsername,
    /// The password is empty. Mieru authenticates with a username/password
    /// pair, so a blank password is rejected before any I/O.
    #[error("Mieru password must not be empty")]
    EmptyPassword,
    /// The MTU is outside the supported `1280..=1500` range.
    #[error("Mieru MTU must be in 1280..=1500")]
    InvalidMtu,
    /// An unrecognised transport-protocol token was supplied. The inner string
    /// is the rejected token.
    #[error("unsupported Mieru protocol `{0}`; only tcp and udp are supported")]
    UnsupportedProtocol(String),
    /// An unrecognised multiplexing-level token was supplied. The inner string
    /// is the rejected token.
    #[error("unsupported Mieru multiplexing `{0}`; only off, low, middle and high are supported")]
    UnsupportedMultiplexing(String),
    /// The UDP carrier (KCP-like reliable ARQ) is not implemented. The TCP
    /// carrier is the supported, recommended transport.
    #[error("Mieru UDP carrier is not implemented; use protocol=tcp")]
    UdpUnsupported,
    /// An AEAD / key-derivation / RNG operation failed. The inner string is a
    /// static stage label and never contains key material.
    #[error("Mieru cipher error: {0}")]
    Crypto(&'static str),
    /// A received segment violated the wire protocol (bad marker, length, or
    /// metadata). The inner string describes the violation, never payload bytes.
    #[error("Mieru protocol error: {0}")]
    Protocol(String),
    /// The in-tunnel SOCKS5 negotiation to the relay target failed.
    #[error("Mieru in-tunnel SOCKS5 error: {0}")]
    Socks5(String),
    /// Underlying I/O failure.
    #[error("I/O error: {0}")]
    Io(#[from] io::Error),
}

pub type Result<T> = std::result::Result<T, MieruError>;
