// Crate-local hardening matching the unsafe-bearing crate convention
// (`ripdpi-vless`, `ripdpi-io-uring`, `ripdpi-privileged-ops`,
// `ripdpi-proxy-runtime`). The only unsafe in this crate is the JNI
// readiness callback (`readiness.rs`): the manual `Send`/`Sync` impls on
// `JniReadinessCallback` and the `JavaVM::from_raw` clone. Re-enabling these
// lints crate-locally turns the SAFETY-comment requirement into a build-time
// error so any future `unsafe` block lands with a per-operation justification.
#![warn(clippy::undocumented_unsafe_blocks)]
#![warn(clippy::multiple_unsafe_ops_per_block)]

mod config;
mod entry;
mod entry_error;
mod geo_versions;
mod lifecycle;
mod lifecycle_create;
mod lifecycle_start;
mod quality_sink;
mod readiness;
mod registry;
mod telemetry;

#[cfg(all(test, feature = "loom"))]
mod loom_tests;
#[cfg(all(test, not(feature = "loom")))]
mod tests;

pub use entry::{
    proxy_create_entry, proxy_destroy_entry, proxy_geoip_metadata_entry, proxy_poll_telemetry_entry, proxy_start_entry,
    proxy_stop_entry, proxy_update_network_snapshot_entry,
};
pub use readiness::{proxy_register_readiness_listener_entry, proxy_unregister_readiness_listener_entry};
