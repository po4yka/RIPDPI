// Under `--features loom`, the in-crate `mod tests` modules are gated out so
// loom integration tests in `tests/loom_*.rs` get a clean test runner. That
// means many test-only helpers (`#[cfg(test)]` items) become dead in that
// configuration. Quiet the corresponding warnings rather than annotating
// each item individually.
#![cfg_attr(all(test, feature = "loom"), allow(dead_code, unused_imports))]
#![deny(unsafe_op_in_unsafe_fn)]
#![warn(clippy::undocumented_unsafe_blocks, clippy::multiple_unsafe_ops_per_block, clippy::missing_safety_doc)]

mod sync;

mod process;
mod runtime;
mod same_sni_cap;

pub use process::{ProcessGuard, ProcessSettings, prepare_embedded, process_settings};
pub use runtime::{
    ProxyRuntimeCleanupReceipt, RuntimeGeoDatabaseVersions, RuntimeGeoIpMetadata, create_listener,
    load_geo_database_versions, load_geoip_metadata, run_proxy, run_proxy_with_embedded_control,
    run_proxy_with_embedded_control_receipt, run_proxy_with_listener, validate_proxy_config,
};
pub use same_sni_cap::{SameSniProfileCaps, SameSniProfileGuard, SameSniProfileLimiter};
