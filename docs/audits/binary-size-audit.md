# Native Binary Size Audit

Audited and updated: 2026-03-24

Scope:

- `libripdpi.so` from `native/rust/crates/ripdpi-android/`
- `libripdpi-tunnel.so` from `native/rust/crates/ripdpi-tunnel-android/`
- All four Android ABIs
- Local debug-like profile `android-jni-dev`
- CI/release profile `android-jni`

## Summary

The audit actions that were safe to land without changing shipped product
capabilities are now implemented.

Combined result:

- Release native payload dropped from 42.00 MiB to 32.88 MiB across the 8
  shipped `.so` files.
- Absolute release reduction: 9.12 MiB
- Relative release reduction: 21.7%
- Debug-like `android-jni-dev` payload dropped from 352.03 MiB to 350.80 MiB.
- The arm64 shipped pair is now 8.46 MiB combined, down from 10.68 MiB.

Landed changes:

1. `android-jni` now sets `opt-level = "z"`.
2. Workspace dependencies now disable heavy defaults for `tokio`,
   `tokio-util`, `smoltcp`, and `hickory-proto`.
3. Tokio features are narrowed per crate instead of being unified
   workspace-wide.
4. `ripdpi-proxy-config` no longer parses UI JSON through
   `serde_json::Value` plus `from_value`; it now validates top-level shape
   with a lightweight visitor and deserializes directly from the source string.
5. Cold async framing helpers in `ripdpi-dns-resolver` are marked
   `#[inline(never)]` to reduce duplicate generic bodies.
6. The 32-bit Android build blocker in `ripdpi-telemetry` is permanently fixed
   by using `metrics::atomics::AtomicU64`.
7. PR CI now checks both packaged `.so` size budgets and representative
   `cargo-bloat` regressions.

Not landed:

- Feature-gating DNSCrypt / QUIC / diagnostics-heavy paths out of the shipping
  flavor. That still has the biggest remaining upside, but it changes feature
  availability and overlaps active DNS/QUIC work, so it was not safe to land
  blindly in the same commit.

## Method And Caveats

Build commands used:

```bash
./gradlew :core:engine:buildRustNativeLibs \
  -Pripdpi.localNativeAbis=armeabi-v7a,arm64-v8a,x86,x86_64

CI=1 ./gradlew :app:mergeDebugNativeLibs
```

Those map to:

- `android-jni-dev` for direct local native builds
- `android-jni` for CI/release-like native outputs

Tooling notes:

- `cargo-bloat` uses the representative
  `x86_64-linux-android` `android-jni` build.
- `twiggy top` and `twiggy monos` still need the symbol-rich
  `android-jni-dev` `.so` files. Stripped release `.so` files remain unsuitable
  for useful `twiggy` output.
- `twiggy` still warns that relocations were not emitted, so `top` and `monos`
  are useful, but `dominators`, `paths`, and `garbage` are not.

## Current Sizes

### Per Library, Per ABI, Per Profile

| ABI | Profile | Library | Bytes | MiB |
|---|---|---|---:|---:|
| `arm64-v8a` | `android-jni-dev` | `libripdpi-tunnel.so` | 47,720,504 | 45.51 |
| `arm64-v8a` | `android-jni-dev` | `libripdpi.so` | 55,144,944 | 52.59 |
| `armeabi-v7a` | `android-jni-dev` | `libripdpi-tunnel.so` | 36,814,008 | 35.11 |
| `armeabi-v7a` | `android-jni-dev` | `libripdpi.so` | 42,593,424 | 40.62 |
| `x86` | `android-jni-dev` | `libripdpi-tunnel.so` | 38,597,696 | 36.81 |
| `x86` | `android-jni-dev` | `libripdpi.so` | 45,183,384 | 43.09 |
| `x86_64` | `android-jni-dev` | `libripdpi-tunnel.so` | 47,107,648 | 44.93 |
| `x86_64` | `android-jni-dev` | `libripdpi.so` | 54,681,912 | 52.15 |
| `arm64-v8a` | `android-jni` | `libripdpi-tunnel.so` | 3,847,456 | 3.67 |
| `arm64-v8a` | `android-jni` | `libripdpi.so` | 5,026,400 | 4.79 |
| `armeabi-v7a` | `android-jni` | `libripdpi-tunnel.so` | 2,432,964 | 2.32 |
| `armeabi-v7a` | `android-jni` | `libripdpi.so` | 3,232,348 | 3.08 |
| `x86` | `android-jni` | `libripdpi-tunnel.so` | 4,155,520 | 3.96 |
| `x86` | `android-jni` | `libripdpi.so` | 5,585,440 | 5.33 |
| `x86_64` | `android-jni` | `libripdpi-tunnel.so` | 4,411,760 | 4.21 |
| `x86_64` | `android-jni` | `libripdpi.so` | 5,789,144 | 5.52 |

