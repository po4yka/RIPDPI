#![allow(dead_code)]

mod cache;
mod catalog;
mod production;
mod source;
#[cfg(test)]
mod tests;
mod validation;

pub use cache::{CachedEchSnapshot, CdnEchUpdater};
pub use catalog::{CdnEchConfig, opportunistic_ech_config_for_ip, opportunistic_ech_provider_for_ip};
pub use production::production_updater;
pub use source::{BundledEchConfigSource, EchConfigSource, EchSourceError, RemoteEchConfigSource};
pub use validation::validate_ech_config_list_bytes;

#[cfg(test)]
pub(crate) use cache::{now_unix_ms, synthesize_instant_for_unix_ms};
#[cfg(test)]
pub(crate) use catalog::CLOUDFLARE_ECH_CONFIG_LIST;
