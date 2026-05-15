# Feature Test Completion Audit - 2026-05-14

This audit maps the active application-test objective to concrete artifacts.
Use it together with `docs/feature-test-checklist.md` and
`docs/feature-test-evidence-2026-05-14.md`.

## Objective

Perform application testing according to `docs/feature-test-checklist.md`, fix
all issues found during that pass, and confirm whether the application is
fully verified.

## Current Verdict

Status: **not complete**.

The local automation and regression slice is verified. The full feature-test
checklist still depends on environments and provider-backed runs that are not
available in the current local lab.

## Prompt-to-Artifact Checklist

| Requirement | Evidence inspected | Result | Remaining evidence required |
| --- | --- | --- | --- |
| Use `docs/feature-test-checklist.md` as the source checklist | `test-lab/scripts/test-feature-checklist-coverage.sh` confirms `docs/feature-test-evidence-2026-05-14.md` maps every major checklist section to `Covered locally` or `Partial`, includes the current 248-item checklist baseline, and rejects stale section/item/evidence-row summary counts | Covered locally | None for source-checklist mapping; incomplete checklist rows remain tracked by their own requirement rows |
| Fix all issues found during the local pass | `docs/feature-test-evidence-2026-05-14.md` records fixed findings and commit subjects for test-lab, build packaging, diagnostics archive redaction, Appium, Maestro, onboarding, logs, UI automation, debug-probe relay readiness, proxy E2E orchestration, service stop-self fallback defects, native fuzz lockfile drift found by the current fuzz smoke rerun, mutation workflow wrapper drift found from the failed hosted mutation-testing logs, stale diagnostics-boundary unit-test fixture drift found by the broad repository script-test suite, partial-read readiness artifact drift found by the final sign-off rerun, local-artifact checker variable-path drift found by the current evidence audit, and debug-install signing-key mismatch guidance found by the emulator proxy E2E rerun | Covered locally | None for the bugs found in the local pass |
| Verify Appium installation and current app flows | Appium evidence rows in `docs/feature-test-evidence-2026-05-14.md`; Appium 3.4.2 with UiAutomator2 7.3.0 is installed. After the local Maestro/Appium install was confirmed again on May 15, 2026, the first current installed full-suite rerun found two harness issues: a reset-state History no-match search assumption and an async support-bundle export chooser leak into the next Diagnostics launch. Both were fixed in the Appium tests. The final current full Appium suite passed on Pixel 8 Pro with `79 passed, 17 skipped, 1 warning` in 1846.96s (0:30:46). This covers launch/navigation, onboarding/advanced/host-pack, diagnostics/logs/support/theme, diagnostics-tail, deterministic scan report/audit coverage, all 7 workflow journeys, activation-window controls, background guidance, backup-PIN warning/editor paths, and clean History empty-state behavior on reset devices. Remaining skips are explicit fixture/environment skips, not unexpected failures | Covered locally | None for current Appium coverage; rerun after future UI or Appium page-object changes |
| Verify Maestro installation and current smoke flows | Maestro smoke and default-install fallback rows in `docs/feature-test-evidence-2026-05-14.md`; smoke pack and lab VPN orchestrator passed on Pixel 8 Pro with Maestro resolved from `~/.maestro/bin/maestro` while absent from `PATH`; after merging `origin/main` locally and rebuilding the current debug APK from `89deca61`, the four-flow smoke rerun passed all committed flows; after the local Maestro/Appium install was confirmed again on May 15, 2026, `PATH="$HOME/.maestro/bin:$PATH" bash scripts/ci/run-maestro-smoke.sh` passed all four committed flows on the attached Pixel 8 Pro; the proxy E2E runner also passed with Maestro-driven connect/disconnect and foreground-service leak assertion | Covered locally | None for current Maestro coverage; rerun after future Home, Settings, or lab-runner changes |
| Verify static local quality gates for the current head | `gh run view 25875963396 --json jobs,headSha,conclusion,status,workflowName,url`; `python3 scripts/ci/check_architecture_health.py --check`; `python3 scripts/ci/check_native_hotspot_budgets.py`; `./gradlew :core:diagnostics-data:ktlintMainSourceSetCheck -Pripdpi.skipNativeBuild=true --no-daemon`; `./gradlew :core:diagnostics-data:ktlintDebugSourceSetCheck :core:diagnostics-data:testDebugUnitTest -Pripdpi.skipNativeBuild=true --no-daemon`; `./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true --no-daemon`; `./gradlew :app:verifyRoborazziGithubDebug -Pripdpi.skipNativeBuild=true -Pripdpi.includeRoborazziUnitTests=true --tests 'com.poyka.ripdpi.ui.screenshot.*' --no-daemon`; `bash scripts/ci/run-rust-workspace-tests.sh`; local `git diff --check`; focused JVM and Appium checks recorded in `docs/feature-test-evidence-2026-05-14.md`; pre-commit hooks passed for the latest local commits | Covered locally | None for local static gates; remote CI is tracked by the remote release gates row |
| Verify local artifacts referenced by the evidence ledger exist | `test-lab/scripts/test-feature-artifact-paths.sh`; `test-lab/scripts/test-feature-artifact-paths-fixtures.sh`; `test-lab/scripts/test-feature-local-artifacts.sh`; `test-lab/scripts/test-feature-local-artifacts-fixtures.sh`; connected-test XML, debug APK, release APK, service unit-test XMLs, and `doctor.json` exist | Covered locally | None for current artifact-path audit; preserve or archive artifacts before cleanup |
| Verify remaining environment readiness | `test-lab/scripts/check-feature-gap-readiness.sh`; `test-lab/scripts/test-feature-gap-readiness.sh`; `test-lab/scripts/check-feature-test-signoff.sh`; `test-lab/scripts/test-feature-test-signoff.sh`; `test-lab/artifacts/feature-gap-readiness.json`; May 15 refresh artifact `test-lab/artifacts/feature-gap-readiness-refresh.json`; `test-lab/README.md` external checklist runbook | Partial | Resolve every readiness item that is `blocked` or `manual` before sign-off |
| Verify rooted behavior | Non-rooted physical degradation, root detector tests, root helper manager tests, native IPC tests; readiness preflight confirms current attached device has no root via `su 0 id` | Partial | Rooted physical-device pass for helper extraction, startup, privileged send operations, readiness timeout, and cleanup |
| Verify physical network matrix | Wi-Fi and private DNS VPN/proxy/diagnostics runs on Pixel 8 Pro; `adb-run-probe.sh` now exposes custom endpoint overrides for routed or public lab hosts; readiness preflight confirms Wi-Fi and cellular are visible but handover still needs a manual or external harness run | Partial | Cellular, Wi-Fi-to-cellular, cellular-to-Wi-Fi, IPv4-only, IPv6-only, captive, and limited-path runs |
| Verify relay provider matrix | Mock relay tests and Rust relay interoperability passed for local fixture coverage; Android debug-probe tests now confirm the client readiness handshake, `relayReady` emission, and hard failure classification; physical proxy, VPN, and diagnostics mock-relay runs emitted `relayReady=true` while the relevant runtime path was active; `check-relay-matrix-config.sh` validates the private matrix manifest shape and emits machine-readable required path/scenario lists, `test-relay-matrix-config.sh` covers duplicate IDs, kind/ID mismatches, duplicate/invalid scenarios, literal endpoint refs, sensitive literal values, and Provider Relay Matrix template parity, and the checked-in example covers all required relay paths/scenarios; readiness preflight confirms no operator-provided provider matrix is configured in the current local environment | Partial | Provider-backed proxy, VPN, diagnostics, restart, invalid credential, reset, timeout, malformed response, DNS fallback, and handover runs for every production relay path |
| Verify accessibility with TalkBack | Automated label audits and Appium selector coverage passed; readiness preflight confirms TalkBack is installed but is not the active accessibility service | Partial | Manual TalkBack pass for buttons, switches, tabs, progress, and error messages |
| Verify routed VM packet-loss lab | Netem scripts passed inside a disposable Linux network namespace; readiness preflight confirms the current host is Darwin and not a routed Linux VM; `test-lab/chaos/netem/README.md` now documents the routed Linux VM evidence run, VPN/diagnostics probes, QUIC-drop probe, cleanup, and required manual evidence fields | Partial | Linux routed-VM run that carries Android or device traffic through the netem path |
| Verify remote release gates | Read-only GitHub Actions refresh on May 15, 2026 showed `origin/main` at `5f8c2636`. CodeQL run `25903839188` passed for that head. CI run `25903839185` is still in progress for that head; its `release-verification` job passed at 06:57:52Z, its `gradle-static-analysis` job failed with Android Lint `Instantiatable` on `.updates.UpdateApkFileProvider`, and two Android integration shards remain in progress. Local commit `e53da9d0` fixes that failure class by adding the GitHub manifest `tools:ignore="Instantiatable"` for the Kotlin-only provider and by making the no-handwritten-Java static-analysis gate configuration-cache safe after a rejected Java-subclass attempt exposed the gate. Focused `:app:lintGithubDebug` and full `staticAnalysis` pass locally on `e53da9d0`. Older Local Network Lab run `25902662966` passed for baseline head `d0b9347e`; scheduled Fuzz Nightly run `25897920791` passed for older head `342a169a`. Latest offline analytics run `25721322098` failed on `3b528850`, and latest mutation-testing run `25655283515` failed on `69defcca`, with local fixes and self-tests recorded in the evidence ledger. A current readiness refresh correctly blocks remote workflow confirmation because the local branch is ahead of `origin/main`; no hosted run covers local commits `59e6df5b`, `e53da9d0`, or `a882ed0e` until they are published through the review-branch, pull-request, merge, and fresh-workflow path | Partial | CodeQL and release verification are green for `5f8c2636`; CI for `5f8c2636` still has two Android integration shards running and a known static-analysis failure fixed locally; publish local commits `59e6df5b`, `e53da9d0`, and `a882ed0e` through the review-branch path, then confirm CI, CodeQL, local-network-lab, offline analytics, mutation-testing, and Fuzz Nightly workflow status for the resulting current commit |

