## Context

The linked proposal records three audit concerns. Current planning is stateless, uses fixed synthetic CIDs, and collects all requested decoys. Generic UDP receive bookkeeping accepts any target response.

## Goals / Non-Goals

- Goal: bound gap generation, separate fresh series contexts, and prevent decoy-only response evidence from completing real traffic.
- Non-goal: change real client packet numbers, add a QUIC endpoint, change adjacent tactics, expand automatic candidates, or require root.

## Decisions

- Reuse the existing 64-packet ceiling for DPI-23 per-step and aggregate chain admission. Runtime keeps a remaining 64-gap-packet budget after activation checks. Normalized datagrams are limited to 65535 bytes, which bounds gap packet contents to about 4 MiB. Bound invalid direct-model counts before generation.
- Build one browser seed for each gap series. Fill fresh DCID and SCID once with the existing fallible OS CSPRNG. Preserve one context within the series. Entropy failure emits no decoys and preserves original forwarding. Keep the shared browser helper and adjacent tactics unchanged.
- Reproduce response attribution with real loopback sockets. Retain necessary real Initial identifiers in flow-local state. Gate success, session observation and migration for unrelated long headers; preserve transparent response delivery.
- Correlated VN mirrors both request identifiers. Retry and protected server long headers can choose a new server SCID. Short headers and generic UDP retain existing behavior. Use a private bounds-checked invariant-header reader in the session owner; no dependency or public API addition.
- Keep one reviewed fix commit for each confirmed finding. Parent owns planning and closure commits.

## Contracts and ownership

- F1: core:data:model validation; ripdpi-proxy-config admission; ripdpi-desync planner budget and owning tests.
- F2: gap packet-family builder and dedicated integration tests.
- H1: ripdpi-proxy-runtime UDP session/receive bookkeeping and owning loopback tests.
- Parent: task/OpenSpec artifacts, combined documentation, wire oracle, required gates, review, main integration and push.
- No dependency locks, serialized schemas, locale resources, goldens or baselines change. Config representation stays unchanged; counts above the new bound are rejected.
- Gates: owning Rust tests with --locked --jobs 4, core:data:model tests, affected-crate Clippy/format, just ci-gates, architecture health and locked cargo metadata. Builds use build-gate and worktree-owned targets. TShark independently decodes actual generated and loopback-received payloads.

## Risks / Trade-offs

- Oversized saved chains fail admission. Test boundary and rejection reason.
- Fresh contexts change synthetic wire bytes. Test relationships and decoded bytes, not fixed random-value goldens.
- CID correlation does not authenticate peers. Cover VN, Retry, server long headers, short headers and generic UDP without claiming a full handshake.
- Host acceptance does not establish Android/VPN or external compatibility; those scopes are separate.

## Migration Plan

No storage migration is required. Integrate reviewed commits after local gates. Rollback uses ordinary revert commits. No history rewrite, force push or production rollout is needed.
