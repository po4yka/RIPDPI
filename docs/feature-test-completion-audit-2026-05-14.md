# Feature Test Completion Audit

Status: **not complete**.

This audit keeps the path expected by `test-lab/scripts/check-feature-test-signoff.sh`, while the current evidence was refreshed on 2026-05-19 from the connected non-rooted Pixel 8 Pro, rooted emulator, and local lab tooling.

## Prompt-to-Artifact Checklist

| Requirement | Current artifact or command | Result |
| --- | --- | --- |
| Use `docs/feature-test-checklist.md` as the source checklist | `docs/feature-test-checklist.md` and `docs/feature-test-evidence-2026-05-14.md` | Used as the coverage inventory. |
| Fix all issues found during the local pass | QA commits through `c1ce7a8e`, including physical packet-smoke and readiness fixes | Local findings were fixed and verified. |
| Verify Appium installation and current app flows | `ANDROID_SERIAL=38080DLJG000GX RUNNER_TEMP=/tmp/ripdpi-appium-20260518 bash scripts/ci/run-appium-smoke.sh` | Passed after installing missing Python test dependencies and fixing one scroll expectation: 78 passed, 18 skipped. |
| Verify Maestro installation and current smoke flows | `run-proxy-e2e.sh` and `run-vpn-e2e.sh` artifacts under `test-lab/artifacts/` | Passed on the Pixel with recoverable Docker Desktop UDP timeout noted. |
| Verify static local quality gates for the current head | `./gradlew :app:testGithubDebugUnitTest -Pripdpi.skipNativeBuild=true` plus hook checks on each commit | Passed. |
| Verify local artifacts referenced by the evidence ledger exist | `test-lab/artifacts/...`, `appium/appium-report.html`, `build/packet-smoke/android/...` | Present locally; generated artifacts remain gitignored. |
| Verify remaining environment readiness | `test-lab/artifacts/feature-gap-readiness-20260519-auto-device-clean.json` | Blocked/manual rows remain. |
| Verify rooted behavior | Readiness probe and manual evidence template | Blocked for rooted physical behavior because the connected Pixel is non-rooted. |
| Verify physical network matrix | Readiness probe and device transport state | Manual cellular handover, IPv4, and IPv6 evidence remain. |
| Verify relay provider matrix | Readiness probe | Blocked until relay provider configuration is supplied. |
| Verify accessibility with TalkBack | Readiness probe | Blocked because TalkBack is installed but inactive. |
| Verify routed VM packet-loss lab | `test-lab/artifacts/netem-container-capability-20260518.txt` | Container capability proved; routed netem Pixel path remains unproven. |
| Verify remote release gates | Required workflow list | Blocked until CI, CodeQL, local-network-lab, offline analytics, mutation-testing, and Fuzz Nightly remote workflow evidence exists. |

## Current Local State

The local non-rooted Pixel pass is healthy for the currently executable surface: app unit tests, Maestro proxy/VPN E2E, physical instrumentation E2E, Android packet-smoke proxy and VPN families, Appium UI/workflow smoke, TSPU adversarial dry-run, and netem container capability all pass after the committed fixes. The remaining blockers are not hidden failures; they are missing environment or operator evidence for rooted physical behavior, TalkBack, cellular handover with IPv4 and IPv6 coverage, routed netem traffic, relay provider coverage, and remote workflow confirmation.

## Requirement Table

| Requirement | Evidence inspected | Result | Remaining evidence required |
| --- | --- | --- | --- |
| Use `docs/feature-test-checklist.md` as the source checklist | Checklist, evidence ledger, and audit table | Covered locally | None |
| Fix all issues found during the local pass | QA commits through `c1ce7a8e` and retest commands | Covered locally | None for locally reproducible issues |
| Verify Appium installation and current app flows | Full Appium smoke after fix: 78 passed, 18 skipped | Covered locally | None for Appium smoke; skipped seeded flows remain non-blocking for this pass |
| Verify Maestro installation and current smoke flows | Proxy and VPN E2E artifacts from 2026-05-18 | Covered locally | None for non-rooted Pixel Maestro smoke |
| Verify static local quality gates for the current head | Full app unit lane and commit hooks | Covered locally | None |
| Verify local artifacts referenced by the evidence ledger exist | Artifact paths listed in `docs/feature-test-evidence-2026-05-14.md` | Covered locally | None |
| Verify remaining environment readiness | `test-lab/artifacts/feature-gap-readiness-20260519-auto-device-clean.json` | Blocked/manual | Rooted physical, TalkBack, cellular handover, IPv4, IPv6, routed netem, relay provider, and remote workflow rows |
| Verify rooted behavior | `adb shell su 0 id` readiness result | Blocked/manual | Rooted physical device evidence |
| Verify physical network matrix | Readiness row and Pixel transport state | Blocked/manual | Cellular handover, IPv4-only, IPv6-only, private-DNS, and limited-path evidence |
| Verify relay provider matrix | Missing `RIPDPI_RELAY_MATRIX_CONFIG` | Blocked/manual | Operator-provided relay provider matrix and full relay provider run |
| Verify accessibility with TalkBack | Accessibility readiness row | Blocked/manual | TalkBack active-session evidence |
| Verify routed VM packet-loss lab | Netem container capability artifact | Blocked/manual | Routed netem VM/router path carrying Pixel traffic |
| Verify remote release gates | Branch state and workflow list | Blocked/manual | CI, CodeQL, local-network-lab, offline analytics, mutation-testing, and Fuzz Nightly remote workflow results |

## Stop Rules

Do not claim release sign-off while rooted physical evidence is absent, TalkBack remains inactive, cellular handover plus IPv4 and IPv6 physical network evidence is missing, routed netem is only proven inside a container and not on the Pixel traffic path, relay provider configuration is unavailable, or remote workflow evidence for CI, CodeQL, local-network-lab, offline analytics, mutation-testing, and Fuzz Nightly has not been collected.

Do not mark the active goal complete from this audit alone. The current local pass proves the non-rooted Pixel and local-tooling slice, but the objective also includes rooted physical/rooted-emulator-adjacent behavior, routed netem, relay provider checks, and remote workflow confirmation where applicable.

## Next Concrete Actions

1. Run rooted physical device evidence or explicitly accept a rooted emulator as supplemental-only evidence while keeping the rooted physical row open.
2. Enable TalkBack and complete the accessibility pass with control labels, state announcements, tabs, progress, errors, and reachability.
3. Execute cellular handover and physical network matrix runs that cover Wi-Fi, cellular, IPv4, IPv6, private DNS, and limited-path behavior.
4. Route Pixel traffic through the Linux routed netem VM/router, then run VPN and diagnostics probes under packet loss and QUIC-drop conditions.
5. Provide the relay provider matrix and run proxy, VPN, diagnostics, restart, invalid credentials, reset, timeout, malformed response, DNS fallback, and handover rows.
6. Publish the branch and collect remote workflow results for CI, CodeQL, local-network-lab, offline analytics, mutation-testing, and Fuzz Nightly.
