# Feature Test Completion Audit

Status: **not complete**.

This audit keeps the path expected by `test-lab/scripts/check-feature-test-signoff.sh`, while the current evidence was refreshed on 2026-05-19 from the connected non-rooted Pixel 8 Pro and local lab tooling, then updated at PR head `e6a57057`. Earlier physical-device artifacts remain valid for the recorded pass, but the latest continuation re-check found no Pixel or other adb device visible through adb or USB, so physical-only rows were not refreshed. The `RIPDPI_Root_API34` AVD is now Magisk-rooted with app-granting `su`, and the opt-in root-helper lifecycle smoke passes there; the rooted physical-device release row remains open because the connected Pixel evidence is non-rooted and a rooted physical device is still unavailable.

## Prompt-to-Artifact Checklist

| Requirement | Current artifact or command | Result |
| --- | --- | --- |
| Use `docs/feature-test-checklist.md` as the source checklist | `docs/feature-test-checklist.md` and `docs/feature-test-evidence-2026-05-14.md` | Used as the coverage inventory. |
| Fix all issues found during the local pass | QA commits through `e6a57057`, including physical packet-smoke, readiness, root-helper launch fallback, macOS host UDP/DNS, VPN readiness-contract fixes, root-helper smoke diagnostics, Docker netem readiness reporting, root-helper IPC lifecycle hardening, Android network E2E stabilization, diagnostics scan gating, mutation harness fixture exposure, and TSPU dry-run command repair | Local findings were fixed and verified where the required device or lab was available. |
| Verify Appium installation and current app flows | `ANDROID_SERIAL=38080DLJG000GX RUNNER_TEMP=/tmp/ripdpi-appium-20260518 bash scripts/ci/run-appium-smoke.sh` | Passed after installing missing Python test dependencies and fixing one scroll expectation: 78 passed, 18 skipped. |
| Verify Maestro installation and current smoke flows | `run-proxy-e2e.sh` and `run-vpn-e2e.sh` artifacts under `test-lab/artifacts/` | Passed on the Pixel with DNS, HTTP, HTTPS, TCP, UDP, relay readiness, and mode-specific service readiness green; only Android debug QUIC probing remains explicitly unsupported. |
| Verify static local quality gates for the current head | `./gradlew :app:testGithubDebugUnitTest -Pripdpi.skipNativeBuild=true` plus hook checks on each commit | Passed. |
| Verify local artifacts referenced by the evidence ledger exist | `test-lab/artifacts/...`, `appium/appium-report.html`, `build/packet-smoke/android/...` | Present locally; generated artifacts remain gitignored. |
| Verify remaining environment readiness | `test-lab/artifacts/feature-gap-readiness-20260519-netem-aware.json` and `/tmp/ripdpi-feature-gap-readiness-qa-current.json` | Blocked/manual rows remain; the latest continuation has no attached adb device, while routed netem is manual because the Docker netem container is running but not proven in the device traffic path. |
| Verify rooted behavior | Readiness probe, `RootHelperManagerTest`, and current opt-in `RootHelperInstrumentedTest` on Magisk-rooted `RIPDPI_Root_API34` | Partial: the app-granting rooted emulator lifecycle smoke passes and cleans up without a helper leak; rooted physical evidence remains blocked because the connected Pixel is non-rooted. |
| Verify physical network matrix | Readiness probe and device transport state | Manual cellular handover, IPv4, and IPv6 evidence remain. |
| Verify relay provider matrix | Readiness probe | Blocked until relay provider configuration is supplied. |
| Verify accessibility with TalkBack | Readiness probe | Blocked because TalkBack is installed but inactive. |
| Verify routed VM packet-loss lab | `test-lab/artifacts/netem-container-capability-20260519-current.txt` plus `test-lab/artifacts/feature-gap-readiness-20260519-netem-aware.json` | Container capability and readiness detection are current; routed netem Pixel path remains unproven. |
| Verify remote release gates | PR #117 green check rollup at `e6a57057` plus required workflow list | Partial: CI, CodeQL, Android network E2E, release verification, static analysis, Android instrumentation, Rust, coverage, relay interoperability, TSPU, and packet smoke are green on the draft PR; merge-to-main confirmation plus local-network-lab, offline analytics, mutation-testing, and Fuzz Nightly evidence remain. |

## Current Local State

The local non-rooted Pixel pass is healthy for the surface that was executable while the Pixel was attached: app unit tests, Maestro proxy/VPN E2E, physical instrumentation E2E, Android packet-smoke proxy and VPN families, Appium UI/workflow smoke, adversarial middlebox dry-run, root-helper launch fallback unit coverage, current opt-in root-helper instrumentation on the Magisk-rooted `RIPDPI_Root_API34` AVD, rooted-emulator packet smoke in indirect capture mode, relay example validation, and current netem container capability/readiness reporting all pass after the committed fixes. The rooted-emulator result is explicit: app-context `su` is granted, helper extraction and socket readiness pass, and cleanup leaves no `ripdpi-root-helper` process or socket file. This still does not close the rooted physical row unless the emulator substitution is explicitly accepted. The latest continuation re-check is also explicit: no physical Pixel or other adb device is currently visible, so Pixel, TalkBack, and physical handover rows cannot be refreshed. The routed-netem result remains explicit: the `ripdpi-linux-netem` container is running with `tc`, but no evidence proves Pixel traffic is routed through it. The remaining blockers are not hidden failures; they are missing environment or operator evidence for rooted physical acceptance, active TalkBack, cellular handover with IPv4 and IPv6 coverage, routed netem traffic, relay provider coverage, and final main-branch workflow confirmation.

