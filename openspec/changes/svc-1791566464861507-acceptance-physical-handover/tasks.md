# SVC-1791566464861507

## Objective

Keep transient lease acquisition and loss separate from physical handover classification without losing physical recovery events.

## Ownership

Android writer owns core/service/src/main/kotlin/com/poyka/ripdpi/services/NetworkHandoverMonitor.kt, VpnResolverRuntime.kt in the same directory, and their service tests. Coordinator owns this plan, portfolio state, combined gates, and publication. Reviewer is read-only. No shared schema, locale, baseline, or lockfile edits are planned.

## Execution

- [x] SVC-1791566703809797 Capture the original handover fields and reproduce transient lease feedback #bug !high @item:SVC-1791566464861507
- [x] SVC-1791566704596382 Separate transient lease acquisition and loss from physical handovers with compatibility tests #bug !high @item:SVC-1791566464861507
- [x] SVC-1791566705394380 Verify clean combined service and real Android recovery with remote CI #bug !high @item:SVC-1791566464861507

## Verification

Run full service tests, service lint and detekt, staticAnalysis, architecture health, and locked Cargo metadata. Rebuild genuine Android artifacts and run the full Android profile, a full Xray repeat, and VM-routed Xray. Verify all completed reports and the full 42-scenario clean combined catalog. Observe exact published main CI.