### Totals

| Slice | Bytes | MiB |
|---|---:|---:|
| Release total, all 8 libraries | 34,481,032 | 32.88 |
| Debug total, all 8 libraries | 367,843,520 | 350.80 |
| Release total, `libripdpi.so` across 4 ABIs | 19,633,332 | 18.72 |
| Release total, `libripdpi-tunnel.so` across 4 ABIs | 14,847,700 | 14.16 |
| Debug total, `libripdpi.so` across 4 ABIs | 197,603,664 | 188.45 |
| Debug total, `libripdpi-tunnel.so` across 4 ABIs | 170,239,856 | 162.35 |

### Per ABI Release Pair

| ABI | Bytes | MiB |
|---|---:|---:|
| `arm64-v8a` pair | 8,873,856 | 8.46 |
| `armeabi-v7a` pair | 5,665,312 | 5.40 |
| `x86` pair | 9,740,960 | 9.29 |
| `x86_64` pair | 10,200,904 | 9.73 |

### Delta Vs The Original Audit Baseline

| Slice | Delta bytes | Delta MiB | Delta % |
|---|---:|---:|---:|
| Release total | -9,559,984 | -9.12 | -21.7% |
| Debug total | -1,286,668 | -1.23 | -0.3% |

## Cargo Profiles In Use

From [`native/rust/Cargo.toml`](/Users/po4yka/GitRep/RIPDPI/native/rust/Cargo.toml):

### `[profile.release]`

- `lto = "thin"`
- `panic = "abort"`
- `strip = "symbols"`
- `codegen-units = 1`
- `opt-level` is still implicit here, so plain release falls back to Rust's
  default release level (`3`)

### `[profile.android-jni]`

- `inherits = "release"`
- `opt-level = "z"`
- `panic = "unwind"`

### `[profile.android-jni-dev]`

- `inherits = "dev"`
- `panic = "unwind"`
- `opt-level = 1`
- `debug = "line-tables-only"`

Assessment:

- Phase 1 is now partially implemented in the shipped profile.
- The highest-leverage low-risk knob was `opt-level = "z"` on `android-jni`,
  and it is now active.
- `panic = "abort"` is still intentionally not used at the JNI boundary.

## Workspace Feature Flags

Workspace crates with explicit feature flags:

| Crate | Features | Default-enabled features |
|---|---|---|
| `android-support` | `loom` | none |
| `ripdpi-android` | `loom` | none |
| `ripdpi-dns-resolver` | `hickory-backend` | none |
| `ripdpi-monitor` | `hickory`, `loom`, `quic-native` | none |
| `ripdpi-runtime` | `loom` | none |
| `ripdpi-tunnel-android` | `loom` | none |
| `ripdpi-ws-tunnel` | `chrome-fingerprint`, `default = []` | none |

Still true after the optimization pass:

- `loom` features: not enabled
- `ripdpi-monitor/hickory`: not enabled
- `ripdpi-monitor/quic-native`: not enabled
- `ripdpi-dns-resolver/hickory-backend`: not enabled
- `ripdpi-ws-tunnel/chrome-fingerprint`: not enabled
- `quinn`: not present in the shipping graph
- `boring`: not present
- `hickory-resolver`: not present in the shipping graph

Important remaining gap:

