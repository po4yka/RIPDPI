## Context

The audit identifies six reachable defects in the native Tor proxy path.

## Goals / Non-Goals

- Goal: restore the existing TCP bridge+PT contract with regression evidence.
- Non-goal: add UDP, VPN socket protection or new Tor providers.

## Decisions

- Preserve unresolved names only on the Tor route. Maintain IP policy safety and fail closed where routing needs an address.
- Carry the Tor first-dial budget to the outer proxy.
- Fix pinned Arti cancellation and subprocess ownership at their owning layer.
- Preserve legacy ASCII profile components up to 255 bytes when they contain letters, digits, dots or hyphens and are not dot segments. Underscores are ambiguous outputs of the old sanitizer, so they also use bounded collision-resistant keys in a separate root. Hash raw UTF-16 code units so replacement encoding cannot merge malformed IDs. Do not copy or delete ambiguous historical state; do not overwrite another profile state.
- Validate required bridge fields before save with existing error resources.

## Contracts and ownership

Ownership is recorded in the portfolio task. The lifecycle writer owns Cargo.toml and Cargo.lock. The profile writer owns locale files if required. Other writers must not edit these serialized lanes. No JNI or protobuf change is planned.

## Risks / Trade-offs

- Name preservation can bypass IP filters: retain fail-closed policy behavior and test it.
- Migration can mix guards: only reuse state with established ownership.
- Child shutdown can hang: bound exit and reap ownership independently of stdout.

## Migration Plan

Use stable new directory keys for unsafe IDs and IDs with underscores; retain only structurally unambiguous legacy paths. Historical collisions cannot be assigned retroactively; retain their data without copying it to newly separated profiles. Keep source dependency versions pinned. Revert the individual finding commit if a regression appears; do not delete persisted guards during rollback.

Validation: targeted Rust tests with --locked, Kotlin owning module tests, independent PR/async review, just ci-gates, architecture health and locked Cargo metadata before main integration. Device and external Tor interoperability remain separate evidence categories.
