## Context

Task DGN-1788599171554142 fixes eager resolution before attempts. The shared helper serves direct TCP and both UDP transports. DNS waits can consume the full remaining scan deadline.

## Goals / Non-Goals

- Goal: attempt literal IP peers before fallback DNS, while retaining fallback after failure.
- Non-goal: change resolver protocols, scan timeout budgets, TLS names, or protection policy.

## Decisions

- Use one private lazy candidate-group mechanism for the three current callers. Attempt the literal group first; resolve hostname groups only when the caller advances after failure.
- Keep Happy Eyeballs within TCP groups and route-experiment handling within each attempted group. Preserve deduplication and useful terminal errors.
- Test lazy resolution with a controlled resolver and loopback endpoint; do not rely on a public DNS outage or timing luck.
- Retain the runner's pre-audit first-candidate informational resolution. Connection attempts still use all candidates; the `resolved` detail must not trigger eager fallback DNS before them.
- Bound public informational address resolution by the active scan deadline; outside a scan its existing timeout remains unchanged.

## Contracts and ownership

- The isolated diagnostics writer owns ripdpi-diagnostics-transport source and tests. Audit integration owns diagnostics-runner/connectivity/probes/domain.rs, planning, report, task state, and integration. Reviewers are read-only.
- Public APIs, JNI/protobuf/wire schemas, Cargo.lock, locales, and baselines remain unchanged.

## Risks / Trade-offs

- Pinned failures can consume time before DNS fallback; retain the existing deadline and attempt timeout limits.
- Lazy group handling can lose fallback or mask failure stages; test pinned success, pinned failure, hostname-only targets, empty targets, and expired deadlines.

## Migration Plan

No migration is required. Rollback is a normal source revert. Validate the full transport crate and clippy, then the diagnostics caller crates and required hosted CI before main integration.
