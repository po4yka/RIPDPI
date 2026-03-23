# RISC-V (riscv64) Android Support Status

**Decision (2026-03-23): Deferred.**

## Current state

- Rust target `riscv64gc-linux-android`: Tier 3 (no CI, no guaranteed builds).
- Android NDK: provisional sysroot present, ABI not finalized.
- Consumer devices: none shipping as of March 2026.

## When to revisit

Add `riscv64` support when **both** conditions are met:

1. Rust promotes `riscv64gc-linux-android` to Tier 2 (pre-built std, CI-tested).
2. Android NDK finalizes the riscv64 ABI and documents it on the [ABIs page](https://developer.android.com/ndk/guides/abis).

## Changes required (estimated <1 hour)

| File | Change |
|------|--------|
| `gradle.properties` | Add `riscv64` to `ripdpi.nativeAbis` |
| `native/rust/rust-toolchain.toml` | Add `riscv64gc-linux-android` to targets |
| `native/rust/.cargo/config.toml` | Add `[target.riscv64gc-linux-android]` rustflags |
| `build-logic/.../ripdpi.android.rust-native.gradle.kts` | Add ABI mapping in `abiToRustTarget` / `abiToClangTarget` |
| `scripts/ci/native-size-baseline.json` | Add size entries for `riscv64` |

No Rust source changes expected -- the codebase has zero `cfg(target_arch)` guards or inline assembly.
