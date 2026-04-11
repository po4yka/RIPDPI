# Relay Transport Status

This note records the current state of the relay transport work after adding:

- Hysteria2 Salamander support
- Relay UDP support for `hysteria2` and `masque`
- MASQUE TCP and UDP support
- MASQUE bearer and preshared authentication
- MASQUE `privacy_pass` runtime plumbing with a deployer-supplied token provider

It also documents what is still intentionally missing in the current build.

## Implemented Now

### Native relay runtime

- `native/rust/crates/ripdpi-relay-core/src/lib.rs`
  - Stateful relay backends instead of TCP-only helper calls
  - SOCKS5 `CONNECT` and `UDP ASSOCIATE`
  - UDP relay support for `hysteria2` and `masque`
  - Domain targets preserved through SOCKS5 UDP frames instead of forced local resolution
- `native/rust/crates/ripdpi-relay-core/src/socks5.rs`
  - SOCKS5 target parsing
  - SOCKS5 UDP frame encode/decode for IPv4, IPv6, and domain names

### Hysteria2

- `native/rust/crates/ripdpi-hysteria2/src/lib.rs`
  - Stateful Hysteria2 client
  - TCP proxy streams
  - UDP datagram sessions
  - Salamander obfuscation at the UDP socket boundary
  - Datagram fragmentation/reassembly for relay-side UDP

### MASQUE

- `native/rust/crates/ripdpi-masque/src/lib.rs`
  - HTTP/3 `CONNECT` for TCP
  - HTTP/3 `CONNECT-UDP`
  - HTTP/2 TCP fallback
  - Bearer auth
  - Preshared auth via `Proxy-Authorization: Preshared ...`
  - `privacy_pass` retry flow driven by a deployer-supplied HTTP provider
- `native/rust/crates/ripdpi-masque/src/auth.rs`
  - Static auth header construction
  - `PrivateToken` challenge parsing
  - In-memory caching of provider-supplied auth headers

### Android/service wiring

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/UpstreamRelaySupervisor.kt`
  - Passes Salamander and UDP settings to native runtime
  - Resolves `privacy_pass` runtime inputs through `MasquePrivacyPassProvider`
  - Validates build/provider readiness before native launch
  - Fails fast if `privacy_pass` is selected but no valid runtime provider is available
- `core/service/build.gradle.kts`
  - Injects deployer-supplied provider settings into `BuildConfig`
  - Reads `masque.privacyPassProviderUrl` / `masque.privacyPassProviderAuthToken` from `local.properties`
  - Reads `RIPDPI_MASQUE_PRIVACY_PASS_PROVIDER_URL` / `RIPDPI_MASQUE_PRIVACY_PASS_PROVIDER_AUTH_TOKEN` from env
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/MasquePrivacyPassProvider.kt`
  - Binds a real `BuildConfigMasquePrivacyPassProvider`
  - Validates MASQUE auth mode and provider URL format
  - Exposes build availability and provider-url validity to the app through `MasquePrivacyPassAvailability`
- `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiRelay.kt`
  - Carries provider URL/auth token into the JNI/native contract

### App/config UI

- `app/src/main/kotlin/com/poyka/ripdpi/activities/ConfigViewModel.kt`
  - Supports MASQUE bearer and preshared modes in current builds
  - Rejects `privacy_pass` in validation when the build does not provide a token provider
  - Sanitizes old `privacy_pass` drafts back to bearer mode in the editor when unavailable
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/config/ModeEditorScreen.kt`
  - Exposes relay UDP for `hysteria2` and `masque`
  - Hides the `Privacy Pass` auth chip when the build has no provider
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/config/RelayFields.kt`
  - Shows provider-aware `Privacy Pass` status for available, missing-provider, and invalid-provider builds

### Interoperability coverage

- `native/rust/crates/ripdpi-masque/src/lib.rs`
  - Includes near-live provider tests using a local HTTP stub for `privacy_pass` token fetch, retry input serialization, caching, and permission-denied failures
- `scripts/ci/run-rust-relay-interoperability.sh`
  - Runs `ripdpi-masque` in the relay interoperability matrix so the provider-flow tests execute in CI together with the broader relay transport suites

## Current Build Behavior

`privacy_pass` is now build-configurable.

- When `MASQUE_PRIVACY_PASS_PROVIDER_URL` is populated through Gradle config, the app exposes `Privacy Pass` as a selectable MASQUE auth mode and the supervisor forwards provider settings to the native runtime.
- When the provider URL is missing or invalid, the app still hides the option and the supervisor rejects stale stored `privacy_pass` profiles before native launch.
- The optional provider auth token is forwarded when configured but is not required for rollout.

This keeps release safety intact while allowing deployer-provided builds to ship a working `privacy_pass` path.

## Phase P2 Closure

The P2 MASQUE closure items are now complete:

- provider-aware build status is surfaced in the editor UI
- the deployer-backed `privacy_pass` path is covered by near-live native tests
- legacy Cloudflare migration fields have been removed from persistence, runtime config contracts, and tests

What remains below is intentionally outside the P2 roadmap exit criteria.

## Future Follow-Ups

### 1. Official Cloudflare `cf-connect-ip` ECDSA mode

Direct Cloudflare-specific ECDSA-authenticated `cf-connect-ip` mode is still not implemented in the current MASQUE client.

That path is distinct from the deployer-supplied `privacy_pass` provider flow added here.

If the project later chooses to support official Cloudflare `cf-connect-ip` directly, that remains separate implementation work.
