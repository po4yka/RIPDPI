# RIPDPI Workspace Patterns Reference

## Workspace dependency management

All dependency versions are centralized in the root `native/rust/Cargo.toml`:

```toml
[workspace.dependencies]
# Internal crates use path deps
ripdpi-packets = { path = "crates/ripdpi-packets" }

# External crates pinned to compatible ranges
jni = "0.22"
tokio = { version = "1", default-features = false }
serde = { version = "1", features = ["derive"] }
```

Members inherit with `workspace = true` and can add features:

```toml
[dependencies]
serde.workspace = true
tokio = { workspace = true, features = ["rt", "net"] }
```

## Workspace-level lints (Cargo.toml)

Clippy lints are configured at workspace level and inherited by all members:

```toml
[workspace.lints.clippy]
correctness = { level = "deny", priority = -1 }
suspicious = { level = "deny", priority = -1 }
style = { level = "warn", priority = -1 }
# JNI/FFI allowances
missing_safety_doc = "allow"
not_unsafe_ptr_arg_deref = "allow"
```

Members opt in with:
```toml
[lints]
workspace = true
```

## Gradle property reference (native build)

| Property | Purpose | Example |
|----------|---------|---------|
| `ripdpi.nativeAbis` | ABIs for CI/release | `arm64-v8a,armeabi-v7a,x86_64,x86` |
| `ripdpi.localNativeAbisDefault` | ABIs for local dev | `arm64-v8a` |
| `ripdpi.localNativeAbis` | Per-invocation ABI override | `arm64-v8a` |
| `ripdpi.nativeCargoProfile` | Default cargo profile | `android-jni` |
| `ripdpi.localNativeCargoProfileDefault` | Local dev profile | `android-jni-dev` |
| `ripdpi.nativeNdkVersion` | NDK version string | `27.2.12479018` |
| `ripdpi.minSdk` | Android minSdk (passed to clang target) | `26` |

## Artifact mapping

The Gradle task maps Cargo output names to Android library names:

| Cargo package | Cargo output | Android .so |
|---------------|-------------|-------------|
| ripdpi-android | libripdpi_android.so | libripdpi.so |
| ripdpi-tunnel-android | libripdpi_tunnel_android.so | libripdpi-tunnel.so |

## Selective build commands

```bash
cd native/rust

# Build specific workspace member
cargo build -p ripdpi-packets
cargo check -p ripdpi-ws-tunnel

# Test specific member
cargo nextest run -p ripdpi-session

# Test all members
cargo nextest run --workspace

# Exclude member from workspace build
cargo build --workspace --exclude ripdpi-bench

# Cross-compile check (host only -- no NDK linker)
cargo check --target aarch64-linux-android -p ripdpi-packets
```

## Cargo.lock management

The lock file is checked into git (application, not library):

```bash
# Update single dep precisely
cargo update -p tokio --precise 1.42.0

# Preview changes
cargo update --dry-run

# Regenerate lock file
cargo generate-lockfile
```
