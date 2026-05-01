//! Opportunistic ECH (Encrypted Client Hello) configs for known CDN providers.
//!
//! When DNS HTTPS record resolution fails (e.g. because encrypted DNS is
//! blocked), we fall back to hardcoded ECH configs for CDN providers that are
//! known to support ECH. The configs are best-effort: if the server rejects
//! them (key rotation, etc.) the caller falls back to normal TLS.
//!
//! # Maintenance
//!
//! Cloudflare rotates ECH keys periodically. The bundled fallback config was
//! captured from `dig +short type65 cloudflare.com` and should be refreshed
//! when the VPN app catalog is updated (see `VpnAppCatalogUpdater`).
//!
//! To obtain a fresh config:
//! ```text
//! dig +short type65 cloudflare.com | grep -oP 'ech=\K[^ ]+'
//! ```
//! Then base64-decode the value and replace the bundled Cloudflare
//! ECHConfigList in `catalog.rs`.
//!
//! # Refresh abstraction
//!
//! [`EchConfigSource`] / [`CdnEchUpdater`] provide a TTL-gated cache with
//! primary -> fallback semantics. [`BundledEchConfigSource`] is the
//! always-available fallback; [`RemoteEchConfigSource`] performs an HTTPS-RR
//! (type 65) DoH query and validates the returned bytes via
//! [`validate_ech_config_list_bytes`] before handing them to the cache.

// Some helpers are still only exercised by tests (the cache's last-resort
// branch, static catalogue matching) -- keep the lint quiet without inviting
// premature pruning.
#![allow(dead_code)]

mod cache;
mod catalog;
mod production;
mod range_match;
mod source;
#[cfg(test)]
mod tests;
mod validation;

pub use cache::{CachedEchSnapshot, CdnEchUpdater};
pub use catalog::{opportunistic_ech_config_for_ip, opportunistic_ech_provider_for_ip, CdnEchConfig};
pub use production::production_updater;
pub use source::{BundledEchConfigSource, EchConfigSource, EchSourceError, RemoteEchConfigSource};
pub use validation::validate_ech_config_list_bytes;

#[cfg(test)]
pub(crate) use cache::{now_unix_ms, synthesize_instant_for_unix_ms};
#[cfg(test)]
pub(crate) use catalog::CLOUDFLARE_ECH_CONFIG_LIST;
#[cfg(test)]
pub(crate) use range_match::{Ipv4Cidr, Ipv6Cidr};
