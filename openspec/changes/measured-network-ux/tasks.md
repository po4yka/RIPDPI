# EPC-1791124000119505

## Objective

Deliver the seven approved product behaviors in sequential feature commits with observed validation.

## Ownership

Root owns these planning records and integration. One implementation writer at a time owns app/core source and all locale sets in an isolated worktree. Independent reviewers are read-only. Persistence contracts and golden fixtures remain serialized.

Export source and its three approved archive fixtures are integrated at `cbf07ec1`; the isolated fixture worktree remains preserved. The unexplained earlier Xray direct request remains an open acceptance incident and is not declared fixed by the later green diagnostic CI.

For timed pause, `pause_implementation` owns production/test Kotlin, manifest and all ten app/service locale resource sets in `network-ux-pause`, except Root's two independent UI tests. Root owns all Markdown/planning, acceptance and integration, and exclusively writes `HomePauseControlsTest.kt` and `HomePauseScreenshotTest.kt` in `network-ux-pause-ui-tests` against 14 hash-frozen read-only UI/model/tag/locale files. Neither writer edits the other's files. Baselines remain frozen until their owning review/recording. Scope and export steps remain open until their required acceptance is recorded; isolated pause implementation does not close them.

## Execution

- [x] EPC-1791124243600668 Explain scan scope and lifecycle consequences in controls and results #epic !high @item:EPC-1791124000119505
- [x] EPC-1791124244103077 Explain measured metrics with aggregation and freshness #epic !high @item:EPC-1791124000119505
- [x] EPC-1791124244595215 Show applied configuration and actionable recovery states #epic !high @item:EPC-1791124000119505
- [x] EPC-1791124245093725 Search grouped diagnostic and saved relay profiles #epic !high @item:EPC-1791124000119505
- [ ] EPC-1791124245588453 Preview diagnostic exports before explicit sharing or saving #epic !high @item:EPC-1791124000119505
- [ ] EPC-1791124246073345 Persist and safely resume timed connection pauses #epic !high @item:EPC-1791124000119505
- [ ] EPC-1791124246556219 Persist profile favorites and recents and expose measured selection #epic !high @item:EPC-1791124000119505

## Verification

- Scope: app diagnostics UI/factory/ViewModel tests and all-locale lint.
- Metrics: app ConnectionHealth tests and core service insights/accumulator tests.
- Applied configuration: Home/Main tests plus runtime registry/controller tests.
- Search: diagnostic/relay picker filtering, empty-state, selection and Back/dismiss tests.
- Export: share-intent/preview cancellation tests plus actual summary/archive redaction inspection.
- Pause: checked persistence/clock/CAS, one-way legacy journal migration, explicit mutation provenance and replay/compensation, full cleanup versus retained shell, stale alarm/user intent, permission/boot/user-stop recovery and matching applied ACK tests; all-locale accessible UI plus actual Android pause/background resume/cancel smoke.
- Profile utility: selector scope/probe/concurrency tests, metadata persistence/deletion/reset tests, UI state tests.
- Every slice: theme checks, affected lint, disposable visual actuals as applicable; final combined staticAnalysis and exact-SHA terminal hosted CI. Before integration run architecture health and locked Cargo metadata. All heavy commands use build-gate with four workers/jobs maximum.
