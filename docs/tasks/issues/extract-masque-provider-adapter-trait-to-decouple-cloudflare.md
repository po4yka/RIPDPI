---
title: Extract MasqueProviderAdapter trait to decouple Cloudflare-specific paths
type: task
status: todo
area: rust-native
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-06-05
---

## Summary

Keep MASQUE provider behavior behind `MasqueProviderAdapter` so self-hosted RFC 9298 providers can use bearer, preshared, Privacy Pass, or TLS client certificate auth without adding proprietary Cloudflare routing behavior to the generic auth path.

## Context

`native/rust/crates/ripdpi-masque/CONFORMANCE.md` now documents the generic/self-hosted adapter surface. `provider_adapter.rs` exposes one `GenericSelfHostedAdapter` selected by `MasqueAuthMode`; Privacy Pass retry and TLS client certificate requirements are auth-mode behavior, while proprietary commercial-provider flows remain out of scope.

## Acceptance criteria

- [x] `MasqueProviderAdapter` describes the provider surface with `provider_id`, `auth_mode`, `auth_header`, `uses_privacy_pass_retry`, `requires_client_certificate`, and `wants_geohash_header`.
- [x] `GenericSelfHostedAdapter` implements the trait for all current `MasqueAuthMode` values. `CloudflareMtls` is treated as generic TLS client certificate auth for compatibility with the existing mode string; there is no `CloudflareDirectAdapter` type in current source.
- [x] Privacy Pass is represented by `MasqueAuthMode::PrivacyPass`; `GenericSelfHostedAdapter` advertises `uses_privacy_pass_retry == true` only for that mode. There is no separate `PrivacyPassAdapter` type in current source.
- [x] Adapter tests cover mode metadata, static bearer/preshared auth header construction, Privacy Pass retry eligibility, TLS client certificate requirements, and the invariant that no adapter requests proprietary geohash headers.
- [ ] Integration coverage proves the adapter-selected auth mode is applied across request construction, Privacy Pass retry handling, TLS client certificate setup, and relay traffic against a conformant CONNECT-UDP proxy.

## Definition of done

- `cloudflare_mtls` remains only as a legacy mode string for TLS client certificate auth; no adapter emits `sec-ch-geohash`.
- Trait is documented in `native/rust/crates/ripdpi-masque/CONFORMANCE.md`.

## Risks / open questions

- Privacy Pass provider retrieval still uses HTTP client code in the crate. Consider isolating provider-fetch dependencies behind a feature flag if a minimal self-hosted build target needs to avoid them.

## Links

- `native/rust/crates/ripdpi-masque/CONFORMANCE.md`
- audit-cloudflare-only-dependencies (closed task)

## Work log

- 2026-06-05: Trait + `GenericSelfHostedAdapter` fully implemented in `provider_adapter.rs`; adapter used via `adapter_for_config` in `auth.rs`; unit tests pass all four checked criteria. The single open criterion (integration coverage of adapter-selected auth across request construction, Privacy Pass retry, TLS client cert, and relay traffic against a CONNECT-UDP proxy) is explicitly listed as remaining work in `CONFORMANCE.md` Audit Gaps and TDD Plan Slice 4. `udp_session_round_trips_through_conformant_h2_connect_udp_fixture` uses `auth_mode: None` and does not exercise provider-specific auth against the fixture. Status reset from `review` to `todo` because the open criterion is not met.
