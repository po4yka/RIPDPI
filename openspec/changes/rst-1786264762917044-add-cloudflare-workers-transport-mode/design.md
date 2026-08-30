## Context

Portfolio task `RST-1786264762917044` owns this change. This is an optional route for the existing Telegram RFC 6455 WebSocket tunnel. When configured, the outer connection terminates at the operator's Worker hostname and the Worker forwards the framed stream to the canonical Telegram WebSocket gateway for the detected data center.

## Goals / Non-Goals

- Goal: deliver `Add optional Cloudflare Workers transport mode` with observable evidence.
- Goal: preserve the task's declared module, contract, and validation boundaries.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Treat the portfolio task as the source of priority, ownership, and lifecycle state.
- Treat this OpenSpec change as the normative behavior delta for the `rust-native` area.
- Keep implementation details in the affected modules and record exact verification evidence separately.
- Persist only the Worker URL and a credential reference in AppSettings. Store the bearer in a dedicated Android-Keystore-backed store, inject it only into the per-session runtime DTO, redact it from debug output, and strip it before remembered-policy or signature serialization.
- Validate the Worker route before DNS or socket I/O: only `https`/`wss`, a non-empty hostname, no userinfo or fragment, a non-empty bearer without control characters, and no simultaneous fake-SNI mode.
- Resolve and dial the Worker endpoint, verify TLS against the Worker hostname, and use that hostname for SNI, the WebSocket URI, and `Host`. Build `X-Ripdpi-Upstream` internally from the detected Telegram data center; it is never an arbitrary user-provided target.
- Preserve the direct `kws{dc}.web.telegram.org/apiws` path byte-for-byte when no Worker route is configured. The Worker route is opt-in and never participates in default bootstrap or mandatory fallback selection.
- The reference Worker fails closed on bearer mismatch, non-WebSocket requests, and any upstream outside the exact Telegram WebSocket allowlist. It must not expose an arbitrary TCP or URL relay.
- Replace the generated HTTP/2 mock criterion with a TLS WebSocket edge loopback. The production client uses RFC 6455 HTTP/1.1 Upgrade; an HTTP/2 fixture cannot exercise that protocol path without introducing a separate RFC 8441 transport.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all mdtask steps and evidence categories to be resolved.
- Parallel work could collide in shared contracts. → Follow the serialized-file and worktree rules in `AGENTS.md`.
- A persisted or logged bearer would turn local policy/config artifacts into credential leaks. → Keep the secret in Keystore storage, use redacted wrappers, and remove it from durable policy JSON.
- An unrestricted upstream header would turn the Worker into an open relay. → Generate a canonical Telegram URL in the client and enforce the same closed allowlist in the Worker.
- Fake SNI would violate the Worker-host identity contract and disable certificate verification. → Reject the combination before network I/O.

## Migration Plan

Implement in an isolated worktree, run the named local and hosted gates, then archive only through `taskctl` after review.
