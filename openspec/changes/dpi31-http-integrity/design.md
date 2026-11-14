## Context

The DPI-31 audit found request data loss and a missing mutation outcome. See proposal.md. This change links to portfolio task `ENG-1794677657331523`.

## Goals / Non-Goals

- Goal: preserve significant User-Agent and body bytes, decline repeated prefix insertion, and expose mutation outcome separately from chain execution.
- Non-goal: improve bypass effectiveness, add protocols, change activation or privilege gates, impose a universal peer header limit, or add dependencies.

## Decisions

- Method EOL may reclaim only trailing SP or HTAB bytes from User-Agent. It leaves the request unchanged if two disposable bytes do not exist. This keeps the current length constraint without deleting significant data.
- Method EOL leaves a request with a nonzero method-start offset unchanged. The current parser prefix contract stays intact.
- HTTP mutation status uses an additive optional boolean: absent means not configured, false means configured but unchanged or declined, and true means changed bytes. It is separate from chain disposition. Keep ActivationSkipped and Applied semantics for the TCP chain. In particular, do not mark a parser-only result Applied when the chain receipt requires a family and planned steps.
- Carry the independent outcome from desync planner through native execution receipts, `ripdpi-proxy-runtime`, `ripdpi-runtime-api`, `ripdpi-monitor-proxy-runtime`, `ripdpi-diagnostics-contracts`, and the Kotlin diagnostics evidence model. Preserve existing defaults when additive serialized fields are absent. No raw request data enters a receipt or log.
- Do not turn the inspection limit into an output limit: no such product or peer contract was established by H2.
- Keep each confirmed issue in a separate implementation commit. The integrator serializes shared artifacts and API snapshots.

## Contracts and ownership

- The packet worker owns `ripdpi-packets` HTTP mutation and its owning tests, in an isolated worktree.
- The receipt worker owns `ripdpi-desync`, `ripdpi-desync-runtime`, `ripdpi-proxy-runtime`, `ripdpi-runtime-api`, `ripdpi-monitor-proxy-runtime`, `ripdpi-diagnostics-contracts`, and `core/diagnostics` receipt conversion or serialization consumers, in an isolated worktree.
- The integrator owns portfolio/OpenSpec state, API snapshots, review coordination, combined checks, and main integration.
- Additive receipt contracts must preserve current chain requirements. Any affected Rust or Kotlin serialization consumer must be updated with an absence/default test. No stored user configuration migration is planned.
- Cargo.lock, dependencies, locales, baselines, golden fixtures, root activation, and physical or external probes stay outside scope.

## Risks / Trade-offs

- Method EOL can become a no-op for more requests. Data preservation takes priority over the optional transformation.
- A new native status can be lost at a conversion boundary. Verify the required consumers and independently assert the final result.
- A new status must not imply successful delivery. Preserve partial-write and failure accounting.
- Existing tests may encode destructive behavior. Replace only those assertions with independently expected data-preservation assertions and retain coverage of the transformation boundary.

## Migration Plan

No stored settings migration is required. Additive receipt fields use a compatibility default where serialization applies. Roll back the individual repair commit if needed.

Required local gates: reproduce each defect with a targeted regression before implementation; run affected Rust crate tests with `--locked`, affected strict Clippy checks, format checks, independent read-only review, `just ci-gates`, architecture health, and locked Cargo metadata before integration. If receipt propagation changes Kotlin, JNI, protobuf, or a wire schema, include the corresponding contract and module gates. Hosted CI, device, artifact, and deployment results must be reported separately. Do not claim external compatibility from local tests.