- DNSCrypt is still compiled in unconditionally inside `ripdpi-dns-resolver`.
- QUIC parsing / fake-profile logic is still always built into the consumer
  flavor.

## `cargo-bloat` Top Contributors

Representative target:

- `x86_64-linux-android`
- profile: `android-jni`

### `libripdpi.so` Top 20

| Rank | Bytes | Symbol |
|---:|---:|---|
| 1 | 31,401 | `ripdpi_android::diagnostics::scan::decode_scan_request` |
| 2 | 19,571 | `ripdpi_config::parse::cli::parse_cli` |
| 3 | 19,273 | `std::backtrace_rs::symbolize::gimli::Cache::with_global` |
| 4 | 16,821 | `ripdpi_dns_resolver::resolver::EncryptedDnsResolver::exchange_with_metadata::{closure}` |
| 5 | 15,078 | `ripdpi_runtime::runtime::relay::relay` |
| 6 | 12,222 | `h2::codec::framed_read::decode_frame` |
| 7 | 12,065 | `ring_core_0_17_14__chacha20_poly1305_seal_avx2` |
| 8 | 11,802 | `data_encoding::Encoding::encode_mut` |
| 9 | 11,723 | `std::backtrace_rs::symbolize::gimli::Context::new` |
| 10 | 11,717 | `hyper_util::client::legacy::client::Client<C,B>::send_request::{closure}` |
| 11 | 11,635 | `<rustls::client::hs::ExpectServerHello as State<ClientConnectionData>>::handle` |
| 12 | 11,074 | `std::sys::backtrace::__rust_begin_short_backtrace` |
| 13 | 11,030 | `http::header::name::StandardHeader::from_bytes` |
| 14 | 10,964 | `rustls::client::hs::emit_client_hello_for_retry` |
| 15 | 10,786 | `TelegramRunner::run` |
| 16 | 10,577 | `DnsRunner::run` |
| 17 | 10,121 | `reqwest::async_impl::client::ClientBuilder::build` |
| 18 | 9,817 | `ripdpi_proxy_config::convert::runtime_config_from_ui` |
| 19 | 9,678 | `ring_core_0_17_14__chacha20_poly1305_open_avx2` |
| 20 | 9,412 | `ripdpi_monitor::observations::observation_for_probe` |

Crate-level view:

- `std`: 562,763 bytes
- `rustls`: 278,956 bytes
- `reqwest`: 267,274 bytes
- `ripdpi_monitor`: 263,171 bytes
- `ripdpi_runtime`: 207,389 bytes
- `ring`: 195,426 bytes
- `h2`: 115,976 bytes
- `ripdpi_proxy_config`: 112,697 bytes
- `ripdpi_dns_resolver`: 77,094 bytes
- `hickory_proto`: 76,564 bytes

Interpretation:

- The proxy-side serde/config cost is materially lower than in the original
  audit. `ProxyUiConfig` deserialization is no longer the largest release
  function, and the main visible proxy-config entry is now
  `runtime_config_from_ui` at 9.8 KiB.
- The async DNS/HTTP/TLS stack is still the biggest structural bucket.

### `libripdpi-tunnel.so` Top 20

