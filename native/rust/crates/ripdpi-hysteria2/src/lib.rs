#![forbid(unsafe_code)]

mod client;
mod config;
mod error;
mod migration;
mod salamander;
mod tcp;
mod tls_quic;
mod udp;
mod varint;

pub use client::{connect, HysteriaClient};
pub use config::Config;
pub use error::{HysteriaError, Result};
pub use tcp::DuplexStream;
pub use udp::UdpSession;
