## Context

The blocking probe runner uses std worker threads. Its TLS observation already has a typed failure stage, but domain details drop it. Retry currently updates only the outcome.

## Goals / Non-Goals

- Goal: report stage and retry evidence accurately.
- Non-goal: infer ISP intent or change network protocols.

## Decisions

- Carry existing typed stages into extensible probe details. Do not change wire types.
- Require a handshake stage in the diagnosis layer; historical reports with no stage remain uncertain.
- Replace the TLS 1.3 observation after a successful retry, then derive aggregate facts.
- An explicit successful aggregate status prevents fallback to an error from another TLS profile.

## Contracts and ownership

- Own runner domain probe and classification domain diagnosis and observation projection. Other worker owns DNS and HTTP classifiers.
- No new async, unsafe, dependencies, schemas, shared lockfiles, or golden changes.

## Risks / Trade-offs

- Historical reports lack stage evidence; suppress unsupported specific diagnoses.
- A handshake-stage timeout alone does not prove a ClientHello reached the peer. Use handshake wording without claiming the packet was sent.

## Migration Plan

No migration. Revert the scoped commit to roll back. Run locked targeted crate tests and Clippy, then combined static analysis and architecture gates. Local fixtures do not prove carrier behavior.