## Current Local State

- Branch: `main`; local HEAD is `e53da9d0`, ahead of `origin/main`, which
  remains at `5f8c2636`.
- Working tree scope: this audit update reflects the latest native
  monitor-engine hotspot split, Roborazzi Logs golden refresh, Appium long-form
  scroll hardening, Appium launch-timeout retry, merged `origin/main`
  fleet-compat changes, task-board legal-framing cleanup, the Appium
  clean-reset diagnostics history cleanup, current installed Appium harness
  fixes for reset History search and support-bundle export-surface cleanup,
  the current `DetectionResolverNetworkStack.kt::readUnsignedShort()`
  architecture-health fix, the debug-install signing-key mismatch guidance,
  the GitHub update-provider lint repair, and the latest test-lab readiness,
  sign-off, and relay-matrix verifier hardening. The routed
  netem runbook now has exact operator steps, but the external Linux routed
  VM evidence remains open. The test-lab README now also gives command-level
  operator steps for rooted physical device, physical network matrix, provider
  relay matrix, TalkBack evidence capture, and remote workflow confirmation.
- Local post-commit checks: `git diff --check`, `cargo fmt --check`,
  `python3 scripts/ci/check_native_hotspot_budgets.py`,
  `python3 scripts/ci/check_architecture_health.py --check`,
  `./gradlew :app:lintGithubDebug -Pripdpi.skipNativeBuild=true --no-daemon`,
  `./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true --no-daemon`,
  `./gradlew :core:diagnostics-data:testDebugUnitTest -Pripdpi.skipNativeBuild=true --no-daemon`,
  `python3 scripts/ci/check_fleet_fixtures.py`,
  `python3 scripts/ci/check_fleet_release_gates.py`,
  `python3 scripts/ci/check_dns_ipv6_killswitch_gates.py`,
  `./gradlew :app:verifyRoborazziGithubDebug -Pripdpi.skipNativeBuild=true -Pripdpi.includeRoborazziUnitTests=true --tests 'com.poyka.ripdpi.ui.screenshot.*' --no-daemon`,
  `bash scripts/ci/run-rust-workspace-tests.sh`,
  `/tmp/ripdpi-appium-venv/bin/python -m compileall appium`,
  `CARGO_TARGET_DIR=target/codex-monitor-engine-check cargo check --manifest-path native/rust/Cargo.toml -p ripdpi-monitor-engine`,
  the monitor-engine `connectivity_partial` contract fixture test, focused
  post-fix Appium slice, the current full Appium suite, the four-flow Maestro
  smoke pack, the May 15 installed-tool Maestro/Appium reruns, the focused
  support-bundle plus diagnostics-empty-state repro, the final current full
  Appium suite, the feature artifact-path self-test and fixture coverage for
  command-embedded plus missing artifact references, the local build-artifact
  self-test and fixture negative-path self-test,
  the feature checklist-coverage self-test with the 248-item checklist baseline
  and fixture negative-path self-test,
  the post-rebase README selector parity and locale key-diff checks,
  the feature-gap readiness self-test including generated-artifact schema
  validation across default/unknown-remote/relay-config paths, malformed-row
  rejection, sign-off required-row parity, and
  unknown remote-compare coverage, dirty-worktree remote-confirmation blocking,
  atomic readiness artifact write coverage, the feature sign-off guard self-test
  including help text, machine-readable required readiness/audit-row output,
  manual evidence template readiness-row parity, required remote-workflow
  evidence rows, remote workflow dispatch-command parity in both operator
  runbooks, audit/evidence blocker and next-action parity, remote-lane coverage
  in the audit and evidence ledger, missing required audit rows, incomplete audit
  result/remaining-evidence cells, stale/missing/future readiness timestamps,
  required `manual` readiness rows, missing required readiness rows, duplicate
  required rows, invalid statuses, and malformed readiness JSON/object/row
  schema, the relay matrix config self-test including template parity,
  the focused debug-probe
  relay-readiness unit test, physical mock-relay
  VPN/proxy/diagnostics probes, proxy E2E runner syntax, the
  service stop-self fallback unit test, the full custom debug-probe broadcast
  verifier, debug/test/main source-set ktlint checks,
  the refreshed architecture-health and native-hotspot gates, the
  local-network-lab validation-only block, and the refreshed host lab doctor
  plus archive-redaction run passed. The May 15 offline analytics unit/sample
  reruns passed under both the host default `python3` and the hosted-workflow
  Python 3.12 runtime. Native fuzz smoke also passed; the fuzz smoke refreshed
  `native/rust/fuzz/Cargo.lock` for the current dependency graph. The mutation
  wrapper self-test also passed after removing the stale default `--jobs auto`
  argument for newer `cargo-mutants`, avoids Bash 4-only `mapfile` and empty
  array expansion in local Bash 3.2 environments, CI now runs that wrapper
  self-test in the unit-test job, and a temporary local `cargo-mutants 27.0.0`
  install exercised the real wrapper path with `--list-files` on
  `ripdpi-strategy-trait`. The broad repository script-test suite now passes
  after refreshing the
  diagnostics-boundary unit-test fixture for the current package-layout gate,
  the production diagnostics boundary verifier still reports 0 violations, and
  the architecture-health/native-hotspot/native-architecture-contract gates are
  green. CI now runs that focused unit test before the diagnostics boundary
  verifier. The sign-off runbook now also
  documents the operator-reviewed readiness JSON input, requires evidence-backed
  `ready` status changes, and the manual evidence template captures review
  branch, pull request, required checks/reviews, readiness JSON, plus the final
  guard command/result. A read-only ruleset refresh confirms `main-protection`
  requires a pull request, review-thread resolution, `build`,
  `static-analysis`, `cli-packet-smoke`, CodeQL Java/Kotlin, CodeQL Actions,
  and CodeQL code-scanning thresholds before merge. A read-only code-scanning
  refresh returned no open alerts and no open CodeQL alerts. Commit hooks passed for the latest
  native/Appium/test-lab/testing-docs commits.
