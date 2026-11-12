---
task_id: TRN-1794466458131158
change: repair-warp-preset-contracts
commit_sha: a5d5bdeb3638d45d9f657b21c4e04c609f5720c8
local: passed
local_evidence: Focused regressions, 4872 combined JVM tests, workspace Rust gates and staticAnalysis passed.
remote_ci: required
remote_ci_evidence: Source and local evidence pushed to origin/main at 3dea72f5c08d51b69091e286672381641705be84; remote CI and CodeQL are not observed.
device: not_applicable
device_evidence: No device or external transport acceptance is requested; host UI rendering and local peer checks are tracked separately.
artifact: not_applicable
artifact_evidence: This source repair does not publish a package or change JNI signatures.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-WARP-PRESET-WIRE | TRN-1794466908354230 | 193 warp-core tests passed; raw peer and reserved cookie retry included; workspace gates passed | passed |
| REQ-WARP-PRESET-ENABLED | TRN-1794466913001475 | 49 focused JVM tests passed; two RED failures reproduced first | passed |
| REQ-WARP-PRESET-HINT | TRN-1794466918147610 | Semantic Compose regression passed; no locale or golden edits | passed |
| REQ-WARP-PRESET-HINT-REFRESH | TRN-1794466921219277 | Seven assembler tests plus the semantic case passed, including startup, empty source and cancellation | passed |

## Limits

No public WARP endpoint, real credentials, root, physical transport, release or
production deployment is used. The pinned generic AWG suite remains required.

## Reviewed code commits

- F2: `1fc2df2163` preserves effective enable state in settings, UI, backup and native preferences.
- F3: `caa9896b20` labels unselected presets as recommendations.
- F1/H1: `d1c9ef0ba8` preserves WireGuard envelopes and WARP reserved MAC behavior.
- H2: `401033710c` refreshes network hints after observer subscription.

Independent read-only review found no remaining actionable findings. The first
H2 review found an initial-capture race; a failing regression reproduced it
before the subscriber-first correction. The final review accepted that change.
An async specialist also accepted F1 cleanup and cancellation annotations.

## Observed local checks

- F1 RED: the new real runtime test failed with initiation156 instead of148.
  Log `/tmp/tun04-f1-red.log`. After the fix, the complete warp-core suite passed
  193/193 with no skips. Log `/tmp/tun04-f1-green-serial.log`. The first parallel
  suite run passed192 and failed one unchanged IPv4/IPv6 port-pair test with
  AddrInUse; no test was removed or skipped. Canonical nextest later passed it.
- F1 Kotlin catalog assertions passed5/5 in IntegrationCatalogsTest.
  `/tmp/tun04-f1-catalog-green.log`. Embedded and asset catalog definitions match.
- F2 passed49 focused tests: settings/JSON12, native writer7, binder30.
  `/tmp/tun04-state-green.log`. The initial two failures are recorded in
  `/tmp/tun04-state-red.log`.
- F3 semantic test passed1/1 after the recommendation-title RED failure.
  `/tmp/tun04-hints-f3-green.log` and `/tmp/tun04-hints-f3-red.log`.
- H2 final focused suite passed8/8: assembler7 and F3 semantic1.
  `/tmp/tun04-hints-h2-final-green.log`. Stale-network and startup-window RED
  receipts are `/tmp/tun04-hints-h2-red.xml` and the worker startup test log.
- Workspace Clippy with all targets and warnings denied passed. Canonical
  workspace nextest passed7347/7347 with66 official platform/e2e exclusions;
  ignored startup smoke passed1/1. Backend boringtun passed44/44; smoltcp TCP
  IPv4 and IPv6 passed195 each. Workspace doctests passed2, failed0, ignored2.
  Logs `/tmp/tun04-rust-workspace-{clippy,tests,doctests}.log`, exact exits and
  durations `/tmp/tun04-rust-workspace-results.tsv`.
- One unchanged Tor bootstrap-retry test emitted a non-failing LEAK signal.
  Its cause was not established. Doctests retain cdylib support and h3 dependency
  warnings; this is not a warning-free docs claim.
- All3172 Rust/Cargo and preset-asset hashes matched before and after broad gates,
  and matched again after commit hooks. Receipts
  `/tmp/tun04-rust-workspace-inputs-{before,after}.json`.

