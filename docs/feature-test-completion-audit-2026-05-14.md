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
| Use `docs/feature-test-checklist.md` as the source checklist | `docs/feature-test-evidence-2026-05-14.md` maps every major checklist section to `Covered locally` or `Partial` | Partial | Complete the rows that remain `Partial` |
| Fix all issues found during the local pass | `docs/feature-test-evidence-2026-05-14.md` records fixed findings and commit subjects for test-lab, build packaging, diagnostics archive redaction, Appium, Maestro, onboarding, logs, and UI automation defects | Covered locally | None for the bugs found in the local pass |
| Verify Appium installation and current app flows | Appium evidence rows in `docs/feature-test-evidence-2026-05-14.md`; Appium 3.4.2 with UiAutomator2 7.3.0 is installed. After the latest automation hardening, the full Appium suite passed on Pixel 8 Pro with `80 passed, 16 skipped, 1 warning` in 2024.08s. This covers launch/navigation, onboarding/advanced/host-pack, diagnostics/logs/support/theme, diagnostics-tail, deterministic scan report/audit coverage, all 7 workflow journeys, activation-window controls, background guidance, and backup-PIN warning/editor paths. The 16 skips are explicit fixture/environment skips, not unexpected failures | Covered locally | Optional reruns after future UI or Appium page-object changes |
| Verify Maestro installation and current smoke flows | Maestro smoke and default-install fallback rows in `docs/feature-test-evidence-2026-05-14.md`; smoke pack and lab VPN orchestrator passed on Pixel 8 Pro with Maestro resolved from `~/.maestro/bin/maestro` while absent from `PATH`; the latest smoke rerun passed after portrait orientation normalization and the post-diagnostics-preset rerun passed all four committed flows | Covered locally | Optional reruns after future Home, Settings, or lab-runner changes |
| Verify static local quality gates for the current head | `./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true --no-daemon`; local `git diff --check`; focused JVM and Appium checks recorded in `docs/feature-test-evidence-2026-05-14.md`; pre-commit hooks passed for the latest local commits | Covered locally | Remote CI for pushed commits |
| Verify local artifacts referenced by the evidence ledger exist | Local artifact-path audit over `test-lab/artifacts/`; connected-test XML, debug APK, release APK, and `doctor.json` exist | Covered locally | Preserve or archive artifacts before cleanup |
| Verify remaining environment readiness | `test-lab/scripts/check-feature-gap-readiness.sh`; `test-lab/artifacts/feature-gap-readiness.json` | Partial | Resolve every readiness item that is `blocked` or `manual` before sign-off |
| Verify rooted behavior | Non-rooted physical degradation, root detector tests, root helper manager tests, native IPC tests; readiness preflight confirms current attached device has no root via `su 0 id` | Partial | Rooted physical-device pass for helper extraction, startup, privileged send operations, readiness timeout, and cleanup |
| Verify physical network matrix | Wi-Fi and private DNS VPN/proxy/diagnostics runs on Pixel 8 Pro; `adb-run-probe.sh` now exposes custom endpoint overrides for routed or public lab hosts; readiness preflight confirms Wi-Fi and cellular are visible but handover still needs a manual or external harness run | Partial | Cellular, Wi-Fi-to-cellular, cellular-to-Wi-Fi, IPv4-only, IPv6-only, captive, and limited-path runs |
| Verify relay provider matrix | Mock relay tests and Rust relay interoperability passed for local fixture coverage; `check-relay-matrix-config.sh` validates the private matrix manifest shape and the checked-in example covers all required relay paths/scenarios; readiness preflight confirms no operator-provided provider matrix is configured in the current local environment | Partial | Provider-backed proxy, VPN, diagnostics, restart, invalid credential, reset, timeout, malformed response, DNS fallback, and handover runs for every production relay path |
| Verify accessibility with TalkBack | Automated label audits and Appium selector coverage passed; readiness preflight confirms TalkBack is installed but is not the active accessibility service | Partial | Manual TalkBack pass for buttons, switches, tabs, progress, and error messages |
| Verify routed VM packet-loss lab | Netem scripts passed inside a disposable Linux network namespace; readiness preflight confirms the current host is Darwin and not a routed Linux VM | Partial | Linux routed-VM run that carries Android or device traffic through the netem path |
| Verify remote release gates | Read-only GitHub Actions check on May 14, 2026 shows `origin/main` at `342a169a` has CI run `25875963396` completed with failure in `architecture-health`, `verify-roborazzi`, `rust-workspace-tests`, and `gradle-static-analysis`; CodeQL run `25875963413` passed for the same remote head. The local branch diverges from `origin/main` after `git fetch --prune origin`, so no remote workflow covers the local commits | Partial | Reconcile the branch, push or dispatch fresh workflows for the local commits, then confirm CI, CodeQL, offline analytics, and mutation workflow status |

## Current Local State

- Branch: `main`; `origin/main` was merged locally, and the local branch is
  now ahead of `origin/main` by 15 commits with no behind count.
- Working tree scope: this audit update reflects the latest native
  monitor-engine hotspot split, Roborazzi Logs golden refresh, Appium long-form
  scroll hardening, Appium launch-timeout retry, merged `origin/main`
  fleet-compat changes, task-board legal-framing cleanup, and the
  `DetectionResolverNetworkStack.kt` architecture-health fix.
- Local post-commit checks: `git diff --check`, `cargo fmt --check`,
  `python3 scripts/ci/check_native_hotspot_budgets.py`,
  `python3 scripts/ci/check_architecture_health.py --check`,
  `./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true --no-daemon`,
  `./gradlew :core:diagnostics-data:testDebugUnitTest -Pripdpi.skipNativeBuild=true --no-daemon`,
  `python3 scripts/ci/check_fleet_fixtures.py`,
  `python3 scripts/ci/check_fleet_release_gates.py`,
  `python3 scripts/ci/check_dns_ipv6_killswitch_gates.py`,
  `/tmp/ripdpi-appium-venv/bin/python -m compileall appium`,
  `CARGO_TARGET_DIR=target/codex-monitor-engine-check cargo check --manifest-path native/rust/Cargo.toml -p ripdpi-monitor-engine`,
  the monitor-engine `connectivity_partial` contract fixture test, focused
  post-fix Appium slice, and the four-flow Maestro smoke pack passed. Commit
  hooks passed for the latest native/Appium commits.
- Remote state: `origin/main` now points at `342a169a`. CI run `25875963396`
  is not a green sign-off because it completed with failed jobs in
  `architecture-health`, `verify-roborazzi`, `rust-workspace-tests`, and
  `gradle-static-analysis`; CodeQL run `25875963413` passed for that remote
  head. No remote run covers the local commits.
- Latest readiness preflight: attached Pixel 8 Pro is ready; rooted physical
  device, TalkBack manual pass, routed Linux netem VM, production relay matrix,
  and remote workflow confirmation remain blocked; physical handover remains
  manual. The remote workflow item is blocked because local `HEAD` `8807b2c9`
  is ahead of `origin/main` by 15 commits and no fresh workflow covers the
  local commits.

## Stop Rules

Do not mark the application-test objective complete until all of these have
direct evidence:

1. Rooted physical-device root-helper pass.
2. Cellular, handover, IPv4-only, IPv6-only, captive, and limited-path network
   pass.
3. Production relay provider matrix pass.
4. Manual TalkBack pass.
5. Routed Linux VM netem packet-loss pass.
6. Fresh remote workflows covering the local commits.

## Next Concrete Actions

1. Push or create a branch from the locally reconciled head, then dispatch
   fresh remote workflows.
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
