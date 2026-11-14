## Context

See proposal.md and the six DPI-30 audit findings.

## Goals / Non-Goals

- Goal: preserve real payloads and meet the existing entropy and TLS mutation contracts.
- Non-goal: add protocols, root features, schema changes, or dependencies.

## Decisions

- Move TCP entropy shaping to final fake emission bytes. Keep ordinary and fallback real payloads unchanged. Resolve adaptive mode in the effective group.
- Validate joint metrics using histograms and bounded padding candidates. Respect existing empty-result behavior when no solution is found.
- Cache TLS prelude state before version mutation. Recognize complete no-SNI ClientHello messages without weakening malformed-input guards.
- Keep each finding in a separate commit. The integrator serializes shared task records.

## Contracts and ownership

- Rust packets owns entropy metrics and record parsing; desync owns TLS prelude; runtime owns TCP emission.
- No Kotlin, protobuf, JNI, lockfile, locale, golden or baseline change is planned. Regenerate an owning API snapshot if an additive packets export requires it.
- Writer ownership is recorded in the linked portfolio task. Each writer uses an isolated worktree.

## Risks / Trade-offs

- Padding a decoy changes its size: test final emission bytes and preserve all genuine offsets.
- Entropy targets may be unreachable: return empty rather than falsely claim both goals.
- TLS parser changes may admit malformed inputs: keep structural and length guards and test failure paths.

## Migration Plan

No stored state migration. Roll back the individual finding commit if needed. Require affected tests, loopback peer evidence, independent review and ci-gates before integration. Device and external-peer coverage remain separately reported.
