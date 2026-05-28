# ripdpi-config

**Responsibility:** shared runtime configuration model, defaults, parsing helpers, and compatibility constants for the native proxy stack.

This crate owns the Rust-side model types consumed after Kotlin or CLI config translation. It is intentionally default-tolerant: additive fields should deserialize with inert defaults, and string-to-enum parsing must fall back to documented safe behavior instead of panicking.

## Boundaries

- Kotlin remains authoritative for user-facing settings, validation, and JSON serialization.
- This crate does not own JNI or Android persistence.
- Stable identifier strings are documented in `docs/architecture/CONFIG_CONTRACTS.md` and must not be renamed.

Run focused checks with `cargo test -p ripdpi-config`.
