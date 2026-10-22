# DAT-1792661188293948: Keep verified reserve profiles available offline

## Objective

Reload accepted reserves without subscription access and verify a working reserve using real bounded payloads.

## Ownership

Parent owns planning, docs and offline integration tests. Refresh worker owns app subscription refresh production/tests. Persistence worker owns ProxyGroupStores and runtime-state tests. Writers use distinct worktrees; only parent changes shared task/schema/dependency inputs.

## Execution

- [ ] DAT-1792661302262545 Preserve encrypted reserve state and test corrupt or failed storage #feature !high @item:DAT-1792661188293948
- [ ] DAT-1792661307171939 Guard reserve refresh publication and test unusable and obsolete results #feature !high @item:DAT-1792661188293948
- [ ] DAT-1792661307731110 Verify offline restart and real payload-qualified reserve selection #feature !high @item:DAT-1792661188293948

## Verification

Gated runtime-state/core-service JVM tests, app subscription/selector JVM tests and staticAnalysis; task-check and architecture-health. Physical-device and filtered-network acceptance are separate, unclaimed evidence.
