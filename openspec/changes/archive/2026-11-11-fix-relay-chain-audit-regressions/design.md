## Context

Task RLY-1794335337545384 fixes five confirmed audit findings in existing chain modes. Profile and settings projections currently disagree. AnyTLS entry pools and supplied-transport workers need one cleanup owner. Telemetry currently assigns exit to the last recorded hop.

## Goals / Non-Goals

- Goal: preserve the selected route, reject incomplete routes, drain owned work, and report fixed roles and full trust warnings.
- Non-goal: add UDP, new hop kinds, a schema version, dependencies, or endpoint-alias policy.

## Decisions

- Update the existing profile mapper and validate all selected middle positions in the editor and service. Keep existing localized error keys.
- Reuse AnyTLS owned task joins for pooled and supplied-transport opens. Supplied transports never fall back to direct network dialing. Chain cleanup visits every owned AnyTLS client and retains joins after cancellation.
- Store the configured hop count and report the latest-started connection attempt. An attempt generation prevents older concurrent opens from overwriting that snapshot. Starting an attempt clears stale later-hop progress.
- Evaluate the editor trust warning with the existing full-list helper.

## Contracts and ownership

- Profiles agent: ConnectionRelayPersistenceSupport.kt, ConnectionDraftValidationSupport.kt, UpstreamRelayResolutionSupport.kt and separate persistence/admission tests. Separate commits for F1 and F3.
- Lifecycle agent: ripdpi-anytls session supplied-transport ownership, relay-tls-transports AnyTLS adapter, and only the factory shutdown section of protocols/chain.rs; associated lifecycle tests. One F2 commit.
- Telemetry/trust agent: telemetry.rs, chain builder initialization, chain.rs attempt recording/guards/open_stream (excluding factory shutdown), ConnectionRelayChainUiState.kt and their tests. Separate F4 and F5 commits.
- Coordinator: planning records, shared generated API snapshots if required, combined validation, independent reviews, and integration. No lockfiles, protobuf, locale or golden edits are planned. Agent writers use separate worktrees.
- Existing profile fields and JSON wire names remain unchanged. An additive AnyTLS Rust method can require an API snapshot refresh through its owning generator.

## Risks / Trade-offs

- A shutdown cancellation can lose joins unless ownership stays in the existing client task group; test cancellation and retry.
- Partial startup and concurrent attempts can mix diagnostics; fixed positions and latest-started generation define attribution.
- Native/API changes can affect other callers; run crate tests, affected Kotlin tests, source gates and independent Rust/async review.

## Migration Plan

No schema migration or new dependency is required. Existing stored chains keep their values until edited. Rollback uses ordinary revert commits. Run targeted Kotlin/Rust tests, staticAnalysis, just ci-gates, architecture-health and cargo metadata --locked before integration. Physical device, hosted CI and production evidence are not required for these local regressions and are not claimed.
