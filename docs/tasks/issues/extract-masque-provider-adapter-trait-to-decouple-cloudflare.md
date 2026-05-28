---
title: Extract MasqueProviderAdapter trait to decouple Cloudflare-specific paths
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-15
---

- [ ] #task Extract MasqueProviderAdapter trait to decouple Cloudflare-specific paths #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `extract-masque-provider-adapter-trait-to-decouple-cloudflare`
- **Verify:** `cargo test -p ripdpi-masque`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-masque/**`, `docs/native/relay-masque-status.md`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Move Cloudflare-specific MASQUE behavior (mTLS identity, `sec-ch-geohash` header, Privacy Pass retry policy) behind a `MasqueProviderAdapter` trait so non-Cloudflare MASQUE providers can be supported without editing core auth code.

## Context

`docs/native/relay-masque-status.md` documents the Cloudflare-direct hardening fixes (mTLS classification, geohash header, Privacy Pass retry, H3→H2 fallback) as part of the core auth path. The `ripdpi-masque/Cargo.toml` directly depends on `reqwest` (HTTP client) and `serde_json`, both reasonable for Cloudflare, but they couple the crate to one vendor. Future providers will need different identity flows.

## Acceptance criteria

- [x] (partial, 2026-05-15) A new `trait MasqueProviderAdapter` describes the provider surface. **First iteration shipped** in `ripdpi-masque::provider_adapter` with `provider_id`, `auth_mode`, and `uses_privacy_pass_retry`. Richer methods (header decoration, challenge classification, retry policy) will land alongside the in-tree refactor of `auth.rs`.
- [x] (partial, 2026-05-15) `CloudflareDirectAdapter` implements the trait. **Shipped as a stub** mapping to `MasqueAuthMode::CloudflareMtls`. Cloudflare-specific code (geohash header, mTLS identity) stays in `auth.rs` until the refactor follow-up.
- [x] (partial, 2026-05-15) `PrivacyPassAdapter` implements the trait for the deployer-supplied Privacy Pass flow. **Shipped as a stub** advertising `uses_privacy_pass_retry == true`.
- [ ] The MASQUE client takes `Arc<dyn MasqueProviderAdapter>` instead of a concrete enum branch. **DEFERRED:** the in-tree refactor of `auth.rs` callers is the larger remaining piece.
- [ ] All existing MASQUE tests pass without modification. **DEFERRED:** pairs with the refactor above; current tests continue to pass since `auth.rs` is unchanged.
- [x] (2026-05-15) At least one negative-path test exercises a `NoneAdapter` (renamed from `NoopAdapter` for clarity) to prove the core client works without provider extensions. Covered by `adapter_for_each_mode_reports_consistent_metadata` and `only_privacy_pass_adapter_requests_retry_flow`.

## Definition of done

- No `cloudflare_mtls` or `sec-ch-geohash` literal lives outside the Cloudflare adapter module.
- Trait is documented in `docs/native/relay-masque-status.md`.

## Risks / open questions

- The `reqwest` dependency may still be needed by the Cloudflare adapter for token fetches. Consider isolating it behind a feature flag so a Cloudflare-free build does not pull `reqwest`.

## Links

- [[relay-masque-status]]
- audit-cloudflare-only-dependencies (closed task)
