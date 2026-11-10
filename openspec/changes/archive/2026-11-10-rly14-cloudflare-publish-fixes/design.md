## Context

Task RLY-1794300683762457 corrects six audit defects. Native origin handles HTTP/1.1 behind cloudflared; Kotlin validates and starts the helpers. The audit used independent pinned cloudflared source and local loopback probes.

## Goals / Non-Goals

- Goal: Correct existing publish behavior and validation with small regression fixes.
- Non-goal: Add VPN publish, UDP transport, account enrollment, or new relay modes.

## Decisions

- Reuse the existing channel body to keep POST responses alive. Tie the pump to response/session lifetime and preserve limits.
- Remove raw parser failures at the credential boundary. Safe exceptions must not retain the original cause.
- Normalize configured path and query consistently with the client.
- Reject unsupported commands after identity verification and before side effects.
- Normalize IPv6 brackets only for the strict loopback comparison and enforce the port range.
- Apply the same required identity check to both editor modes.

## Contracts and ownership

- Modules: `:core:service`, `:app`; crate: `ripdpi-cloudflare-origin`.
- No schema, JNI signature, persisted field, or dependency changes.
- Ownership and serialized file lanes are recorded in the portfolio task.
- Each audit defect gets a separate Conventional Commit.

## Risks / Trade-offs

- Keeping a response open can retain a body pump: test response drop, session failure, upload EOF, and limits.
- Sanitization can hide useful diagnostics: retain a stable safe error message.
- URL normalization can broaden hosts accidentally: retain a strict loopback allowlist.

## Migration Plan

No data migration. Revert a fix commit to roll back its behavior. Run origin tests and clippy with --locked; run focused Kotlin tests with -Pripdpi.skipNativeBuild=true. Run just ci-gates, architecture health, locked Cargo metadata, independent review, and local pinned cloudflared regression before integration. Public edge, physical device, root, and real-secret probes are outside this local change's acceptance.
