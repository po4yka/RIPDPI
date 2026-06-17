---
title: Add mid-handshake-freeze classification precision and retry-storm backoff guard to failure classifier and retry policy
type: task
status: done
area: diagnostics
priority: medium
owner: unassigned
parent: epic-transport-obfuscation-research
blocks: []
blocked_by: []
created: 2026-06-15
updated: 2026-06-17
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

- [x] Audit of the classifier documents whether `ConnectionFreeze` distinguishes mid-handshake-freeze from pre-handshake and post-data silent-drop subtypes. (Variants live in `types.rs` / `block_detection/signal_types.rs`, not `signal_types.rs`. See **Spike findings → Part 1**.)
- [x] A minimal, non-breaking attribute extension is proposed (`FreezePhase` as a separate observation attribute, never folded into `BlockSignal`) that preserves the `(transport_class, network_scope_hash, BlockSignal, quorum)` matrix schema. See **Part 1 → Proposal**.
- [x] Audit of `build_retry_penalties` / `PolicyPort` documents that a freeze-specific same-tuple guard is **absent** — and that the existing penalty path actively *diversifies* (flips fingerprint), the opposite of the desired behavior. See **Part 2**.
- [x] Design note produced with a seam recommendation; **no timer constant derived from the China/GFW figure is embedded** (cooldown proposed as `Option<u64>`, default `None`).
- [x] Design note states the implementation follow-ons to file. See **Follow-on tasks**.

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

## Spike findings (2026-06-17)

All facts read from `native/rust/crates` at HEAD. The task's file names are
slightly off: `FailureClass` lives in `ripdpi-failure-classifier/src/types.rs`,
the matrix `BlockSignal` in `.../src/block_detection/signal_types.rs`, and the
freeze classifier in `.../src/connection_freeze.rs`. `build_retry_penalties` is a
`PolicyPort` method in `ripdpi-runtime-decision-ports/src/policy.rs`, implemented
in `ripdpi-runtime-policy`.

### Part 1 — classification precision (gap: yes, two subtypes conflated)

`classify_connection_freeze(bytes_received, stall_windows, window_ms)`
(`connection_freeze.rs:3`) produces a `ClassifiedFailure` with
`class = ConnectionFreeze`, a hardcoded `stage = FailureStage::Relay`, and the
wire counters recorded only as **free-form string tags**
(`bytesReceived=…`, `stallWindows=…`) on `evidence.tags: Vec<String>`.

The three subtypes the task asks about sit as follows today:

| Subtype | Wire signature | Current classification |
|---|---|---|
| pre-handshake silent-drop | SYN, no SYN-ACK | **already distinct** — `FailureClass::SilentDrop` (`transport.rs`), not `ConnectionFreeze` |
| mid-handshake-freeze | TCP est → ClientHello sent → no ServerHello, no RST | `ConnectionFreeze` with `bytesReceived = 0` |
| post-data silent-drop | data flowed → silence | `ConnectionFreeze` with `bytesReceived > 0` |

**Gap:** mid-handshake-freeze and post-data freeze are **conflated** into one
`ConnectionFreeze`, separable only by the stringly-typed `bytesReceived` tag —
and that distinction is **lost at the matrix boundary**: `BlockSignal`
(`signal_types.rs:5`) is a bare C-like enum, so `signal_mapping.rs` maps both to
`BlockSignal::ConnectionFreeze` with no phase. The policy/diagnostic layers never
see the phase. Pre-handshake is fine (separate class).

**Proposal (minimal, non-breaking):**

1. Add `FreezePhase { MidHandshake, PostData, Unknown }` (snake_case serde) to
   `ripdpi-failure-classifier`.
2. Thread an explicit wire-observable input into `classify_connection_freeze`
   (e.g. `server_hello_seen: bool`, or keep deriving `MidHandshake` from
   `bytes_received == 0` *and* a "no app bytes before stall" flag) so phase is
   computed from observation, not guessed.
3. Surface it as an **optional typed field** on `ClassifiedFailure`:
   `#[serde(default, skip_serializing_if = "Option::is_none")] pub freeze_phase:
   Option<FreezePhase>`. `serde(default)` + camelCase `freezePhase` keeps every
   existing JSON payload and the round-trip tests valid (non-breaking).
4. **Hard constraint — do NOT add phase to `BlockSignal`.** That enum is the
   third element of the `(transport_class, network_scope_hash, BlockSignal,
   quorum)` matrix key; widening it would change the key cardinality and break
   the schema from `investigate-rkn-unannounced-protocol-class-signatures`. Phase
   rides **alongside** `BlockSignal` as a refinement attribute consumed only by
   the policy/diagnostic layer, never inside the matrix key.

