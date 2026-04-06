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
  - Fails fast if `privacy_pass` is selected but no runtime provider is available
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
  - Shows a note explaining that `Privacy Pass` is disabled in the current build

## Current Build Behavior

The default application build does **not** ship a real `privacy_pass` provider.

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/MasquePrivacyPassProvider.kt`
  - `NoopMasquePrivacyPassProvider` is the bound implementation
  - `MasquePrivacyPassAvailability.isAvailable()` returns `false`
  - `resolve(...)` returns `null`

Because of that:

- The config editor does not offer `Privacy Pass` as a selectable MASQUE auth mode
- Validation rejects hand-crafted `privacy_pass` drafts in app code
- The relay supervisor still fails fast if a stale stored profile somehow requests `privacy_pass`

This is intentional. The goal in the current build is to keep the app usable without pretending that `privacy_pass` is fully configured.

## What Is Still Missing

### 1. Real deployer-supplied `privacy_pass` provider

The missing piece is a real implementation of:

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/MasquePrivacyPassProvider.kt`

That provider must:

- return `isAvailable() == true`
- produce a `MasquePrivacyPassRuntimeConfig`
- supply the provider URL expected by the native MASQUE client
- optionally supply provider auth material if the deployer requires it

The native MASQUE client already expects the provider to return ready-to-use auth headers or header batches through the external provider endpoint.

### 2. Provider-aware app UX

The current app only knows whether `privacy_pass` is available in the build.

Future product work may still want:

- provider readiness state
- user-facing setup status
- error details for provider failures
- any deployer-specific onboarding or sign-in flow

Those are not required for the current safe fallback behavior, but they are likely required for a production `privacy_pass` rollout.

### 3. Legacy Cloudflare cleanup

Legacy migration fields are still present in the runtime/config contract:

- `masqueCloudflareMode`
- `masqueCloudflareClientId`
- `masqueCloudflareKeyId`
- `masqueCloudflarePrivateKeyPem`

These currently remain only so existing settings and tests can still round-trip. New work should avoid expanding them further.

Once a real deployer-backed `privacy_pass` path is stable, these legacy fields should be removed from:

- app settings persistence
- relay credential storage
- runtime config contracts
- obsolete tests

### 4. Official Cloudflare `cf-connect-ip` ECDSA mode

`native/rust/crates/ripdpi-masque/src/cloudflare.rs` still intentionally fails fast for Cloudflare-specific ECDSA-authenticated MASQUE mode.

That path is distinct from the deployer-supplied `privacy_pass` provider flow added here.

If the project later chooses to support official Cloudflare `cf-connect-ip` directly, that remains separate implementation work.

### 5. Live interoperability coverage

Current verification covers unit tests and targeted Kotlin tests. It does **not** yet include:

- live Hysteria2 TCP/UDP interoperability against a real server
- live MASQUE TCP/UDP interoperability against a real proxy
- deployer-backed `privacy_pass` provider integration tests

Those should be added before enabling `privacy_pass` in release builds.

## Suggested Future Implementation Order

1. Implement a real `MasquePrivacyPassProvider` in `core/service`.
2. Add provider-backed integration tests on the Kotlin side.
3. Add live or stubbed end-to-end MASQUE `privacy_pass` tests on the native side.
4. Expose provider readiness in the app UI only after the provider is real.
5. Remove legacy Cloudflare migration fields after rollout is stable.
