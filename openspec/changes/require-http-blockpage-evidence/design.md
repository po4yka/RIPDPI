## Context

The HTTP classifier uses status 403 alone as blockpage evidence.

## Goals / Non-Goals

- Goal: require evidence beyond a generic HTTP refusal.
- Non-goal: redesign classification for other HTTP status codes or HTTP 451 semantics.

## Decisions

- Remove the unconditional status 403 branch. Generic Forbidden or Access Denied body text is not positive blockpage evidence for HTTP 403. Require a specific blocking message or a matching fingerprint. Keep the existing short-body limit and other status behavior.

## Contracts and ownership

- Classifier agent owns `ripdpi-diagnostics-http/src/http/classifier.rs` and `http/tests.rs`, plus this task and change.
- No Kotlin, wire, lockfile, golden, or baseline edits. Root owns combined board generation and integration.

## Risks / Trade-offs

- A real empty HTTP 403 blockpage remains an ordinary refusal because attribution is not established.

## Migration Plan

No migration. Revert this atomic fix to roll back. Run a failing regression, the crate suite with Cargo locked, Clippy, and combined staticAnalysis before integration.
