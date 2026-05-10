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

pub fn tls_profile_catalog_version() -> &'static str {
    ripdpi_tls_profiles::profile_catalog_version()
}

#[cfg(test)]
mod tests;
