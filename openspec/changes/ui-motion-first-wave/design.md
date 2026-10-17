## Context

See proposal.md and specs/ui-action-feedback/spec.md. Current motion tokens provide 120 ms quick and 220 ms state transitions. A spring remains finite when animations are disabled. The actuator already owns an elapsed-time confirmation deadline. Events use stable keys and preserve the reader position.

## Goals / Non-Goals

- Goal: make deadlines, completed results, and recent events visible with short, truthful feedback.
- Non-goal: add network operations, invented progress, new navigation, or decorative infinite motion.

## Decisions

- Return a finite animation specification that snaps when motion is disabled. Keep existing reduced-motion spring behavior without bounce.
- Derive countdown seconds and progress from the existing elapsed-time deadline. Motion preferences never shorten the confirmation window. Keep cancellation available during connection.
- Key diagnostics transitions by semantic outcome identity, not the entire changing report. Preserve one authoritative result and its actual failure or cancellation state.
- Show Jump to latest outside the scrolling event content while away from the top. Keep stable list keys and filters; use immediate scrolling in static or reduced-motion mode.
- Use existing buttons, surfaces, colors, motion tokens, and automation selectors. The integration owner writes all locale strings and shared tags.

## Contracts and ownership

- Only :app Kotlin UI and tests change. No Rust crates, JNI, protobuf, network, persistence, or public contracts change.
- Ownership is recorded in docs/tasks/issues/ui-motion-first-wave.md before parallel writers start. Shared locale sets, test tags, and planning records have one writer.
- Use RDS motion-reduced-motion.html, vpn-confirm-disconnect.html, motion-connection-states.html, and motion-page-transitions.html. Jump to latest has no exact RDS sheet and uses existing action roles.

## Risks / Trade-offs

- Timer drift: use elapsed time rather than animation duration and test expiry and late confirmation.
- Replayed completion: isolate semantic transition keys and test intermediate telemetry updates and saved results.
- Unwanted scrolling: preserve stable item keys and require an explicit recent-event action away from the top.
- Layout or accessibility regressions: test static/reduced motion, font scale 2.0, dark theme, and RTL on the leased emulator.

## Migration Plan

No migration is required. Revert the scoped commits to roll back. Validate focused motion, actuator, diagnostics, and history behavior tests, all theme tests, :app:lintGithubFullDebug, :core:service:lintDebug, staticAnalysis, architecture health, and locked Cargo metadata. Build the real GithubFull debug APK and inspect the four behaviors on an exclusively leased emulator. Rebase onto origin/main, repeat combined checks as needed, integrate with ff-only, and push only the authorized main branch.
