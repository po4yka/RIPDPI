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
| Verify Appium installation and current app flows | Appium evidence rows in `docs/feature-test-evidence-2026-05-14.md`; focused reruns passed on Pixel 8 Pro | Covered locally | Optional reruns after future UI changes |
| Verify Maestro installation and current smoke flows | Maestro smoke row in `docs/feature-test-evidence-2026-05-14.md`; smoke pack passed on Pixel 8 Pro | Covered locally | Optional reruns after future Home or Settings changes |
| Verify static local quality gates for the rebased head | `./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true --no-daemon`; `git diff --check origin/main...HEAD`; focused JVM tests for automation and onboarding | Covered locally | Remote CI for pushed commits |
| Verify local artifacts referenced by the evidence ledger exist | Local artifact-path audit over `test-lab/artifacts/`; connected-test XML, debug APK, release APK, and `doctor.json` exist | Covered locally | Preserve or archive artifacts before cleanup |
| Verify remaining environment readiness | `test-lab/scripts/check-feature-gap-readiness.sh`; `test-lab/artifacts/feature-gap-readiness.json` | Partial | Resolve every readiness item that is `blocked` or `manual` before sign-off |
| Verify rooted behavior | Non-rooted physical degradation, root detector tests, root helper manager tests, native IPC tests; readiness preflight confirms current attached device has no root via `su 0 id` | Partial | Rooted physical-device pass for helper extraction, startup, privileged send operations, readiness timeout, and cleanup |
| Verify physical network matrix | Wi-Fi and private DNS VPN/proxy/diagnostics runs on Pixel 8 Pro; `adb-run-probe.sh` now exposes custom endpoint overrides for routed or public lab hosts; readiness preflight confirms Wi-Fi and cellular are visible but handover still needs a manual or external harness run | Partial | Cellular, Wi-Fi-to-cellular, cellular-to-Wi-Fi, IPv4-only, IPv6-only, captive, and limited-path runs |
| Verify relay provider matrix | Mock relay tests and Rust relay interoperability passed for local fixture coverage; `check-relay-matrix-config.sh` validates the private matrix manifest shape and the checked-in example covers all required relay paths/scenarios; readiness preflight confirms no operator-provided provider matrix is configured in the current local environment | Partial | Provider-backed proxy, VPN, diagnostics, restart, invalid credential, reset, timeout, malformed response, DNS fallback, and handover runs for every production relay path |
| Verify accessibility with TalkBack | Automated label audits and Appium selector coverage passed; readiness preflight confirms TalkBack is installed but is not the active accessibility service | Partial | Manual TalkBack pass for buttons, switches, tabs, progress, and error messages |
| Verify routed VM packet-loss lab | Netem scripts passed inside a disposable Linux network namespace; readiness preflight confirms the current host is Darwin and not a routed Linux VM | Partial | Linux routed-VM run that carries Android or device traffic through the netem path |
| Verify remote release gates | Read-only `origin/main` CI and CodeQL are green at `eabedd2a`; local branch has commits ahead of `origin/main` | Partial | Push or dispatch fresh workflows for the local commits, then confirm CI, CodeQL, offline analytics, and mutation workflow status |

## Current Local State

- Branch: `main`, with local commits ahead of `origin/main`.
- Working tree: clean at the time of this audit.
- Local post-rebase checks: static analysis, focused JVM automation tests, and
  whitespace diff check passed.
- Remote state: `origin/main` CI and CodeQL are green, but those runs do not
  cover the local commits ahead of `origin/main`.

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

1. Push the local commits or create a branch and dispatch fresh remote workflows.
2. Run the rooted physical-device pass.
3. Run the network lab pass with cellular, handover, IPv4-only, IPv6-only,
   captive, and limited-path coverage.
4. Execute the provider-backed relay matrix one relay path at a time.
5. Run manual TalkBack verification and attach the transcript or screen
   recording reference.
6. Run the routed Linux VM netem scenario and attach the lab archive.
