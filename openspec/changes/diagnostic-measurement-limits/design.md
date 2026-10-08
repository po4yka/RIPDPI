## Context

Diagnosis already carries nullable controlValidated. QUIC connectivity checks initial packets, and Android provides interface MTU only.

## Goals / Non-Goals

- Goal: Preserve evidence and display limits in existing report surfaces.
- Non-goal: Add active HTTP/3 or PMTU probes or attribute failures to an operator.

## Decisions

- Derive limits from existing diagnosis and probe codes. Unknown codes get no invented source.
- Keep control validation tri-state. Use localized labels in UI and stable text in exports.
- Use existing card typography and spacing. No matching new RDS layout is needed; this is extra text in existing cards.

## Contracts and ownership

- Root owns summary renderer/projector, app diagnosis presentation, and all locale resources.
- Metadata worker owns snapshot availability models and mapper; root consumes its getters.
- Home and controls workers own separate orchestration and classification files in isolated worktrees.
- No JNI, protobuf, Rust wire, or persistent schema changes in this slice. Redacted export validation and captive portal flags become nullable to represent unavailable observations; existing Boolean values remain accepted.

## Risks / Trade-offs

- Additional text can increase card height. Use wrapping text and existing styles.
- Legacy snapshots cannot prove availability. Render unknown.

## Migration Plan

No migration is required. Revert the scoped commit to restore presentation. Validate diagnostics and app unit tests, Android lint locale parity, staticAnalysis, and architecture checks before main integration.