| Rank | Bytes | Symbol |
|---:|---:|---|
| 1 | 19,697 | `std::backtrace_rs::symbolize::gimli::Cache::with_global` |
| 2 | 19,046 | `ripdpi_tunnel_core::io_loop::dns_intercept::spawn_dns_worker::{closure}` |
| 3 | 18,058 | `ripdpi_tunnel_core::io_loop::io_loop_task::{closure}` |
| 4 | 13,874 | `TunnelConfigPayload` serde visitor `visit_map` |
| 5 | 13,250 | `ripdpi_tunnel_android::session::lifecycle::create_session` |
| 6 | 12,222 | `h2::codec::framed_read::decode_frame` |
| 7 | 12,065 | `ring_core_0_17_14__chacha20_poly1305_seal_avx2` |
| 8 | 11,802 | `data_encoding::Encoding::encode_mut` |
| 9 | 11,723 | `std::backtrace_rs::symbolize::gimli::Context::new` |
| 10 | 11,717 | `hyper_util::client::legacy::client::Client<C,B>::send_request::{closure}` |
| 11 | 11,635 | `<rustls::client::hs::ExpectServerHello as State<ClientConnectionData>>::handle` |
| 12 | 11,030 | `http::header::name::StandardHeader::from_bytes` |
| 13 | 10,964 | `rustls::client::hs::emit_client_hello_for_retry` |
| 14 | 10,121 | `reqwest::async_impl::client::ClientBuilder::build` |
| 15 | 9,678 | `ring_core_0_17_14__chacha20_poly1305_open_avx2` |
| 16 | 9,244 | `curve25519_dalek` AVX2 field multiply implementation |
| 17 | 9,168 | `gimli::read::dwarf::Unit<R>::new` |
| 18 | 9,113 | `<Pin<P> as Future>::poll` |
| 19 | 8,737 | `ring_core_0_17_14__chacha20_poly1305_seal_sse41` |
| 20 | 8,663 | `core::cell::once::OnceCell<T>::try_init` |

Crate-level view:

- `std`: 439,147 bytes
- `rustls`: 276,969 bytes
- `reqwest`: 268,954 bytes
- `ring`: 196,001 bytes
- `ripdpi_tunnel_core`: 127,185 bytes
- `h2`: 115,925 bytes
- `hickory_proto`: 76,494 bytes
- `tokio`: 72,669 bytes
- `webpki`: 65,356 bytes
- `hyper_util`: 53,251 bytes

Interpretation:

- `smoltcp` dropped far enough that it is no longer a top-10 release crate
  here; disabling its defaults was a real win.
- Tunnel-side config serde is still visible, but the dominant cost remains the
  async DNS/TLS stack plus the tunnel I/O loop.

## `twiggy` Findings

Representative target:

- `x86_64-linux-android`
- profile: `android-jni-dev`

### Shallow Item Notes

The same large crypto/static items still lead both debug libraries:

- `ecp_nistz256_precomputed`: 151,552 bytes
- `ring_core_0_17_14__k25519Precomp`: 24,576 bytes

These are still real, but they are not the best first target unless the
surrounding crypto stack itself becomes optional.

### `libripdpi.so` Generic Monomorphization Hotspots

| Approx. bloat bytes | Family |
|---:|---|
| 128,916 | `serde_json::Deserializer::deserialize_struct` |
| 40,941 | `core::slice::sort::stable::quicksort::stable_partition` |
| 40,525 | `Vec<T>::clone` |
| 37,543 | `serde::private::de::content::visit_content_map` |
| 36,023 | `core::slice::sort::stable::quicksort::quicksort` |
| 31,777 | `Vec<T>::from_iter` |
| 28,244 | `<&T as Debug>::fmt` |
| 22,727 | `hashbrown::RawTable::reserve_rehash` |
| 22,210 | `core::slice::sort::stable::drift::sort` |
| 21,581 | `core::slice::sort::shared::smallsort::sort4_stable` |

Interpretation:

- Serde-driven duplication is still the largest generic hotspot in the proxy
  library even after the config parser cleanup.
- The Phase 3 change reduced the release-visible config hotspot, but it did not
  remove serde from the dominant debug monomorphization bucket.

### `libripdpi-tunnel.so` Generic Monomorphization Hotspots

| Approx. bloat bytes | Family |
|---:|---|
| 23,814 | `<&T as Debug>::fmt` |
| 21,609 | `hashbrown::RawTable::reserve_rehash` |
| 20,792 | `untrusted::Input::read_all` |
| 20,198 | `Arc::drop_slow` |
| 17,798 | `tokio::time::timeout::Timeout<T>::poll` |
| 14,922 | `Vec<T>::clone` |
| 14,741 | `core::slice::sort::stable::quicksort::quicksort` |
| 14,322 | `Vec<T>::from_iter` |
| 13,090 | `webpki::der::nested` |
| 11,623 | `core::slice::sort::stable::drift::sort` |

Interpretation:

- Tunnel-side duplication is still mixed across async futures, hashing,
  formatting, collections, and TLS parsing.
