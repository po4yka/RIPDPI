---
task_id: DAT-1791477482125477
change: reject-subscription-multiplex
commit_sha: null
local: passed
local_evidence: 65 parser and contract JVM tests passed; core data and runtime-state ktlintCheck and detekt passed.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is tracked separately after push; local regression evidence gates this fix.
device: not_applicable
device_evidence: Pure subscription parsing; no runtime implementation changes.
artifact: not_applicable
artifact_evidence: No release artifact is produced.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-MUX-REJECT | DAT-1791477920421310 | SingBoxMultiplexImportTest; RED 3 new test failures, GREEN 4 tests passed | Passed |
| REQ-MUX-COMPAT | DAT-1791477920421310 | SingBoxMultiplexImportTest and VlessRealityImportTest; RED omitted-flow failures, GREEN 18 tests passed | Passed |

## Local commands and scope

Base commit: `1854e271446bb9e955a5d134e07024c0bc3b4bb4`. Evidence below was observed with the local change applied before its commit. The exact implementation commit is recorded by the integration owner before archival.

- RED: `./gradlew :core:data:testDebugUnitTest --tests com.poyka.ripdpi.data.SingBoxMultiplexImportTest --tests com.poyka.ripdpi.data.VlessRealityImportTest`: 18 tests, 4 expected failures before the parser edit.
- GREEN: the same task with test filters `SingBoxMultiplexImportTest`, `VlessRealityImportTest`, `SingBoxSubscriptionParserTest`, `FleetCompatGoldenFileTest`, `RipdpiBundleContractTest`, and `SelectorUrltestGroupImportTest` (all in `com.poyka.ripdpi.data`): 65 tests passed, zero failures, errors, or skips.
- Quality: `:core:data:ktlintCheck :core:data:runtime-state:ktlintCheck :core:data:detekt :core:data:runtime-state:detekt` passed in the GREEN invocation.
- All Gradle commands used `--max-workers=4 -Pripdpi.nativeCpuBudget=4`, a 5 GiB Gradle heap and 3 GiB Kotlin heap. The documented machine-specific `build-gate` executable was absent; builds were serialized with the AWG writer.
- Hosted CI, Android device, and VPS connectivity were not run. The change rejects unsupported framing; it does not add a multiplex runtime.
