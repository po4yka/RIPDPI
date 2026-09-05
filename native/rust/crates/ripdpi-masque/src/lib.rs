#![forbid(unsafe_code)]

pub mod auth;
pub mod capsule;
mod client;
pub mod config;
mod ech;
mod h2;
mod h3;
pub mod migration;
mod privacy_pass;
pub mod provider_adapter;
mod request;
mod response;
mod tls;
mod udp;
mod url;
mod validation;

pub use client::{AsyncIo, MasqueClient};
pub use udp::{MasqueQuicPathSnapshot, MasqueUdpRelay};

#[cfg(test)]
mod tests;
