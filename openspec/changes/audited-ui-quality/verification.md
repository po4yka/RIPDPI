---
task_id: UIX-1791538891055420
change: audited-ui-quality
commit_sha: 92651124770cf055cfe073944794534f1e4685ae
local: passed
local_evidence: 2341 app tests and 1689 core tests passed without failures or skips. Static analysis and locale lint passed.
remote_ci: required
remote_ci_evidence: Main integration and push are authorized and pending.
device: required
device_evidence: Native APK and device acceptance have not been observed.
artifact: required
artifact_evidence: Supported SDK 36 previews were inspected. Final Full golden comparison is pending.
deployment: not_applicable
deployment_evidence: This change does not own a deployment.
---

# Verification

The combined source passed the complete app gate on `f0425b96`.
The generated translation manifest is committed as `926511247`.
Both commits are based on `9e28d0330`; the new diagnostics controls and
profile ownership from main are preserved.

## Requirement evidence

| Requirement | Step | Observed evidence | Result |
|---|---|---|---|
| REQ-UIQ-INPUT | UIX-1791539016251165 | Native keyboard, toggle, focus, and accessible-name tests. The old focus targets failed seven cases with the same platform Keyboard harness. The legacy positional dropdown API is tested. | passed in Full combined gate |
| REQ-UIQ-LAYOUT | UIX-1791539016251165 | Bounded and unbounded dialogs, font scale 2, RTL, and Home action wrapping. Five shared SDK 36 previews inspected. | passed in Full combined gate |
| REQ-UIQ-CREDENTIAL | UIX-1791539018423234 | Password masking, secure-window setup and release, and share-dialog policy. | passed in Full combined gate |
| REQ-UIQ-RECOVERY | UIX-1791539018423234 | Draft retention, load/save retry, catalog reload, refresh serialization, and cancellation. Twelve setup SDK 36 previews inspected. | passed in Full combined gate |
| REQ-UIQ-EVIDENCE | UIX-1791539020136173 | Results follow the selected or running profile. Consent, busy selection, tuner feedback, and typed tool states are tested. Compact and RTL layouts were inspected. | passed in Full combined gate |
| REQ-UIQ-LOCALE | UIX-1791539021807814 | All ten locales have the new keys. Full/Simple app lint, service lint, locale parity, and translation-export checks passed. | passed |

## Complete combined gate

The exact command ran all `testGithubFullDebugUnitTest` tests,
`lintGithubFullDebug`, `core:service:lintDebug`, and `staticAnalysis`,
with `--continue`. It used the normal build-gate, offline, native-skip,
worker, and heap settings. No test filter or SDK override was used.

Result: exit 0 in 4 minutes 30 seconds; 2,341 tests in 375 suites,
zero failures, errors, or skips. Full and Simple app lint, service lint,
module boundary guards, detekt, and ktlint passed. All 4,283 app, core,
and build-logic source hashes stayed unchanged during the gate.

The full XML, HTML, lint, and log snapshot is preserved in the local
evidence directory `ripdpi-ui-quality-final-combined-evidence-__k0z2ul`.
The command log is `/tmp/ripdpi-ui-quality-final-combined-gates.log`.

Architecture health passed: 21 existing indicators, no new or worsened
indicators, 116 crates, and 354 internal dependency edges. The log is
`/tmp/ripdpi-ui-quality-architecture-combined.log`. Full locked Cargo
metadata passed with 116 workspace members and 910 resolved packages.
Its output is `/tmp/ripdpi-ui-quality-cargo-metadata-final.json`.
Task contracts passed for 123 tasks and 335 steps.

The source translation catalog has 4,701 keys. The owning export script
added the eleven new UI keys to its manifest. The export checker and its
three Python tests passed. Locale parity passed for main, Simple, and
service resource catalogs.

## Confirmed regressions and repair

The first combined gate found prohibited engine testing imports in the
rule-editor test. Local one-shot I/O failures now preserve its retry
assertions. The app engine boundary passed. The exact missing SDK 32
Robolectric artifact was obtained from Maven Central and checked with
SHA-512; all three original SubscriptionStatusNotifier methods passed.

The user approved a separate JSON centralization repair. Its five new
contract tests preserve PMTU default values, explicit nulls, and tolerant
parsing, and strict transfer parsing. All 1,689 model and diagnostics
tests passed, with detekt and ktlint. The app JSON source guard passed
in the complete gate. No schema or native measurement behavior changed.
The core log is `/tmp/ripdpi-ui-quality-json-green.log`.

Broad Full golden verification found a real tuner progress regression.
Four real-screen tests reproduced the one-character progress column.
The control now uses an adaptive flow and intrinsic button width.
All four tests passed at 420dp and at 320dp/font scale 2 with Arabic RTL,
including Run and Cancel callbacks. Actual images were inspected.
The log is `/tmp/tuner-progress-green.log`.

Independent reviews found no remaining actionable source defects in
the combined UI, the JSON contracts, or the tuner repair.

## Artifact and runtime limits

The first broad Full golden gate ran 286 tests: 239 passed, 46 failed,
and one existing RTL gallery test was ignored. All 46 comparisons were
inspected. The tuner regression was repaired; the other differences
match the approved UI changes. Expected PNGs were not changed.
Final compare mode will also capture paired dark states before any
request to update the exact reviewed fixture paths.

Simple runtime stops at `verifyEmbeddedRelayBundle`: it needs a real,
non-empty `src/simple/assets/embedded-relay-bundle.json`. No placeholder
or gate bypass was used. Simple production UI compiled and passed lint;
this does not prove its runtime behavior.

SDK 37 preview rendering is unsupported by the pinned renderer, which
supports SDK 21 through 36. SDK 36 previews are compatibility evidence.
The native APK needs the actual LibXray artifacts. Native device,
TalkBack, packet traffic, and hosted CI acceptance remain separate.
