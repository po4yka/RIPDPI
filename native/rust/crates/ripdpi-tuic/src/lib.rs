#![forbid(unsafe_code)]

mod client;
mod config;
mod endpoint;
mod migration;
mod protocol;
mod tcp;
#[cfg(test)]
mod tests;
mod udp;

pub use client::TuicClient;
pub use config::Config;
pub use protocol::{ProtocolVersion, TuicFailureKind, classify_failure_payload};
pub use tcp::DuplexStream;
pub use udp::UdpSession;
