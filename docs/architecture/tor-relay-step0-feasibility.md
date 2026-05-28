# Tor Relay Backend STEP 0 Feasibility Gate

Date: 2026-05-28

## Decision

GO, with prerequisites before slice 1: add `arti-client` with an Android-safe feature set that includes `static-sqlite`, require SDK CMake for Android native builds, and fix `BuildPluggableTransportAssetsTask` so Rust-source PT assets resolve the root `native/rust` workspace instead of `core/engine/native/rust`. The native size delta is large but bounded and does not by itself block an opt-in Tor backend.

## Fixed Architecture

Do not reimplement Tor. The planned `ripdpi-tor` crate wraps Arti and exposes `connect_tcp(target) -> BoxedIo` by calling `arti_client::TorClient::connect((host, port)) -> arti_client::DataStream`. Relay-core wires it as `RelayBackend::Tor`; `udp_capable()` remains false because Tor client streams are TCP only.

The censored-profile bootstrap path must never perform direct directory fetches. RIPDPI must construct Arti bridge and pluggable-transport configuration from user bridge lines and extracted PT binaries before bootstrapping, so the initial Arti directory path is `app -> Arti -> obfs4/WebTunnel PT bridge -> Tor -> exit`.

## Arti API Notes

The probed Arti version was `arti-client 0.42.0`. The requested features `tokio`, `pt-client`, `bridge-client`, and `onion-service-client` exist; Android also needs `rustls` as the TLS backend and `static-sqlite` so `rusqlite`/`libsqlite3-sys` does not link against a missing NDK `-lsqlite3`.

Relevant Arti APIs found in the crate source and docs are `TorClient::create_bootstrapped(config)`, `TorClient::connect(target) -> DataStream`, `TorClient::resolve(hostname)`, `config::BridgeConfigBuilder`, `config::BridgesConfig`, `config::pt::TransportConfigBuilder`, and `TorClientConfigBuilder::from_directories(state_dir, cache_dir)`.

