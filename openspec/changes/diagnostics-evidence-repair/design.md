## Context

The audit found eight inconsistent evidence paths. The existing engine already records typed outcomes, completion, and network scope. The fixes use those facts instead of new scoring layers.

## Goals / Non-Goals

- Goal: correct configuration composition, verification, aggregation, comparison, and transfer interpretation with regression tests.
- Non-goal: add a remote measurement service, new production dependencies, or claim device/network acceptance from unit tests.

## Decisions

- Keep candidate measurement configurations isolated. Compose final TCP and QUIC settings explicitly from the actual winners.
- Make lane winner IDs and labels nullable as pairs. Require at least one real successful winner. Keep the unmeasured lane unchanged when applying a partial recommendation. Do not fabricate baseline success.
- Use a new engine schema version and update all consumers together. Preserve absent winners as null in exports. A partial result is useful evidence but does not bypass confidence, control, capability, or completion gates.
- Home verification uses typed outcome policy and report completion/network scope. It does not infer success from outcome substrings.
- Comparison uses normalized domain outcomes and component-specific service evidence. Unsupported TLS versions and untested components do not imply selective blocking.
- The DPI suite treats unavailable and unknown evidence explicitly. Unreachable endpoints do not establish DPI interference.
- Alternative-SNI success requires completed transfer evidence. TCP errors remain observations unless independent evidence establishes their cause.
- Use transfer targets whose advertised body supports the required window. Preserve short complete bodies as insufficient evidence. Keep valid throughput comparisons when unrelated domains fail.

## Contracts and ownership

The portfolio Ownership section assigns isolated writer worktrees. The strategy writer alone owns recommendation wire models, schema constants, field manifests, and associated fixtures. The primary writer owns catalog generation, locales, Home application/verification, and these artifacts. No Cargo dependency or lockfile changes are planned.

## Risks / Trade-offs

- A nullable lane affects native selection, Kotlin validation, settings application, UI, and redacted export. Tests cover both single-lane directions and the two-lane case.
- Local endpoints cannot establish public-network accuracy. Regression tests prove classification and composition; device and external-network acceptance remain separate evidence.
- Golden contract updates require scoped approval after expected/actual differences are available. No fixture is changed merely to pass a check.

## Migration Plan

Ship the Rust and Kotlin schema changes together. No old-schema adapter or fallback is added. Revert the coordinated change to roll back; there is no database migration or deployment.

Validate regression tests before and after fixes, affected Rust crate suites, Kotlin diagnostics and affected app tests, formatting/lint, contract gates, Cargo metadata, architecture health, and `just ci-gates`. Run independent review before the final implementation commit. Native artifacts and live device behavior require separate evidence if they cannot be built or exercised locally.
