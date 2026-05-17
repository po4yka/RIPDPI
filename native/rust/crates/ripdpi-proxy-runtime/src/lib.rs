// Under `--features loom`, the in-crate `mod tests` modules are gated out so
// loom integration tests in `tests/loom_*.rs` get a clean test runner. That
// means many test-only helpers (`#[cfg(test)]` items) become dead in that
// configuration. Quiet the corresponding warnings rather than annotating
// each item individually.
#![cfg_attr(all(test, feature = "loom"), allow(dead_code, unused_imports))]

mod sync;

mod process;
mod runtime;

pub use process::{prepare_embedded, process_settings, ProcessGuard, ProcessSettings};
pub use runtime::{
    create_listener, load_geo_database_versions, load_geoip_metadata, run_proxy, run_proxy_with_embedded_control,
    run_proxy_with_listener, RuntimeGeoDatabaseVersions, RuntimeGeoIpMetadata,
};
