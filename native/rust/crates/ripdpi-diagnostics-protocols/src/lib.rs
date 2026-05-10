//! Narrow protocol aggregation for diagnostics runners.
//!
//! This crate gives orchestrators one dependency for protocol families while
//! keeping imports behind named modules instead of root-level facade exports.

pub mod compat {
    include!("compat.rs");
}
pub mod dns;
pub mod dns_analysis;
pub mod dns_oracle;
pub mod fat_header;
pub mod http;
pub mod tls;
pub mod transport;
pub mod util;
