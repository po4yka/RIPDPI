# RIPDPI Roadmap

This roadmap is based on the audit issues discussed in this thread. It separates confirmed work from partially confirmed items and orders the work to reduce risk: hardening first, structural cleanup next, and runtime refactors only after baselines exist.

## Status Legend

- `confirmed`: directly supported by code inspection or repository evidence.
- `partially confirmed`: structurally plausible, but needs measurement or deeper validation before a large refactor.
- `defer`: low priority relative to higher-impact items.

## Phase 0: Baseline And Guardrails

Goal: establish repeatable measurements before changing architecture.

- [ ] Define baselines for native throughput, memory per connection, startup/shutdown time, JNI polling cost, release `.so` size, and key test/runtime durations.
- [ ] Add a small benchmark or profiling harness for the runtime hot path and TCP relay path.
- [ ] Record current values in CI artifacts or a tracked benchmark output.
- [ ] Add a short checklist for unsafe-code review so future refactors do not expand blind spots.

Acceptance criteria:

- Baselines are reproducible locally and in CI.
- Later phases can compare against a known starting point.

Suggested commit slice:

- `docs: add roadmap and baseline plan`
- `test: add initial benchmark harness`

## Phase 1: Input-Surface Hardening

Goal: close the highest-risk gaps around untrusted input.

- [ ] Add fuzz targets for parser-heavy code in `ripdpi-packets`.
- [ ] Add fuzz targets for `ripdpi-failure-classifier` and other blockpage or response-parsing paths.
- [ ] Add a documented local fuzz workflow and at least one CI smoke target if practical.
- [ ] Triage fuzz crashes into parser fixes, corpus additions, or false positives.

Acceptance criteria:

- Core network parsers have fuzz coverage.
- At least one fuzz job or smoke test is runnable in a documented way.
- Newly discovered parser bugs are fixed or tracked with repro cases.

Suggested commit slice:

- `test: scaffold fuzz targets for parser crates`
- `test: add fuzz smoke documentation`
- `fix: address first fuzz-found parser issues`

## Phase 2: Unsafe Audit And Containment

Goal: make unsafe usage reviewable and reduce hidden risk.

- [ ] Audit unsafe blocks in `ripdpi-runtime`, starting with platform and socket code.
- [ ] Add or tighten `// SAFETY:` notes where the code is not self-evident.
- [ ] Extract safe wrappers for repeated unsafe patterns such as socket setup, fd handling, and platform shims.
- [ ] Add Miri or equivalent pure-logic checks where the code can run off-device.

Acceptance criteria:

- Unsafe hotspots are mapped to owners or modules.
- Repeated unsafe patterns are wrapped behind narrower APIs.
- Pure-logic unsafe helpers have at least one automated validation path.

Suggested commit slice:

- `refactor: isolate runtime unsafe helpers`
- `docs: annotate unsafe invariants`
- `test: add targeted unsafe validation`

## Phase 3: Runtime Contention Reduction

Goal: reduce lock contention on the hot path without speculative redesign.

- [ ] Profile `RuntimeState` and identify the actual contention points.
- [ ] Split the state into smaller ownership units if the profile confirms a shared-lock bottleneck.
- [ ] Convert read-heavy paths to snapshot or read-optimized access where it is justified by data.
- [ ] Revisit autolearn host storage and adaptive tuning state only after measurement.

Acceptance criteria:

- The profile shows lower contention on the targeted hot path.
- Behavior stays unchanged under concurrent connection load.
- Benchmarks show either lower latency or higher throughput, or both.

Suggested commit slice:

- `perf: measure runtime lock contention`
- `refactor: shard hot runtime state`
- `perf: add contention regression benchmark`

## Phase 4: Diagnostics Boundary Cleanup

Goal: remove the direct diagnostics-to-service coupling.

- [ ] Define a diagnostics-facing interface in a neutral module such as `:core:data` or a new diagnostics API module.
- [ ] Move service-owned implementations behind that interface in `:core:service`.
- [ ] Replace direct diagnostics imports of service-layer types with injected abstractions.
- [ ] Update tests so diagnostics can run with fakes without pulling the service implementation.

Acceptance criteria:

- `:core:diagnostics` no longer depends directly on `:core:service`.
- Diagnostics behavior is testable with substitutes.
- The dependency arrow is interface-first, not implementation-first.

Suggested commit slice:

- `refactor: introduce diagnostics service interface`
- `refactor: decouple diagnostics from service implementation`
- `test: update diagnostics fakes and coverage`

## Phase 5: App-State Decomposition

