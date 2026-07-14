---
name: rust-crate-architecture
description: Rust crate creation, workspace restructuring, dependency layering, and direction rules.
---

# Rust Crate Architecture

The `native/rust/` workspace is large and changes frequently. Treat `native/rust/Cargo.toml`, `cargo metadata --locked`, and `docs/architecture/NATIVE_RUST.md` as the current crate inventory. Dependencies should still flow toward smaller/shared crates and away from Android/JNI adapter crates.

## Current Architecture Map

- `docs/architecture/NATIVE_RUST.md` is the maintained crate taxonomy and native artifact map.
- `cargo metadata --locked --manifest-path native/rust/Cargo.toml --no-deps` is the authoritative machine-readable workspace inventory.
- Android JNI crates (`ripdpi-android`, `ripdpi-tunnel-android`, `ripdpi-warp-android`, `ripdpi-relay-android`) should stay as adapters over domain/runtime crates.
- Diagnostics execution is split across `ripdpi-monitor-engine`, `ripdpi-diagnostics-runner`, `ripdpi-diagnostics-contracts`, and per-protocol `ripdpi-diagnostics-*` crates.
- Proxy runtime work belongs under `ripdpi-proxy-runtime` and its adapter/service crates, not under removed legacy crate names.

### Test Support (dev-dependencies only)

| Crate | Purpose |
|-------|---------|
| `golden-test-support` | Golden file test infrastructure |
| `local-network-fixture` | Local DNS/HTTP/TCP server fixtures |
| `native-soak-support` | Soak/stress test harness |
| `android-support` | Android logging + JNI helpers (also runtime dep for android crates) |

## Dependency Rules

### 1. No upward dependencies

Layer N crates must not depend on Layer N+1 crates. Cargo enforces no cycles, but upward deps within the same cycle-free graph are still architecturally wrong.

**Example violations**:
- `ripdpi-config` depending on `ripdpi-proxy-runtime`
- `ripdpi-packets` depending on `ripdpi-desync` (Layer 0 -> Layer 1)

### 2. Two separate stacks

The workspace has two independent dependency trees:

```
Proxy stack:  ripdpi-packets -> ripdpi-config -> ripdpi-desync -> ripdpi-proxy-runtime -> ripdpi-android
Tunnel stack: ripdpi-tun-driver -> ripdpi-tunnel-config -> ripdpi-tunnel-core -> ripdpi-tunnel-android
```

These stacks share only foundation crates such as `ripdpi-packets` and `ripdpi-failure-classifier`. Do not introduce cross-stack dependencies (e.g., `ripdpi-tunnel-core` must not depend on `ripdpi-proxy-runtime`).

### 3. Test support crates are dev-deps only

`golden-test-support`, `local-network-fixture`, and `native-soak-support` must appear under `[dev-dependencies]` only. Exception: `android-support` is also a runtime dependency for `ripdpi-android` and `ripdpi-tunnel-android`.

### 4. Diagnostics-runtime relationship

`ripdpi-monitor-engine` may use diagnostics runner/protocol crates and shared runtime contracts, but proxy/tunnel runtime crates must not depend back on `ripdpi-monitor-engine`. Keep diagnostics observation as an adapter edge, not a core runtime dependency.

### 5. Platform isolation

Platform-specific code lives behind `#[cfg(target_os = "...")]` gates, never creating conditional dependencies that break the layer model.

## Creating a New Crate

### Checklist

1. **Choose layer**: determine which layers the new crate needs to depend on. Place it at the minimum layer that satisfies its dependencies.

2. **Create directory**:
   ```bash
   cd native/rust
   cargo init --lib crates/<crate-name>
   ```

3. **Add to workspace** in `native/rust/Cargo.toml`:
   ```toml
   # [workspace] members list
   members = [
       # ... existing members
       "crates/<crate-name>",
   ]

   # [workspace.dependencies] section
   <crate-name> = { path = "crates/<crate-name>" }
   ```

4. **Configure crate Cargo.toml**:
   ```toml
   [package]
   name = "<crate-name>"
   edition.workspace = true
   version.workspace = true
   license.workspace = true

   [dependencies]
   # Use workspace = true for all deps
   ripdpi-packets = { workspace = true }

   [lints]
   workspace = true
   ```

5. **Add safety attribute** to `lib.rs`:
   ```rust
   #![forbid(unsafe_code)]
   ```
   Omit only if the crate genuinely needs unsafe (JNI crates, tun-driver).

6. **Verify**:
   ```bash
   cargo clippy --locked -p <crate-name> --all-targets -- -D warnings
   cargo deny --locked check
   ```

### Naming conventions

- Prefix with `ripdpi-` for proxy/network crates
- Prefix with `ripdpi-tunnel-` for tunnel-stack crates
- No prefix for support/utility crates (`golden-test-support`, `local-network-fixture`)

## Module Structure Within a Crate

### Small crate (< 500 lines total)

Single `lib.rs` with inline `#[cfg(test)] mod tests`.

### Medium crate (500-2000 lines)

```
src/
  lib.rs           # mod declarations + pub use re-exports
  types.rs         # data structures
  logic.rs         # core algorithms
```

Inline tests or a single `tests.rs` file.

### Large crate (2000+ lines)

```
src/
  lib.rs           # mod declarations + pub use re-exports
  engine.rs        # module root (declares sub-modules)
  engine/
    plan.rs
    report.rs
    runners.rs
    tests.rs       # focused test module
  types.rs         # module root
  types/
    request.rs
    response.rs
  test_fixtures.rs # shared test helpers (#[cfg(test)])
  tests.rs         # top-level unit tests
tests/
  integration.rs   # integration tests
  golden/          # golden test fixtures
```

Use `name.rs` + `name/` directory pattern. Use `mod.rs` only for 3+ level deep nesting.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Adding upward dependency (e.g., Layer 1 -> Layer 2) | Restructure: extract shared types into a lower-layer crate |
| Cross-stack dependency (proxy crate -> tunnel crate) | Keep stacks independent; shared types go to Layer 0 |
| Test support crate in `[dependencies]` | Move to `[dev-dependencies]` |
| Missing `[lints] workspace = true` in new crate | Always inherit workspace lints |
| Missing `#![forbid(unsafe_code)]` on safe crate | Add to lib.rs unless unsafe is genuinely needed |
| Putting the new crate in wrong workspace member list position | Alphabetical order within the members list |
| Forgetting to add to `[workspace.dependencies]` | Required for `workspace = true` syntax in dependents |
| Using `version = "0.1.0"` instead of `version.workspace = true` | All crates share the workspace version |
