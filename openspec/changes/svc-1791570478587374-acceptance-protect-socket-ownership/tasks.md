# SVC-1791570478587374: Preserve protect socket ownership across VPN session overlap

## Objective

Keep newer VPN protection available when old session teardown overlaps.

## Ownership

Android: core/service protect server, VPN session module and lifecycle, active path provider, native registration owner, runtime context assembler, and their tests; core/service testFixtures/ProtectSocketOwnershipProbe.kt, app Android E2E ProtectSocketOwnershipInstrumentedTest.kt, and the single existing-module test fixture dependency line in app/build.gradle.kts. Coordinator: task and OpenSpec artifacts. Reviewer and Linux: read-only inspection. Heavy builds are serialized.

## Execution

- [ ] SVC-1791570737195239 Reproduce overlapping endpoint and registration ownership with observed failures #bug !high @item:SVC-1791570478587374
- [ ] SVC-1791570737996972 Preserve per-session protection ownership and pass local compatibility gates #bug !high @item:SVC-1791570478587374
- [ ] SVC-1791570738877357 Verify clean Android catalog repeats and exact published CI #bug !high @item:SVC-1791570478587374

## Verification

Observe deterministic RED before production edits. Run actual Android endpoint overlap/connect/ACK checks, full service --rerun and staticAnalysis, then clean genuine Android profile and full Xray/VM-routed repeats with lab verify, Simple cancel/share, independent review, and exact published main CI. Keep failures and repeat receipts separate.