- The cold-helper `#[inline(never)]` changes were safe and worth landing, but
  they are only one part of the tunnel-side generic bloat story.

## Serde Derives

Total derive sites matching `Serialize` or `Deserialize`: 107

Largest clusters:

- `ripdpi-monitor/src/types/observation.rs`: 21
- `ripdpi-proxy-config/src/types.rs`: 20
- `ripdpi-monitor/src/types/target.rs`: 11
- `ripdpi-monitor/src/types/scan.rs`: 8
- `local-network-fixture/src/types.rs`: 7
- `ripdpi-tunnel-config/src/lib.rs`: 5
- `ripdpi-monitor/src/wire.rs`: 5
- `ripdpi-monitor/src/types/request.rs`: 5
- `ripdpi-failure-classifier/src/lib.rs`: 5

Current impact:

- `ripdpi_proxy_config` still costs 112,697 release bytes in
  `libripdpi.so`, but it is no longer dominated by a single 56 KiB generated
  UI visitor as in the original audit.
- The largest release-visible serde-generated item is now the tunnel config
  visitor at 13,874 bytes.
- `twiggy monos` still shows `deserialize_struct` as the single largest proxy
  monomorphization family at 128,916 approximate bloat bytes.

Assessment:

- Phase 3 improved size materially, but serde remains a major contributor.
- The next serde win is still to isolate or simplify the broad config/report
  model surface, not only telemetry serialization.

## Tokio Assessment

### Features Enabled In The Shipping Graph

The direct crate declarations are now much narrower, but the resolved shipping
graph still includes transitive Tokio feature unification from dependencies such
as `fast-socks5`, `h2`, and `hyper-util`.

Observed in the current feature graph:

- `tokio`: `default`, `io-util`, `macros`, `net`, `rt`, `rt-multi-thread`,
  `sync`, `time`
- `tokio-util`: `default`, `codec`, `io`, `rt`

### Features Actually Used

Direct production requests after the cleanup:

- `ripdpi-dns-resolver`: `io-util`, `net`, `rt`, `sync`, `time`
- `ripdpi-monitor` optional tokio path: `net`, `rt`, `time`
- `ripdpi-tunnel-core`: `io-util`, `macros`, `net`, `rt`, `sync`, `time`
- `ripdpi-tunnel-android`: `rt-multi-thread`
- `tokio-util`: `rt` only where directly requested

Assessment:

- The direct Tokio cleanup is landed.
- The remaining Tokio bloat is now mostly transitive, not self-inflicted by the
  workspace dependency declaration.
- The next meaningful Tokio reduction still requires reducing or splitting the
  always-on async DNS/HTTP/TLS stack.

## Optimization Plan

Estimates below are for the full universal release payload across all 8 native
libraries. They are directional, not additive guarantees.

### Phase 1: Release Profile Tuning

Status: landed

What landed:

1. `android-jni` now uses `opt-level = "z"`.
2. Existing `thin` LTO, `strip = "symbols"`, and `codegen-units = 1` were kept.

Estimated size reduction:

- 3 to 6 MiB by itself

Observed result:

- Contributes to the combined 9.12 MiB release reduction from the full landed
  set

Risk:

- low to medium
- `opt-level = "z"` can trade throughput for size

Verification:

- compare per-ABI packaged `.so` bytes
- smoke-test proxy start/stop and tunnel start/stop
- check JNI crash behavior remains acceptable with `panic = "unwind"`

### Phase 2: Tokio Feature Gating

Status: mostly landed for direct dependencies

What landed:

1. Workspace-wide Tokio defaults were removed.
2. Per-crate Tokio features were narrowed.
3. Direct `tokio-util` usage is now `default-features = false`.

Estimated size reduction:

- 0.5 to 1.5 MiB for the direct cleanup already landed
- 3 to 6 MiB more if the async DNS/HTTP/TLS stack becomes optional by flavor

Risk:

- medium
- feature unification bugs still show up easily in tests

Verification:

- `cargo tree -e features` before/after
- native `cargo check`
- JNI build for all four ABIs