- Remote state: `origin/main` now points at `5f8c2636`. CodeQL run
  `25903839188` passed for that head. CI run `25903839185` is still in
  progress for that head; release verification passed, two Android integration
  shards remain in progress, and `gradle-static-analysis` failed with Android
  Lint `Instantiatable` on the GitHub update APK provider. Local commit
  `e53da9d0` fixes that failure class and passes focused GitHub lint plus full
  `staticAnalysis`, but no hosted run covers the local commits yet. Older Local
  Network Lab run `25902662966` passed on `d0b9347e`; scheduled Fuzz Nightly
  run `25897920791` passed on previous head `342a169a` and is not current-head
  sign-off evidence. Latest offline analytics run `25721322098` failed on
  `3b528850`; latest mutation-testing run `25655283515` failed on `69defcca`.
  Failed-step logs show offline analytics failed on the old head because
  `app/src/main/assets/strategy-packs/catalog.json` was absent, while mutation
  testing failed before starting because `cargo-mutants v27.0.0` rejected
  `--jobs auto`; the local fixes and self-tests for those classes are recorded
  in the evidence ledger.
- Latest readiness preflight: the tracked May 15, 2026 refresh artifact
  `test-lab/artifacts/feature-gap-readiness-refresh.json` confirmed the attached
  Pixel 8 Pro was ready in that run; rooted physical device, TalkBack manual
  pass, routed Linux netem VM, and production relay matrix remained blocked;
  physical handover remained manual. A later local post-commit refresh written
  to `/tmp/ripdpi-feature-gap-readiness-current.json` blocks remote workflow
  confirmation because the local branch is ahead of `origin/main`; that refresh
  also reports no attached adb device currently ready. CodeQL and release
  verification are green for `5f8c2636`, but CI for that head still has two
  Android integration shards running plus a known static-analysis failure fixed
  locally, and no hosted run covers the local commits.

