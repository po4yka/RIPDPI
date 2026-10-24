# SVC-1792856195708453

## Objective

Use selected physical authority for VPN handover and tunnel network parameters.

## Ownership

Protocol worker owns the existing observer, fingerprint, handover, tunnel-parameter
sources and affected existing tests in its isolated worktree. Transient model
metadata is permitted only for atomic selected semantic evidence. Coordinator
owns planning records and integration and is the sole physical-device operator.
Security worker performs independent read-only review. Other work is preserved.
Protocol worker also owns the narrow cooldown consumer fix in
NetworkHandoverProcessor.kt, HandoverAwareSession in ServiceRuntimeCoordinator.kt,
ServiceRuntimeRegistry.kt and BaseServiceRuntimeCoordinatorTest.kt.

The shared process owner is renamed from CandidateRelayNetworkEpoch to
PhysicalNetworkObserver. Protocol also owns rename-only references in
AndroidRuntimeEvidenceModule.kt and ActiveAwgMigrationProbe.kt.

## Execution

## Verification

Reproduce overlay and MTU regressions, then run complete affected JVM and static
checks. Review source and cancellation/locking. Rebuild genuine artifacts and
run all four owned USB scenarios with exact digests and cleanup. Hosted source
gates remain required.
- [ ] SVC-1792856270513687 Use atomic selected physical snapshots for fingerprint handover and tunnel MTU without VPN fallback #bug !high @item:SVC-1792856195708453
- [ ] SVC-1792856270934423 Verify overlay isolation real path changes unavailable evidence scope hashing and DNS lease boundaries #bug !high @item:SVC-1792856195708453
- [ ] SVC-1792856271355520 Integrate reviewed source and pass genuine artifact four USB scenarios and hosted gates #bug !high @item:SVC-1792856195708453
