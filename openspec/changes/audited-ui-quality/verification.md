---
task_id: UIX-1791538891055420
change: audited-ui-quality
commit_sha: 8be992fd514de802295a451e8ce69cd8f0130ab4
local: passed
local_evidence: 2341 app tests and 1689 core tests passed without failures or skips. Static analysis and locale lint passed.
remote_ci: required
remote_ci_evidence: Main was pushed at 160e2e16581770dfeba3bae413e9ec0727cc0595. GitHub CI run 37934017957 is pending; other triggered gates are running.
device: required
device_evidence: Native APK and device acceptance have not been observed.
artifact: passed
artifact_evidence: The user approved 50 inspected PNG updates. Full golden verify passed 285 tests, with one existing ignored RTL test. All 396 expected hashes stayed unchanged during verify.
deployment: not_applicable
deployment_evidence: This change does not own a deployment.
---

# Verification

The combined source passed the complete app gate on `f0425b96`.
The delivered source preserves the generated translation manifest,
diagnostics controls, and profile ownership. Full golden verify passed
on `8be992fd5`, based on `8d2902679`. Rebase changed only task documents;
the tested production and test sources are unchanged.

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
Task contracts passed after the final rebase for 122 tasks and 334 steps.

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
Final read-only compare passed 285 tests with no failures or errors and
one existing ignored RTL test. All 396 expected PNG hashes stayed
unchanged. It captured 50 differences: 45 match the previously reviewed
images, plus four paired dark states and the repaired tuner. All 50
were inspected. The exact paths, filters, rationale, and images are in
`ripdpi-ui-quality-final-golden-evidence-dfey14ib/review-manifest.md`.
The user explicitly approved these 50 PNGs. Narrow record passed all
46 selected methods. Exactly 50 expected PNGs changed; each matches its
reviewed actual image byte for byte. The other 346 PNGs are unchanged.
Normal commit hooks passed.

Final broad `verifyRoborazziGithubFullDebug` passed on `8be992fd5`: 286
tests in 55 suites, 285 passed, no failures or errors, and one existing
ignored RTL gallery test. It completed in 1 minute 38 seconds. No SDK
override was used. All 396 expected hashes stayed unchanged during
verify. The complete XML, HTML, log, and hash proof is preserved in
`ripdpi-ui-quality-final-full-verify-evidence-al6nc6ci`. The log is
`/tmp/ripdpi-ui-quality-final-full-verify.log`.

Main advanced to `8d2902679`, with the identical JSON repair and task
documentation. The integration branch was rebased without source
conflicts. Its app, core, build-logic, config, native, and Gradle sources
match the tested and reviewed tree. The only later app changes are the
50 approved expected PNGs. Task contracts passed after the final rebase.

Simple runtime stops at `verifyEmbeddedRelayBundle`: it needs a real,
non-empty `src/simple/assets/embedded-relay-bundle.json`. No placeholder
or gate bypass was used. Simple production UI compiled and passed lint;
this does not prove its runtime behavior.

SDK 37 preview rendering is unsupported by the pinned renderer, which
supports SDK 21 through 36. SDK 36 previews are compatibility evidence.
The native APK needs the actual LibXray artifacts. Native device,
TalkBack, packet traffic, and hosted CI acceptance remain separate.

## Delivery

The integration branch was rebased onto `origin/main` before delivery.
Only task documents changed after the complete source and golden gates.
Task contracts passed on the final combined tree.

The main checkout was clean. Fast-forward integration and normal push
completed with exit 0. `git ls-remote origin refs/heads/main` confirmed
`160e2e16581770dfeba3bae413e9ec0727cc0595`. No unrelated edits were
staged or discarded. The approved golden commit contains exactly the
50 reviewed PNGs. Each source repair has its own commit.

Hosted CI was inspected for this exact SHA. The main CI run is pending:
https://github.com/po4yka/RIPDPI/actions/runs/37934017957. Locale parity,
translation export, harness, fleet, Secret Scan, and CodeQL runs were
in progress. These states do not establish hosted CI acceptance.
The task is in review; native device and hosted CI acceptance remain
separate from the completed local implementation and main delivery.