References: [arti-client crate](https://crates.io/crates/arti-client), [TorClient docs](https://docs.rs/arti-client/0.42.0/arti_client/struct.TorClient.html), [configuration docs](https://docs.rs/arti-client/0.42.0/arti_client/config/index.html), [features](https://docs.rs/crate/arti-client/0.42.0/features).

## Android Build Probe

Probe setup: detached worktree `/tmp/ripdpi-arti-step0` at `87d89f37a`; temporary dependency added only there; `ripdpi-relay-android` exported a retained size-probe function that conditionally runs `TorClient::create_bootstrapped`, `connect`, and `resolve` through Tokio so LTO keeps the real Arti client path. Baseline and Arti builds used `--profile android-jni`, NDK `29.0.14206865`, minSdk 27, and SDK CMake `3.31.6`.

| ABI | Baseline `libripdpi-relay.so` | Arti probe `libripdpi-relay.so` | Delta | Delta % |
| --- | ---: | ---: | ---: | ---: |
| armeabi-v7a | 5,665,636 B (5.40 MiB) | 9,844,552 B (9.39 MiB) | +4,178,916 B (+3.99 MiB) | +73.8% |
| arm64-v8a | 8,630,232 B (8.23 MiB) | 14,132,424 B (13.48 MiB) | +5,502,192 B (+5.25 MiB) | +63.8% |
| x86 | 7,848,984 B (7.49 MiB) | 13,395,664 B (12.78 MiB) | +5,546,680 B (+5.29 MiB) | +70.7% |
| x86_64 | 10,238,176 B (9.76 MiB) | 16,340,368 B (15.58 MiB) | +6,102,192 B (+5.82 MiB) | +59.6% |

Build blockers and mitigations:

- `arti-client` with the requested feature set but without `static-sqlite` failed on Android arm64 at link time with `ld.lld: error: unable to find library -lsqlite3`; mitigation is enabling Arti's `static-sqlite` feature.
- Homebrew CMake injected macOS `-arch arm64` into `boring-sys` Android compiler checks after cache churn; mitigation is using Android SDK CMake. This matches the existing Gradle plugin expectation and should be documented as a native-build prerequisite.
- Adding Arti pulled 229 additional locked packages in the probe. This is expected for Tor directory, guard, persistence, PT manager, and onion-client support, but it makes `cargo deny`, license review, and duplicate-dependency review mandatory in slice 1.

## Pluggable Transport Interop

Managed-client protocol checks passed for both planned bridge transports:

- WebTunnel: `cargo test --manifest-path /tmp/ripdpi-arti-step0/native/rust/Cargo.toml -p ripdpi-webtunnel --test pt_managed_client` passed 6 tests, including `CMETHOD webtunnel socks5 ...` emission, RFC1929 PT-arg splitting, and stdin-close shutdown.
- obfs4: host-built pinned Lyrebird (`fc105a03c0e0acc2479301c361c012ffed359c43`) emitted `VERSION 1`, `STATUS TYPE=version IMPLEMENTATION="lyrebird" VERSION="devel"`, `CMETHOD obfs4 socks5 127.0.0.1:<port>`, and `CMETHODS DONE` with `TOR_PT_CLIENT_TRANSPORTS=obfs4`.
- Android arm64 Lyrebird source build produced `core/engine/build/generated/pluggableTransportAssets/bin/arm64-v8a/ripdpi-obfs4.upstream` at about 16 MiB plus the launcher script. Direct Cargo build produced arm64 `ripdpi-webtunnel` at 2,163,408 bytes.

Current PT build-system blocker: `:core:engine:buildPluggableTransportAssets -Pripdpi.pluggableTransportAssetsMode=source` fails after Lyrebird because the Rust-source branch uses `project.layout.projectDirectory.file("native/rust/Cargo.toml")`, which resolves under `core/engine/native/rust`. It must use the root project directory, and configuration-cache storage also reports execution-time `Task.project` access. Slice 1 must either fix this task first or keep Tor gated behind already-extracted PT paths until the task is corrected.

## Android State Directory

Arti exposes `TorClientConfigBuilder::from_directories(state_dir, cache_dir)` and validates a writable `state_dir`; with `pt-client`, Arti also creates `pt_state` under that state directory. RIPDPI already creates app-private PT state under `Context.filesDir/pluggable-transports/<profile>-<method>` in `PluggableTransportManager`, so the Tor backend should use app-private directories such as `Context.filesDir/tor/<profile>/state` and `Context.cacheDir/tor/<profile>/cache`, create them on the Kotlin side, and pass literal paths through JNI to `ripdpi-tor`.

On an API 34 arm64 emulator repaired from `system-images;android-34;aosp_atd;arm64-v8a`, the existing `app-github-arm64-v8a-debug.apk` installed as `com.poyka.ripdpi`; `run-as com.poyka.ripdpi` created `/data/data/com.poyka.ripdpi/files/tor/step0/state` and `/data/data/com.poyka.ripdpi/files/tor/step0/cache`, wrote `write-smoke.txt` in both directories, read both files back, and listed them as owned by the app UID. This confirms the Android app-private state/cache directory route is writable for Arti state. Slice 1 should still add a JVM contract test for path construction and an instrumentation or emulator smoke that creates, writes, and reopens the Tor state/cache directories before bootstrapping.

## Test Plan After GO

1. TDD slice 1: add failing host tests for `ripdpi-tor` config construction and `connect_tcp` type shape, then implement a thin Arti wrapper. Add a Chutney local Tor network E2E for `TorClient::connect` through a controlled bridge path before any relay-core wiring.
2. TDD slice 2: add failing tests for bridge-line parsing into Arti `BridgeConfigBuilder` and PT binary mapping into `TransportConfigBuilder`, including the Russia profile invariant that direct bootstrap is disabled. Fix `BuildPluggableTransportAssetsTask` before depending on WebTunnel assets from Gradle.
3. TDD slice 3: add persistence tests that prove guard/consensus state survives restart, Tor DNS uses `TorClient::resolve`, and `.onion` targets are accepted without local DNS resolution.
4. TDD slice 4: wire `RelayBackend::Tor`, `RelayKind::Tor`, runtime validation, and capability descriptor tests with `udp_capable=false`; add negative tests for UDP ASSOCIATE.
5. TDD slice 5: add Kotlin schema migration, `RelayNativeConfig` fields for bridge lines and PT binary paths, opt-in UI/backend label, and all locale strings if any user-visible key is added.

Live Tor is not default CI. Chutney/local Tor is the offline oracle; live Tor can be a manually gated nightly only.

## Non-Goals

- UDP over Tor; `udp_capable=false`.
- Running a Tor relay or onion service from RIPDPI.
- Replacing fast proxy relays as the default path.
- Custom Tor path policy.
- Bundling PT implementations inside Arti; PT binaries remain external assets managed by RIPDPI.