## Exact broad Rust commands

All commands ran under one `build-gate --lane ripdpi` lease with host jobs4 and
`CARGO_TARGET_DIR=/tmp/ripdpi-tun04-target`:

```sh
cargo clippy --manifest-path native/rust/Cargo.toml --workspace --all-targets --locked --jobs 4 -- -D warnings
bash scripts/ci/run-rust-workspace-tests.sh
cargo test --manifest-path native/rust/Cargo.toml --workspace --doc --locked --jobs 4
```

The canonical script owns its documented platform/e2e filter. No new exclusion,
retry, weakened assertion, real credential, or external transport probe was used.

## Combined check and visual evidence

The first combined app run completed3481 tests with one obsolete fixture
failure: SettingsUiStateTest expected a preset to enable shaping while its saved
flag was false. The fixture now explicitly enables the active case and also
asserts disabled UI state. No production contract was weakened. A complete
combined rerun passed all4872 tests (settings87, data989, engine315, app3481).
Its only remaining failure was ktlint layout in the new test assertion. After
that whitespace correction, the full staticAnalysis and UI-state class80/80
passed. Logs were `/tmp/tun04-combined-gradle-final.log` and
`/tmp/tun04-final-static-and-ui-state.log`.

The real WarpSection was rendered with production localized dropdown arrays at
320x470. Off is selected; Recommended profile and the informational QUIC hint
are visible without clipping. The render test passed1/1. PNG
`/tmp/tun04-warp-recommendation.png`, SHA-256
`6b5f885a802a22a1a0477f642049d5a22a8798e3956e258a5877fa44037168e3`.
Provenance `/tmp/tun04-hints-render-provenance.json` records worker code commit
01ce8171c8bd8cb5c53383ca467c07e466d44edf, cherry-picked as401033710c. Temporary
capture-only changes were restored byte-for-byte; no golden was changed.

## Final local gate receipt

The final UI-state test-only commit is `a5d5bdeb36`. Locked Cargo metadata
completed with exit0. The last staticAnalysis run completed successfully in
1m38s. Local JVM XML still confirms settings87, data989, engine315 and the
focused app class80 with zero failures, errors or skips.

Temporary `/tmp` receipts named above existed and were inspected during the
runs. They disappeared after the interrupted session; the commands and observed
results are retained here, but those paths are not durable attachments.
The repeated source gates completed with exit0 before integration.
The fresh log is `/tmp/tun04-final-ci-gates.log`; all Rust API snapshots match.

The combined JVM command was:

```sh
./gradlew :core:data:settings:testDebugUnitTest :core:data:testDebugUnitTest :core:engine:testDebugUnitTest :app:testGithubFullDebugUnitTest staticAnalysis --max-workers=4 -Pripdpi.skipNativeBuild=true -Pripdpi.nativeCpuBudget=2
```

After the test assertion layout correction:

```sh
./gradlew :app:testGithubFullDebugUnitTest --tests com.poyka.ripdpi.ui.screens.settings.SettingsUiStateTest staticAnalysis --max-workers=4 -Pripdpi.skipNativeBuild=true -Pripdpi.nativeCpuBudget=2
cargo metadata --manifest-path native/rust/Cargo.toml --locked --no-deps --format-version 1
```

Gradle ran through `build-gate --lane ripdpi` with ambient
`CARGO_BUILD_JOBS` cleared and the disposable native target directory.

## Integration receipt

On 2026-11-12, `git fetch origin` found the unchanged base `f6ad80c20d`.
`git rebase origin/main` reported the repair branch up to date. Main was clean,
then `git merge --ff-only fix/warp-preset-contracts` and `git push origin main`
completed successfully. `git ls-remote --exit-code origin refs/heads/main`
confirmed `3dea72f5c08d51b69091e286672381641705be84`.

The server accepted the authorized direct push with bypass notices for the PR
rule, expected ci-required status, and pending CodeQL results. This receipt
does not claim remote CI success. The task remains in review until that
required evidence is available. No release or external transport was run.

All execution steps are complete. This final receipt changes tracking documents
only. The registry, dependencies, baselines and source audit are preserved.
