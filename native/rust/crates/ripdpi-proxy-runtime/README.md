# ripdpi-proxy-runtime

**Responsibility:** long-running native proxy runtime entry points for local SOCKS/HTTP proxy operation.

This crate owns listener creation, embedded-control startup, process preparation, geo database metadata loading, and the public runtime functions used by `ripdpi-android` and the desktop CLI. It composes lower-level config, desync, runtime-service, telemetry, routing, DNS, and session crates rather than owning Android service lifecycle directly.

## Entry Points

- `prepare_embedded`, `process_settings`, and `ProcessGuard` for process/runtime preparation.
- `create_listener` for bound listener construction.
- `run_proxy`, `run_proxy_with_listener`, and `run_proxy_with_embedded_control` for runtime execution.
- `load_geo_database_versions` and `load_geoip_metadata` for native geo database reporting.

Run focused runtime checks with `cargo test -p ripdpi-proxy-runtime`. Loom-specific tests use the crate's `loom` feature and keep in-crate test helpers gated accordingly.
