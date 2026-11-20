## Context

Portfolio task `TRN-1786264762917575` owns this change. DNS-Morph (Ailabouni-Dunkelman-Bitan, CSCML 2021) splits the transport model: the handshake uses DNS port 53 while the data plane uses any underlying transport. This provides a distinct bootstrap surface whose behavior depends on middlebox port-53 handling and active L7 fingerprinting. No mature Android-targeting fork exists yet. The spike validates whether the bootstrap shim is buildable on Android and whether controlled external clients can complete the roughly 80-query type-A handshake on representative resolver paths

## Goals / Non-Goals

- Goal: deliver `Spike: DNS-Morph bootstrap as fallback bootstrap channel` with observable evidence.
- Goal: preserve the task's declared module, contract, and validation boundaries.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Purpose: reserve delivery of the signed subscription body. The bridge never carries VPN traffic; the delivered bundle then selects the data plane as an HTTPS refresh would.
- Authentication: Ed25519 over a domain label, a per-fetch client nonce and the payload. The nonce prevents replay of a recorded reply; the pinned key comes only from an accepted bundle.
- Wire: one TXT answer per 180-byte envelope chunk, stop-and-wait, 512-byte datagram cap, 64 KiB payload cap, three 2 s attempts per chunk inside a 90 s budget.
- Placement: blocking `std` UDP client (no async runtime), protected through `ripdpi-runtime-platform` before the first send. The Kotlin refresh coordinator owns the fallback order; the channel never masks the HTTPS failure.
- No user-visible strings: the channel shows through the existing refresh status.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all mdtask steps and evidence categories to be resolved.
- Parallel work could collide in shared contracts. → Follow the serialized-file and worktree rules in `AGENTS.md`.

## Migration Plan

Implement in an isolated worktree, run the named local and hosted gates, then archive only through `taskctl` after review.
