# ripdpi-desync

**Layer:** L3 -- domain logic.

`ripdpi-desync` owns DPI desynchronization planning primitives: chain-step modeling, offset expressions, packet markers, and strategy planning inputs used by proxy and tunnel runtimes.

## Dependencies

- **Upstream:** `ripdpi-config`, `ripdpi-ipfrag`, `ripdpi-packets`, `ripdpi-strategy-trait`, `ripdpi-tls-profiles`.
- **Downstream:** desync runtime, proxy runtime adapters, diagnostics candidates, and strategy crates.

## Boundaries

- Strategy planning and protocol-aware desync model types belong here.
- Socket I/O, privileged packet emission, Android/JNI, and runtime orchestration belong in downstream runtime or platform crates.

## Checks

Run focused checks with `cargo test -p ripdpi-desync`.
