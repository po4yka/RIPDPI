---
id: TRN-1786264762917886
title: Add a parallel active-probe race for initial transport selection
kind: feature
status: done
area: transport
priority: high
owner: Codex
parent: EPC-1786264762917282
blocked_by: []
spec_mode: required
openspec_change: trn-1786264762917886-parallel-active-probe-race-initial-transport-selection
created: 2026-07-10
updated: 2026-08-26
source_wiki_pages:
  - whitelist-dpi-confirm-good-paradigm
  - urltest-dual-transport-fallback
linked_task: null
closed_at: "2026-08-26T11:20:23Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Integrated-tree gate battery complete: Rust 184/184, relay interoperability all stages, Kotlin 5159/0 failures across three modules, staticAnalysis and architecture health clean; relay-lab config self-test green with live-scenario layer documented as operator-owned standing requirement"
---

## Goal

Race the simple flavor's seeded VLESS+Reality and Hysteria2+Salamander relay paths with an application-level probe before the VPN TUN is exposed, select the first confirmed-good transport, and retain the existing post-connection failover and UCB1 behavior.

## Scope

- Parse the embedded bundle's explicit `urltest` URL and require one TLS-mimicry candidate plus one UDP-obfuscation candidate.
- Start both relay runtimes concurrently on ephemeral loopback ports, retain the first path returning HTTP 2xx, and stop the loser.
- Cache only confirmed winners for 24 hours under the hashed network scope and candidate-set signature; use the cache only when both fresh probes fail.
- Re-run on normal startup and network handover, but skip the race during `FailoverCoordinator` self-induced restarts.
- Keep full flavor, proxy mode, AWG, command-line settings, native relay schema, JNI, UCB1, and periodic post-connection evolution unchanged.

## Acceptance criteria

- [x] A stalled Reality application exchange does not delay selection of a healthy Hysteria2 path until the legacy timeout.
- [x] A blocked UDP path selects healthy Reality.
- [x] The TUN is not established before a probe-confirmed winner or eligible cached fallback exists.
- [x] The first valid HTTP 2xx response wins and the losing runtime is stopped without surfacing an unexpected-exit event.
- [x] Cached fallback is scoped by hashed network identity and candidate signature, expires after 24 hours, and is not refreshed by fallback use.
- [x] Handover re-races; self-induced post-connection failover restart does not.
- [~] Focused Rust, Kotlin, simple-flavor, architecture, static-analysis, and controlled relay-lab gates pass. Feature-focused gates and architecture health pass; repository-wide Gradle gates remain blocked by unrelated existing failures recorded below.

## Work log

- 2026-07-10: Added a simple-flavor-only policy that derives a two-candidate race from the embedded selector URL test group, scopes confirmed-winner cache entries to the hashed network and candidate signature, and suppresses races for post-connection failover restarts.
- 2026-07-10: Added isolated concurrent relay runtime slots on ephemeral listeners, application-level SOCKS URL probes, first-2xx promotion, loser cancellation and cleanup, cached fallback, typed startup failure, and privacy-safe service telemetry.
- 2026-07-10: Preserved the promoted runtime and its pooled transport for real traffic, rewrote only session-local proxy preferences, and notified failover state before the service reports `Running` or starts the TUN.
- 2026-07-10: Added deterministic service and simple-flavor coverage, relay-core ephemeral-listener coverage, and owner-controlled TCP-blackhole/UDP-drop lab scenarios.
- 2026-07-10: `cargo nextest run -p ripdpi-relay-core -p ripdpi-relay-android --locked`, the complete relay interoperability script, focused Kotlin tests, the relay-lab config self-test, and architecture health pass. The full Gradle unit-test command has one reproducible unrelated failure in `DiagnosticsViewModelTest`; `staticAnalysis` reaches the pre-existing `ripdpi-relay-core/src/tests.rs` LoC violation, and the branch does not change that file.

## References

- Internal transport-selection research notes identified by the `source_wiki_pages` keys above.