## Stop Rules

Do not mark the application-test objective complete until all of these have
direct evidence:

1. Rooted physical-device root-helper pass.
2. Cellular, handover, IPv4-only, IPv6-only, captive, and limited-path network
   pass.
3. Production relay provider matrix pass.
4. Manual TalkBack pass.
5. Routed Linux VM netem packet-loss pass.
6. Published review branch, merged current commits, plus fresh remote workflows
   covering the resulting commit.

## Next Concrete Actions

1. Complete remote workflow confirmation: publish local commits `59e6df5b`,
   `e53da9d0`, and `a882ed0e` through the review-branch and pull-request path,
   keep CodeQL run
   `25903839188` recorded as passed for baseline commit `5f8c2636`, keep the
   local fix for CI run `25903839185`'s static-analysis failure recorded, then
   dispatch or record fresh CI, CodeQL,
   local-network-lab, offline analytics, mutation-testing, and Fuzz Nightly
   workflows for the resulting current commit. Use `workflow_dispatch` for CI
   optional lanes, Local Network Lab `run_vpn_emulator_lane`, Offline Analytics
   `private_corpus_path` only when needed, Mutation Testing `packages`/`in_diff`
   as appropriate, and Fuzz Nightly `fuzz_seconds`; CodeQL must run via push or
   pull request because it has no manual dispatch.
2. Run the rooted physical-device pass.
3. Run the network lab pass with cellular, handover, IPv4-only, IPv6-only,
   captive, and limited-path coverage.
4. Execute the provider-backed relay matrix one relay path at a time.
5. Run manual TalkBack verification and attach the transcript or screen
   recording reference.
6. Run the routed Linux VM netem scenario and attach the lab archive.

Use `docs/feature-test-manual-evidence-template.md` when recording those manual
or external lab runs so each remaining row has a consistent artifact reference,
result, and retest note.
