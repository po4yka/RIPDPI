# RIPDPI Roadmap Execution Queue

This queue now tracks only unfinished roadmap work.
Phases 0-10 and 12-17, plus Track S, are complete and were removed from the
detailed slice tables to avoid keeping a second archive of shipped work.

## Current Critical Path

The only remaining roadmap phase is **Phase 11 -- TLS Templates + ECH**.

## Phase 11 -- TLS Templates + ECH

**Status:** PARTIAL (2026-04-18)

**Already landed**

- browser-family TLS template metadata in `ripdpi-tls-profiles`
- explicit desktop and ECH template variants
- ALPN-aware monitor binding from template plans
- Android owned-TLS metadata export through Kotlin surfaces
- OkHttp profile-id honoring
- bundled strategy-pack catalog entries for the shipped template set
- acceptance fixture for the current template families
- coherent Chrome-family production fake profiles

**Remaining execution slices**

| Slice | Scope | Verify gate |
|-------|-------|-------------|
| 11.1 | Complete. `ripdpi-tls-profiles` captures live ClientHello records and validates extension-order family behavior, GREASE, supported groups, key-share layout, and ALPN payloads at the packet level | `cargo test -p ripdpi-tls-profiles` |
| 11.2 | Complete. `ripdpi-tls-profiles` now plans real record boundaries and `ripdpi-desync` ships packet-level rewrite goldens for browser-family templates | `cargo test -p ripdpi-tls-profiles`; `cargo test -p ripdpi-desync phase11_` |
| 11.3 | Complete. The fake TLS path now ships a controlled `google_chrome_hrr` profile and the opportunistic `tlsrec_fake_hrr` probe candidate, with TLS-alert-biased ordering and packet-level tests proving the stripped-`x25519` / preserved-`supported_groups` shape | `cargo test -p ripdpi-packets`; `cargo test -p ripdpi-desync fake`; `cargo test -p ripdpi-monitor tls_alert_reorders_tcp_candidates_away_from_fake_heavy_paths -- --nocapture` |
| 11.4 | Complete. ECH-aware template metadata now carries explicit bootstrap policy, resolver selection, outer-extension handling, and first-flight planning, and the monitor path resolves HTTPS ECH configs through the planned encrypted DNS bootstrap endpoint | `cargo test -p ripdpi-tls-profiles`; `cargo test -p ripdpi-monitor planned_tls_template_profiles_match_phase_eleven_catalog -- --nocapture`; `cargo test -p ripdpi-monitor endpoint_for_resolver_id_uses_known_doh_metadata -- --nocapture` |
| 11.5 | Complete. Android now carries ECH policy, acceptance-corpus linkage, proxy-mode notice metadata, and native template bootstrap/fallback details through the owned-TLS path, with strategy-pack download checks that fail closed on ECH template drift | `:core:service:testDebugUnitTest` for `OwnedTlsClientFactoryTest`; `:core:engine:testDebugUnitTest` for `NativeOwnedTlsHttpFetcherTest`; `:app:testDebugUnitTest` for `StrategyPackNetworkTest` |
| 11.6 | Detect and surface proxy-mode or browser-native suppression of the intended TLS/ECH path | diagnostics/export/UI coverage |
| 11.7 | Finish replacing remaining generic fake families with coherent client-profile families | candidate-pool diff and runtime/probe regressions |
| 11.8 | Build live acceptance coverage across major CDN and server stacks for each shipped template family | acceptance corpus and validation reports |
| 11.9 | Keep strategy-pack catalog entries aligned with the shipped template families and evidence | catalog fixtures and rollout metadata checks |

## Completed Phases Removed From Detailed Queue

- Phases `0-10`
- Phases `12-17`
- `Track S`

See [ROADMAP.md](/Users/po4yka/GitRep/RIPDPI/ROADMAP.md) for the current
repository-wide summary.
