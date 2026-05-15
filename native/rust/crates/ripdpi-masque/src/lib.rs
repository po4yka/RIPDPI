#![forbid(unsafe_code)]

pub mod auth;
pub mod config;
pub mod provider_adapter;

mod client;
mod h2;
mod h3;
mod migration;
mod privacy_pass;
mod request;
mod response;
mod tls;
mod udp;
mod url;
mod validation;

pub use client::{AsyncIo, MasqueClient};
pub use udp::MasqueUdpRelay;

#[cfg(test)]
mod tests;
