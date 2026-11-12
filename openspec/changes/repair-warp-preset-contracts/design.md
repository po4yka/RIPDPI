## Context

The audit reproduced incompatible initiation and response sizes for three
presets. See proposal.md and the linked task for the four repair slices.

## Goals / Non-Goals

- Goal: preserve real WireGuard messages, exact effective enable state, and
  truthful network suggestions.
- Non-goal: add real QUIC/TLS/DNS sessions, change generic AWG, use credentials,
  register WARP accounts, or claim external compatibility.

## Decisions

- Retain built-in J tuples; use standard H and zero S. Keep I slots empty.
- Use an internal WARP caller context for the prelude-only codec. It retains
  I/J but sends backend messages with the same reserved overlay as plain WARP.
  Receive restores reserved bytes before the backend verifies MAC/cookies.
  Generic AWG codecs and full custom AWG envelopes keep their current behavior.
- An explicit disabled flag controls imitation/custom. Off and random are
  always disabled. UI selection explicitly enables imitation/custom and saves
  the resolved flag. Backup and native-preference writes use the same state.
  Raw admission precedes canonicalization so legacy profiles cannot bypass it.
- Reuse the existing localized recommendation title for the hint. Use
  NetworkHandoverMonitor for live network presentation, separately from costly
  routing catalog snapshots. Do not add a polling loop or telephony listener.

## Contracts and ownership

- Native coordinator owns F1: WarpPayloadGenCatalog.kt, its asset, warp-core
  codec/factories/runtime/probe and their tests. Generic AWG behavior is protected
  by existing peer/cookie regressions. No public JSON/JNI field is added.
- Settings worker owns F2: WarpSettings.kt, AppSettingsJsonWarpMappers.kt,
  WarpSettingsMutationWriter.kt, native preferences settings writer, and their
  existing tests. It does not edit catalog tuples or native Rust.
- Hint worker owns F3 and H2 in separate commits: WarpSection.kt, settings
  dependency/assembler/network presentation callers and their tests. It owns
  all locale resources if required, but an existing translated key is preferred.
- Coordinator alone owns portfolio, OpenSpec, generated board and combined
  generated API/architecture outputs. Every writer has a separate worktree.
- No Cargo.lock, dependency version, proto, golden, or quality baseline changes.

## Risks / Trade-offs

- Public WARP server behavior is not observed. Test nonzero synthetic reserved
  bytes and cookie retry against a raw peer; keep C/V/S/E distinct.
- Fixed preset names retain their IDs but lose unsafe real packet H/S changes.
  Document this correction; custom remains explicit.
- Observe only signals already supported by NetworkHandoverMonitor. Do not claim
  a telephony-only update when no physical-network event is emitted.
- Use independent read-only review for behavior/Rust changes. Never bless goldens
  or extend quality baselines to pass checks.

## Migration Plan

No schema migration is needed. Resolve existing named presets with the corrected
catalog and canonicalize enable flags at configuration boundaries. Keep legacy
wire-profile rejection. Roll back the corresponding atomic fix if its acceptance
fails. Run focused Kotlin/native tests, staticAnalysis, ci-gates, architecture
health, locked metadata, task validation, and a supported UI render before main
integration. Fetch and rebase on origin/main, rerun affected gates after new input,
then fast-forward main and push as the user requested.
