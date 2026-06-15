---
title: Add mid-handshake-freeze classification precision and retry-storm backoff guard to failure classifier and retry policy
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: epic-transport-obfuscation-research
blocks: []
blocked_by: []
created: 2026-06-15
updated: 2026-06-15
source_wiki_pages:
  - "behavioral-freeze-client-device-view-2026"
  - "gfw-residual-censorship-timers"
  - "tspu-vpn-detection-layers"
linked_task: null
---

## Motivation

`behavioral-freeze-client-device-view-2026` (sourced from Habr 1047442) documents a RU behavioral-freeze pattern distinct from both IP-block and volume-threshold RST: the TCP handshake completes, ClientHello is sent, then the connection enters a sustained silent-drop blackhole with no ServerHello and no RST. `ripdpi-failure-classifier` already enumerates `ConnectionFreeze` as a `BlockSignal` variant, but no task addresses whether the classifier precisely distinguishes this mid-handshake subcase (post-ClientHello silence) from other silent-drop events, nor whether the retry-penalty path in `ripdpi-runtime-policy` avoids self-extending a freeze window through aggressive retry. `gfw-residual-censorship-timers` corroborates the self-extension dynamic but flags the ~120s figure as a single-source reconstruction matching a China/GFW timer — not a measured RU constant, and not to be hardcoded.

## Proposed change

A two-part spike scoped to `ripdpi-failure-classifier` and `ripdpi-runtime-policy`. No production code merges without a follow-on implementation task.

**Part 1 — classification precision (`ripdpi-failure-classifier`).** Audit `signal_types.rs` (and classifier entry points in `ripdpi-diagnostics-classification`) to determine whether `ConnectionFreeze` carries enough wire-observable attributes to distinguish mid-handshake-freeze (TCP-established → ClientHello-sent → silence, no RST, no TLS alert) from pre-handshake silent-drop (SYN, no SYN-ACK) and post-data silent-drop (data flowing, then silence). If the variant conflates these subtypes, propose a minimal, non-breaking attribute extension (e.g. a `handshake_phase` field) that preserves the `(transport_class, network_scope_hash, BlockSignal, quorum)` matrix schema established in `investigate-rkn-unannounced-protocol-class-signatures`.

**Part 2 — retry-storm guard (`ripdpi-runtime-policy`).** Audit `build_retry_penalties` and its `PolicyPort` / `PolicySelectionPort` callers to evaluate whether a `ConnectionFreeze` signal triggers an immediate retry against the same `(dst_IP, SNI)` tuple. Document whether the existing penalty mechanism suppresses this or whether an explicit guard is required. Guard objective: on a freeze-class signal for a given `(dst_IP, SNI)`, avoid re-attempting that tuple until a configurable, observable cooldown elapses, and avoid changing the transport fingerprint mid-freeze (fingerprint flips may extend the block, per the source). The cooldown value must be configurable and default-unset — never derived from the China/GFW timer figure.

Spike deliverable: a written design note covering the classification gap (if any), the proposed attribute extension, and the retry-guard seam recommendation, with reasoning for or against each option and a list of any implementation follow-ons to file.

## Acceptance criteria

- [ ] Audit of `ripdpi-failure-classifier/src/signal_types.rs` documents whether `ConnectionFreeze` distinguishes mid-handshake-freeze from pre-handshake and post-data silent-drop subtypes.
- [ ] If a classification gap is found, a minimal attribute extension is proposed that does not break the existing `(transport_class, network_scope_hash, BlockSignal, quorum)` matrix schema.
- [ ] Audit of `build_retry_penalties` and `PolicyPort` / `PolicySelectionPort` callers documents whether a `ConnectionFreeze` signal already suppresses same-tuple retries or whether a guard is absent.
- [ ] Design note produced covering both findings with a seam recommendation; no timer constant derived from the China/GFW figure is embedded.
- [ ] Design note states which implementation tasks (if any) should be filed as follow-ons.

## Risks / open questions

- The RU-specific freeze duration is a single-source reconstruction; the design must treat it as an observable, configurable parameter, never a hardcoded constant.
- The `ConnectionFreeze` variant may already carry enough attributes — the spike may conclude no extension is needed.
- `build_retry_penalties` is slated for trait decomposition in `split-policyport-trait-selection-learning`; the spike should note whether the guard seam changes depending on which decomposed trait surface it lands on.
- Mid-handshake-freeze block state appears to bind to `(src_IP, dst_SNI)` per the source; confirm the current penalty store is keyed on a compatible tuple before recommending a seam. (Coordinate with `per-exit-ip-tls-cap-with-mux-preference-in-relay-core` and `audit-relay-mux-default-nested-handshake-conformance`, which also touch `ripdpi-relay-core/src/backend/pool.rs`.)

## References

- `behavioral-freeze-client-device-view-2026` — device-side packet-walk + block-type classification table.
- `gfw-residual-censorship-timers` — China/GFW residual timers (distinct scope; not a measured RU constant).
- `tspu-vpn-detection-layers` — RU detection-layer taxonomy.
- `investigate-rkn-unannounced-protocol-class-signatures` — establishes the `BlockSignal` matrix schema; `ConnectionFreeze` enumerated in `signal_types.rs`.
- `split-policyport-trait-selection-learning` — `build_retry_penalties` decomposition context.
