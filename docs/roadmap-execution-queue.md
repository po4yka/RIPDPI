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
| 11.6 | Complete. Strategy-probe recommendations now flag proxy-mode/browser-native TLS or ECH suppression explicitly, and the notice is surfaced in diagnostics UI plus archive export | `:core:diagnostics:testDebugUnitTest` workflow/archive tests; `:app:testDebugUnitTest` diagnostics UI tests |
| 11.7 | Complete. The remaining lab-only fake families now use explicit Chrome-family fake ClientHello profiles too, including `adaptive_fake_ttl` and `fake_rst`, eliminating the last generic randomized/original-byte fake lane from the shipped candidate catalog | `cargo test -p ripdpi-monitor adaptive_fake_ttl_ -- --nocapture`; `cargo test -p ripdpi-monitor rooted_and_lab_candidates_are_partitioned_between_quick_and_full_matrix -- --nocapture` |
| 11.8 | Complete. The shared acceptance corpus now carries reviewed CDN/server-stack results for every shipped TLS template profile, and CI validates the committed acceptance report against the corpus, coverage thresholds, and strategy-pack catalog references | `cargo test -p ripdpi-tls-profiles phase11_acceptance_fixture_covers_all_catalog_profiles -- --nocapture`; `python3 -m unittest scripts.tests.test_tls_template_acceptance`; `python3 scripts/ci/check_tls_template_acceptance.py` |
| 11.9 | Keep strategy-pack catalog entries aligned with the shipped template families and evidence | catalog fixtures and rollout metadata checks |

## Completed Phases Removed From Detailed Queue

- Phases `0-10`
- Phases `12-17`
- `Track S`

See [ROADMAP.md](/Users/po4yka/GitRep/RIPDPI/ROADMAP.md) for the current
repository-wide summary.
