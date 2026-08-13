# SVC-1786597927063162: Separate local runtime readiness from VPN connectivity

## Objective

Keep local runtime lifecycle active and controllable while Home makes a separate evidence-backed claim about whether the VPN data plane works.

## Ownership

`app/src/main/kotlin/com/poyka/ripdpi/activities/`, focused app and service tests, the app/service locale sets, and the lifecycle-status documentation in `:core:data:model`. No native, JNI, protobuf, persistence, or diagnostics wire files.

## Execution

- [x] SVC-1786598277318910 Add a typed VPN data-plane projection, use it to keep Home lifecycle controls active without claiming unverified connectivity, localize the new states, and verify RED/GREEN plus affected gates #feature !high @item:SVC-1786597927063162

## Verification

Observed RED/GREEN focused app test; affected app unit-test variant; `./gradlew staticAnalysis`; locale lint; architecture health; strict OpenSpec/task validation; rebased combined-tree gates; hosted CI and physical-device validation reported separately.
