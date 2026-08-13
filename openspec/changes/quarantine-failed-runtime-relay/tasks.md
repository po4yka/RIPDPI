# SVC-1786565057976588: Quarantine confirmed failed runtime relays

## Objective

Persist failed active relay confirmations in the existing network- and capability-scoped health memory so a confirmed-bad profile is excluded from later candidate races until its bounded cooldown expires.

## Ownership

- Production: `app/src/simple/kotlin/com/poyka/ripdpi/failover/FailoverCoordinator.kt`.
- Regression coverage: `app/src/testGithubSimple/kotlin/com/poyka/ripdpi/failover/FailoverCoordinatorTest.kt`.
- Serialized shared-file lane: none; dependency manifests, schemas, generated artifacts, locale resources, and native registries are out of scope.

## Execution

- [x] SVC-1786565057977001 Add an observed RED/GREEN regression, persist failed active relay probes with the effective proof in `confirmRelayEgress`, remove the duplicate XUDP-only write, and verify bounded same-network quarantine plus success and cross-network isolation #bug !high @item:SVC-1786565057976588

## Verification

- Targeted GitHub Simple JVM regression for failed active relay confirmation.
- Full `:app:testGithubSimpleDebugUnitTest` suite.
- `./gradlew staticAnalysis`.
- `python3 scripts/ci/check_architecture_health.py`.
- Strict OpenSpec and task-portfolio validation.
- Hosted CI is reported separately after the implementation commit is pushed; physical-device and live-relay deployment evidence are not implied by local checks.
