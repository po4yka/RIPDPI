## Context

Task ID: RLY-1794300111377171. See proposal.md and the RLY-15 audit.

## Goals / Non-Goals

- Goal: fix all eight findings with separate commits and observable regressions.
- Non-goal: deploy a Google service or weaken certificate pinning.

## Decisions

- Reuse local SOCKS credential conventions; reject NO-AUTH when credentials exist.
- Fail closed for unsupported HTTP preflight and non-HTTP payloads.
- Keep the existing envelope names, require a valid status and preserve body encoding.
- Use app-private storage and export the public CA only. Document the explicit trust step.
- Keep remote Google acceptance separate from local tests.

## Contracts and ownership

Ownership is recorded in the portfolio task before writers start. Auth owns config credentials; trust coordinates additive dataDir changes. HTTP owns the relay data plane. Integrator owns planning, docs and combined checks. No lockfile, baseline or golden updates.

## Risks / Trade-offs

Rejecting fabricated preflight and direct fallback can stop previously unsafe requests. Tests must confirm fail-closed behavior. TLS clients that pin certificates remain unsupported. Preserve stored fields unless a migration is needed.

## Migration Plan

Existing profiles retain endpoint fields. Remove the unused UI control without reusing protobuf numbers. Run cargo tests --locked, affected Kotlin tests, lint, staticAnalysis and just ci-gates. Review before each worker commits; integrate only after combined checks. Roll back code commits as a unit if required; do not delete user credentials or CA data.
