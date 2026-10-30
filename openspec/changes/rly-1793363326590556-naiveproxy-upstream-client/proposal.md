# Change: Ship the pinned upstream NaïveProxy client for real-certificate profiles

Task ID: `RLY-1793363326590556`

## Why

NaïveProxy exists to make proxy traffic look like Chrome. A wire comparison on 2026-10-30 ran the RIPDPI Rust helper (`ripdpi-naiveproxy`) and the upstream client v154.0.8037.49-5 (commit d04efe1) against the same pinned Caddy forwardproxy server. The Rust helper is easy to tell apart from upstream:

- a TLS HelloRetryRequest on every handshake with Go servers;
- a different JA4;
- no GREASE, ALPS or certificate compression;
- a different ECH GREASE KEM;
- different HTTP/2 SETTINGS and windows;
- no cover-site preamble, Fast Open or padded stream close;
- periodic constant-payload PINGs.

rustls cannot produce several parts of Chrome's ClientHello, so closing the gap inside the Rust helper would never converge. The upstream client is Chromium's own network stack. Upstream publishes an Android PIE executable per ABI. It supports a JSON config file, so credentials can stay out of argv. It supports authenticated SOCKS and HTTP listeners, extra headers and static host mapping.

The upstream client trusts only the Chrome Root Store and the system CA store, and has no certificate pin. RIPDPI already ships a per-profile server certificate pin. Profiles that use the pin must keep working.

## What Changes

- A NaïveProxy profile without a certificate pin runs the pinned upstream client as its subprocess. A profile with a pin keeps running the Rust helper. The Rust helper's behavior does not change.
- The upstream client binary comes from the pinned upstream release. The build verifies each per-ABI artifact against a sha256 recorded in the repository and stages the executable as a helper jniLib. A missing or mismatched artifact fails the build; it never falls back silently.
- Upstream credentials and listener credentials reach the upstream client only through a config file that only the app can read. They never appear in argv, logs or diagnostics. The file is removed when the runtime stops.
- The local listener stays authenticated and bound to loopback.
- The proxy host is resolved through the Rust helper's existing protected DoH path. It is passed to the upstream client as a static host mapping, so a poisoned system resolver cannot redirect the proxy connection. If resolution fails, the start fails closed.
- Readiness uses the existing authenticated SOCKS readiness probe. Startup and exit failures map to the existing relay failure classes. Upstream-health lines that only the Rust helper emits (`RIPDPI-UPSTREAM-OK`) remain absent for the upstream client, so its health stays "running" and does not report "healthy".
- A pin-free profile now presents Chromium's TLS and HTTP/2 behavior on the wire. This includes the cover-site preamble and Fast Open, which upstream performs by default.
- BREAKING (packaging): APKs grow by about 7.2 MB per ABI (the uncompressed upstream executable), because both clients ship.

## Capabilities

### New Capabilities

- `naiveproxy-client-runtime`: owns which NaïveProxy client runs for a profile, how the upstream artifact is pinned and staged, how credentials and the proxy address reach the upstream client, and the readiness, failure and cleanup contract of the upstream runtime.

### Modified Capabilities

- None.

## Impact

- `build-logic/convention`: helper executable staging (`HelperExecutableJniLibsTask` and the rust-native plugin) gains a pinned, digest-verified upstream artifact source.
- New pin manifest for the upstream client, next to `native/pluggable-transports/sources.json`, plus a fetch and extract script under `scripts/ci/`.
- CI and release workflows that stage helper artifacts (`ci.yml`, `release-candidate.yml`) and the native-artifact table in `docs/architecture/ARCHITECTURE.md`.
- `core/service`: `NaiveProxyManager`, `SubprocessSocksRelayManager` launch specs, the new upstream config writer, and client selection.
- `native/rust/crates/ripdpi-naiveproxy`: a resolve-only mode that reuses `resolve_server_addrs` under the protect path. The relay data path does not change.
- Acceptance: `test-lab/acceptance` peer scenarios and the `android-naiveproxy` emulator scenario. Real-certificate trust for the upstream client on the emulator comes from a lab CA mounted into the system CA directory.
- Native size: no tracked baseline covers helper executables, but APK size gates (per-ABI release size) must be checked.
