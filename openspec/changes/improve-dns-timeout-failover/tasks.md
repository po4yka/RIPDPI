# SVC-1786488973639528: Bound encrypted DNS timeout failover

## Objective

Fail over after one encrypted DNS bootstrap timeout without persisting a
transient timeout as a network-blocked resolver path.

## Ownership

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/VpnEncryptedDnsFailoverController.kt`
- `core/service/src/test/kotlin/com/poyka/ripdpi/services/VpnEncryptedDnsFailoverControllerTest.kt`
- This OpenSpec change, its portfolio issue, closure receipt, and generated
  task board
- No serialized shared-file lane is modified

## Execution

- [x] SVC-1786489128455286 Implement eager bootstrap-timeout failover, keep timeout paths session-local, and verify strict encrypted-only behavior #bug !high @item:SVC-1786488973639528

## Verification

- Focused controller RED/GREEN regression.
- Full `:core:service:testDebugUnitTest`.
- `staticAnalysis`.
- `taskctl openspec` strict validation, task validation, and hosted CI.