## Requirement Table

| Requirement | Evidence inspected | Result | Remaining evidence required |
| --- | --- | --- | --- |
| Use `docs/feature-test-checklist.md` as the source checklist | Checklist, evidence ledger, and audit table | Covered locally | None |
| Fix all issues found during the local pass | QA commits through `e6a57057` and retest commands | Covered locally | None for locally reproducible issues with the currently available lab/device set |
| Verify Appium installation and current app flows | Full Appium smoke after fix: 78 passed, 18 skipped | Covered locally | None for Appium smoke; skipped seeded flows remain non-blocking for this pass |
| Verify Maestro installation and current smoke flows | Proxy and VPN E2E artifacts from 2026-05-19 | Covered locally | None for non-rooted Pixel Maestro smoke |
| Verify static local quality gates for the current head | Full app unit lane and commit hooks | Covered locally | None |
| Verify local artifacts referenced by the evidence ledger exist | Artifact paths listed in `docs/feature-test-evidence-2026-05-14.md` | Covered locally | None |
| Verify remaining environment readiness | `test-lab/artifacts/feature-gap-readiness-20260519-netem-aware.json` and `/tmp/ripdpi-feature-gap-readiness-qa-current.json` | Blocked/manual | A currently attached physical Pixel, rooted physical, TalkBack, cellular handover, IPv4, IPv6, routed netem, relay provider, and final remote workflow rows |
| Verify rooted behavior | `adb shell su 0 id`, `RootHelperManagerTest`, and current opt-in `RootHelperInstrumentedTest` on Magisk-rooted `RIPDPI_Root_API34` | Partial | Rooted physical device evidence remains unless the app-granting rooted-emulator substitution is explicitly accepted; privileged packet-action evidence should be expanded beyond helper lifecycle |
| Verify physical network matrix | Readiness row and Pixel transport state | Blocked/manual | Cellular handover, IPv4-only, IPv6-only, private-DNS, and limited-path evidence |
| Verify relay provider matrix | Missing `RIPDPI_RELAY_MATRIX_CONFIG` | Blocked/manual | Operator-provided relay provider matrix and full relay provider run |
| Verify accessibility with TalkBack | Accessibility readiness row | Blocked/manual | TalkBack active-session evidence |
| Verify routed VM packet-loss lab | Netem container capability artifact and netem-aware readiness row | Blocked/manual | Routed netem VM/router path carrying Pixel traffic |
| Verify remote release gates | PR #117 green check rollup at `e6a57057`, branch state, and workflow list | Partial | Merge-to-main confirmation plus local-network-lab, offline analytics, mutation-testing, and Fuzz Nightly remote workflow results |

## Stop Rules

Do not claim release sign-off while no physical Pixel is attached for fresh device rows, rooted physical evidence or explicit acceptance of the app-granting rooted-emulator substitution is absent, TalkBack remains inactive, cellular handover plus IPv4 and IPv6 physical network evidence is missing, routed netem is only proven inside a container and not on the Pixel traffic path, relay provider configuration is unavailable, or final main-branch workflow evidence for CI, CodeQL, local-network-lab, offline analytics, mutation-testing, and Fuzz Nightly has not been collected.

Do not mark the active goal complete from this audit alone. The current local pass proves the non-rooted Pixel and local-tooling slice plus app-granting rooted-emulator helper lifecycle, but the objective also includes rooted physical acceptance, routed netem, relay provider checks, and remote workflow confirmation where applicable.

## Next Concrete Actions

1. Run rooted physical device evidence, or explicitly accept the app-granting Magisk `RIPDPI_Root_API34` emulator substitution and add privileged packet-action artifacts beyond helper lifecycle.
2. Enable TalkBack and complete the accessibility pass with control labels, state announcements, tabs, progress, errors, and reachability.
3. Execute cellular handover and physical network matrix runs that cover Wi-Fi, cellular, IPv4, IPv6, private DNS, and limited-path behavior.
4. Route Pixel traffic through the Linux routed netem VM/router, then run VPN and diagnostics probes under packet loss and QUIC-drop conditions.
5. Provide the relay provider matrix and run proxy, VPN, diagnostics, restart, invalid credentials, reset, timeout, malformed response, DNS fallback, and handover rows.
6. Complete PR #117 review, merge the branch, and collect main-branch workflow results for CI, CodeQL, local-network-lab, offline analytics, mutation-testing, and Fuzz Nightly so the `remote_workflow_confirmation` readiness row can move to ready.