### Part 2 — retry-storm guard (gap: absent, and the default behavior is inverted)

Penalty store (`autolearn/mod.rs:55`): `learned_hosts_by_scope:
BTreeMap<network_scope_key, BTreeMap<host /*=SNI*/, LearnedHostRecord>>`, with
per-group `penalty_until_ms`. So the persisted key is
**`(network_scope_hash, SNI)`**, *not* `(dst_IP, SNI)`. The source binds the
freeze to `(src_IP, dst_SNI)`; `network_scope_key` already encodes the src
**network** identity (per `network-fingerprint-privacy.md`) and `host` = dst SNI,
so the existing keying is **broadly compatible** with the source's binding
without introducing `dst_IP` (which is available ephemerally as
`RouteAdvance.dest` but is deliberately not persisted).

What a `ConnectionFreeze` does today:

- `note_host_failure` → sets per-`(host, group)` `penalty_until_ms` → selection
  **skips that group** → **diversifies to another strategy group = transport
  fingerprint flip**.
- `note_block_signal(ConnectionFreeze)` (`autolearn/mod.rs:120`) → 2-hit
  confirmation within `BLOCK_CONFIRMATION_WINDOW_MS` → marks the **whole host
  blocked**.
- `build_retry_penalties` → `RetrySelectionPenalty { same_signature_cooldown_ms,
  family_cooldown_ms, diversification_rank }` → diversification-oriented.

**Finding:** there is **no** freeze-specific guard that holds the same tuple
without re-attempt. Worse, the dominant path (`diversification_rank`) does the
**opposite** of the task's objective — it pushes a fingerprint flip on a freeze,
which the source says can *extend* the block. `same_signature_cooldown_ms` is the
inverse lever but is generic and dominated by diversification.

**Proposed guard (default-unset, no hardcoded timer):**

- Config: add `host_autolearn.freeze_cooldown_secs: Option<u64>` to
  `ripdpi-config`, default `None`. `None` → guard disabled → behavior identical
  to today. Configurable; **never seeded from the 120s GFW figure**.
- Write seam: in `note_block_signal`, on a confirmed `BlockSignal::ConnectionFreeze`
  (refined by `FreezePhase::MidHandshake` once Part 1 lands) and when
  `freeze_cooldown_secs` is `Some`, stamp a `freeze_cooldown_until_ms` on the
  `LearnedHostRecord` (already scoped by `network_scope` ≈ src network).
- Read/enforce seam: in `select_initial` / `select_next` / `advance_route`, while
  `freeze_cooldown_until_ms > now` for `(network_scope, SNI)`, (a) do **not**
  re-attempt that tuple and (b) **suppress the diversification penalty** so the
  transport fingerprint is held, not flipped, for the cooldown window.
- Decomposition coordination (`split-policyport-trait-selection-learning`): the
  cooldown **write** belongs on the learning trait surface (`note_block_signal`),
  the **read/suppress** on the selection trait surface (the penalty consumer),
  with the timestamp on the shared `LearnedHostRecord`. The guard straddles both
  decomposed traits; landing it on the shared record keeps the seam stable across
  the split.

### Follow-on tasks to file

1. **`implement-freeze-phase-classifier-attribute`** (`ripdpi-failure-classifier`)
   — `FreezePhase` enum + optional `freeze_phase` field on `ClassifiedFailure`,
   derived from a wire-observable input; `BlockSignal`/matrix unchanged;
   serde-default non-breaking; tests for mid-handshake (`bytesReceived = 0`) vs
   post-data.
2. **`implement-freeze-cooldown-retry-guard`** (`ripdpi-runtime-policy` +
   `ripdpi-config`) — `freeze_cooldown_secs: Option<u64>` (default `None`); stamp
   cooldown on confirmed `ConnectionFreeze`; during cooldown suppress re-attempt
   **and** diversification on `(network_scope, SNI)`; coordinate the seam with
   `split-policyport-trait-selection-learning`; tests including default-unset =
   exact no-op.

### Scope limitation (per the source)

The ~120s figure in `gfw-residual-censorship-timers` is a single-source
China/GFW reconstruction, **not a measured RU constant**. Both proposals treat
the cooldown strictly as an operator-configurable, default-unset observable —
never a hardcoded default — per the task's risk note.
