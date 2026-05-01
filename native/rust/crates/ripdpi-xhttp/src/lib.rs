#![forbid(unsafe_code)]

mod client;
mod config;
mod connect;
mod finalmask;
mod h2_body;
mod pool;
mod relay;

pub use client::{connect_reality, connect_tls, XhttpClient};
pub use config::{AsyncIo, ConfigError, FinalmaskConfig, XhttpRealityConfig, XhttpTlsConfig, XmuxConfig};
pub use relay::XhttpStream;

#[cfg(test)]
mod tests;
