# EPC-1791124000119505

## Objective

Deliver the seven approved product behaviors in sequential feature commits with observed validation.

## Ownership

Root owns these planning records and integration. One implementation writer at a time owns app/core source and all locale sets in an isolated worktree. Independent reviewers are read-only. Persistence contracts and golden fixtures remain serialized.

For export fixture finalization, golden-blesser exclusively owns `core/diagnostics/src/test/resources/golden/archive/{manifest_v12.json,runtime_config_v6.json,integrity_v12.json}` in `network-ux-export-json`. Its copied production source, locales and PNGs are frozen. Root owns the combined worktree, documentation and integration; the Pause explorer is read-only until export delivery completes.

## Execution

- [ ] EPC-1791124243600668 Explain scan scope and lifecycle consequences in controls and results #epic !high @item:EPC-1791124000119505
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
- Pause: runtime-state, service controller/boot/process reconstruction tests and Android lifecycle smoke.
- Profile utility: selector scope/probe/concurrency tests, metadata persistence/deletion/reset tests, UI state tests.
- Every slice: theme checks, affected lint, disposable visual actuals as applicable; final combined staticAnalysis and exact-SHA terminal hosted CI. Before integration run architecture health and locked Cargo metadata. All heavy commands use build-gate with four workers/jobs maximum.
