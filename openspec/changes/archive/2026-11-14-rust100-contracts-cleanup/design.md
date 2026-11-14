## Context

See proposal.md. Rust 1.100 and edition 2024 are already pinned.

## Goals / Non-Goals

- Goal: represent impossible results and existing cleanup with standard types.
- Non-goal: change runtime behavior, protocols, dependencies, or security policy.

## Decisions

- Use `type Datagram = !` in the four TCP-only RelaySession implementations. Keep capability checks and Unsupported errors.
- Use `io::Result<!>` in Naiveproxy listener wrappers and origin run; origin serve returns `!`. Adapt main callers without changing successful utility modes.
- Use inline DropGuard for proxy Idle restoration and DNS attempt failure. Dismiss after the existing commit point, with no intervening await.
- Replace only optional root-dot stripping with trim_suffix. Use Vec::from_fn in ScriptPool construction and rotation.

## Contracts and ownership

- One writer owns the affected Rust files; review agents are read-only.
- Public Rust associated types change. Dependent crates must compile. No Kotlin/JNI, wire, persistence, locale, manifest, lockfile, or baseline changes.

## Risks / Trade-offs

- Never results can expose unreachable test statements: adapt complete callers while retaining their failure checks.
- Guard dismissal can change cleanup timing: retain lock release order and cancellation tests.
- trim_suffix removes one dot, not all dots: retain repeated-dot rejection.

## Migration Plan

Apply all source changes together and build dependent crates. Run affected nextest suites, workspace Clippy, rustfmt, cargo metadata --locked, just ci-gates, task validation, and read-only general/async review. Check the Android native compilation path. Revert the source change to roll back; no data migration is needed. Hosted CI and physical-device acceptance are separate from local checks.