### Phase 3: Serde Optimization

Status: partially landed

What landed:

1. UI payload parsing now validates top-level shape with a streaming visitor.
2. Full `serde_json::Value` DOM materialization on the proxy config path is
   removed.

Estimated size reduction:

- 1 to 3 MiB for selected config/report hot paths

Observed result:

- `ProxyUiConfig` deserialization is no longer the top release function
- `ripdpi_proxy_config` still remains a meaningful crate-level contributor

Risk:

- medium to high
- config JSON compatibility drift is easy to introduce

Verification:

- `ripdpi-proxy-config` tests
- host `cargo check`
- release `cargo-bloat` comparison for `ripdpi_proxy_config` and `serde_json`

### Phase 4: Generic Deduplication

Status: small targeted win landed

What landed:

1. `#[inline(never)]` was added to cold async frame read/write helpers in
   `ripdpi-dns-resolver`.

Estimated size reduction:

- 0.5 to 1.5 MiB if expanded across more cold generic families

Observed result:

- safe cleanup landed, but major generic duplication remains in serde, sorting,
  formatting, and async timeout futures

Risk:

- medium
- easy to trade size for throughput in hot loops

Verification:

- `twiggy monos` diff on debug `x86_64`
- `cargo-bloat` diff on release `x86_64`

### Phase 5: Feature-Gated Optional Capabilities

Status: not landed

Remaining work:

1. Feature-gate DNSCrypt support in `ripdpi-dns-resolver`.
2. Feature-gate QUIC-specific fake payload logic for lighter flavors.
3. Make diagnostics-heavy monitor/runtime support optional by shipped flavor.

Estimated size reduction:

- 4 to 8 MiB depending on which capabilities move out of the consumer flavor

Risk:

- high
- changes real behavior and expands the supported feature matrix

Verification:

- build each feature set in CI
- run smoke/contract coverage per feature set
- compare universal APK native totals by flavor

## CI Integration

This is now implemented.

Build-time checks:

1. `scripts/ci/verify_native_sizes.py`
   - verifies packaged tracked `.so` sizes from
     `app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib`
   - baseline: `scripts/ci/native-size-baseline.json`
   - thresholds:
     - max per-library growth: 128 KiB
     - max total tracked growth: 2% or 256 KiB, whichever is tighter
2. `scripts/ci/verify_native_bloat.py`
   - runs representative `cargo bloat --message-format json`
   - target: `x86_64-linux-android`
   - profile: `android-jni`
   - baseline: `scripts/ci/native-bloat-baseline.json`
   - checks:
     - text section growth
     - top function growth
     - top crate growth
     - unexpectedly large new top functions/crates

Workflow integration:

- `.github/workflows/ci.yml` now installs `cargo-bloat`
- the build job now runs both size verifiers on every PR/push CI run

Recommended follow-up:

- When a PR intentionally changes native size, refresh both baseline files in
  the same PR and explain the reason in the PR body.

## Native Size Target

For a universal sideloadable APK that includes all 8 native libraries, the
target remains:

- short-term budget: 28 MiB total native payload
- stretch budget: 24 MiB total native payload
- per-device arm64 pair target: 8 MiB or less combined

Current position relative to target:

- current universal release payload: 32.88 MiB
- gap to short-term budget: 4.88 MiB
- current arm64 pair: 8.46 MiB
- gap to arm64 pair target: 0.46 MiB

Assessment:

- The low-risk optimization pass closed most of the easy gap.
- Reaching 28 MiB now likely requires capability gating, not only more profile
  tuning.

## Recommended Next Moves

1. Decide whether the consumer APK can ship without always-on DNSCrypt and/or
   some QUIC fake-profile paths.
2. Split the async DNS/HTTP/TLS stack into a lighter always-on core plus an
   optional diagnostics/encrypted-DNS layer.
3. Continue shrinking serde-heavy report/config models, especially in
   `ripdpi-monitor` and `ripdpi-proxy-config`.
4. Keep the new CI baselines tight so the 32.88 MiB result does not drift back
   upward while the deeper feature work is pending.