Goal: reduce `MainViewModel` width and isolate orchestration concerns.

- [ ] Extract connection-state reconciliation into a smaller component or use case.
- [ ] Separate startup, permission, diagnostics, and navigation orchestration where the responsibilities are currently mixed.
- [ ] Keep `MainViewModel` as a composition root, not a behavior dump.
- [ ] Preserve current behavior with focused tests during each extraction.

Acceptance criteria:

- Constructor width is materially reduced.
- The extracted pieces are independently testable.
- No user-visible behavior changes in startup, permissions, or diagnostics flows.

Suggested commit slice:

- `refactor: extract connection state reconciliation`
- `refactor: split main viewmodel orchestration`
- `test: add focused viewmodel coverage`

## Phase 6: Session-Scoped DI

Goal: replace manual session lifetime handling with explicit scope.

- [ ] Introduce a VPN or runtime session scope for per-run objects.
- [ ] Move session-owned coordinators, telemetry bridges, and runtime helpers into that scope.
- [ ] Keep true app-wide singletons global and do not over-scope shared stores.
- [ ] Convert manual destroy paths to scope-driven lifecycle where possible.

Acceptance criteria:

- Session-lifetime objects are created and destroyed automatically.
- Manual cleanup is reduced rather than expanded.
- The scope model is documented and used consistently.

Suggested commit slice:

- `refactor: add session scope for vpn runtime`
- `refactor: migrate session-owned services`
- `docs: document scoped lifetime model`

## Phase 7: TCP Concurrency Model Validation

Goal: validate whether the current thread-per-connection model should be changed.

- [ ] Measure memory and scheduling cost per active connection under load.
- [ ] Confirm which relay paths truly need blocking threads and which can use lighter task-based execution.
- [ ] Prototype a hybrid model only if the measurement shows a material win.
- [ ] Keep the more complex desync and socket-mutation paths on blocking execution where required.

Acceptance criteria:

- The actual concurrency bottleneck is measured.
- Any hybrid model is benchmark-backed and does not regress protocol handling.
- The default path stays simple unless the gain is clear.

Suggested commit slice:

- `perf: measure tcp per-connection runtime cost`
- `refactor: prototype hybrid relay execution`
- `perf: compare relay models under load`

## Phase 8: JNI Wrapper Validation

Goal: verify that the JNI polling and wrapper lifecycle are correct before redesigning them.

- [ ] Audit handle lifecycle and race windows around poll, stop, and destroy.
- [ ] Add concurrency tests for stale-handle and lifecycle-transition cases.
- [ ] Only introduce atomics or a state machine if tests expose a concrete bug or measurable latency issue.

Acceptance criteria:

- The suspected JNI contention issue is either disproven or backed by a failing test.
- Handle lifecycle transitions are exercised by automation.
- No speculative synchronization rewrite lands without evidence.

Suggested commit slice:

- `test: add jni lifecycle race coverage`
- `refactor: harden wrapper state transitions`

## Phase 9: RelayBackend Cleanup

Goal: reduce boilerplate without changing behavior.

- [ ] Replace repetitive `RelayBackend` match arms with a trait-based or macro-generated dispatch path.
- [ ] Keep the refactor mechanical and local to the relay abstraction.
- [ ] Avoid mixing boilerplate cleanup with protocol changes.

Acceptance criteria:

- The delegation code is shorter and easier to extend.
- Behavior is unchanged.
- The change is low-risk and easy to review.

Suggested commit slice:

- `refactor: simplify relay backend dispatch`

## Phase 10: TLS Size Monitoring

Goal: keep binary size visible without forcing premature architectural cuts.

- [ ] Preserve existing size and bloat checks in CI.
- [ ] Attribute native size growth by crate or feature when possible.
- [ ] Revisit protocol feature gating only if release size becomes a real constraint.

Acceptance criteria:

- Release-size trends remain observable.
- Any growth can be explained by a specific crate or feature.
- No unnecessary TLS-stack removal lands without a measured need.

Suggested commit slice:

- `ci: improve native size monitoring`
- `docs: note native size tradeoffs`

## Recommended Execution Order

1. Phase 0
1. Phase 1
1. Phase 2
1. Phase 3
1. Phase 4
1. Phase 5
1. Phase 6
1. Phase 7
1. Phase 8
1. Phase 9
1. Phase 10

## Non-Goals

- No broad rewrite of the runtime concurrency model without measurements.
- No diagnostics-service coupling changes that preserve the same architecture under a different name.
- No binary-size optimization that removes intentional protocol support without evidence.
- No unsafe cleanup that weakens correctness or hides invariants.

