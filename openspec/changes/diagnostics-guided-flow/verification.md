---
task_id: UIX-1791534456996035
change: diagnostics-guided-flow
commit_sha: 6ce2271e7b33f813e098e969b74d191e663b0b61
local: passed
local_evidence: Final code passed the full app suite with 2277 tests, diagnostics with 1650 tests and model with 39 tests, all with no failures or skips; staticAnalysis and app/service locale lint passed; approved 14 Roborazzi fixtures verified; architecture health had no new or worse indicators; Cargo metadata locked and translation export passed; independent and structured JSON contract reviews had no actionable P0-P2.
remote_ci: required
remote_ci_evidence: "Code run 37927644982 passed build-android-tests, kotlin-coverage, verify-roborazzi and gradle-static-analysis. Its APK size gate failed under the previous policy. The user approved raising native byte-growth limits on 2026-10-09. Policy commit 20df27917 preserves measurements and the 2 percent total cap; the new executable gate passed all eight real CI libraries locally. A combined CI rerun after main integration is pending. No full green CI is claimed."
device: passed
device_evidence: "API35 ARM64 Pixel7 emulator; final code APK 6ce2271e7. Exact Full Approaches instrumentation passed 1 test, 0 failed, 0 skipped in 4.665 seconds. Final Home, Diagnostics, saved Scan results and decoded DNS/stage detail smoke passed, with no crash or ANR. Earlier d32/9e APKs passed real Quick/Full/Manual start and Stop, partial cancelled sessions, guided/Advanced, details and Copy action. Simple APK, full 12-stage completion, VPN/provider path and full clipboard payload were not verified. Final runtime evidence is build/diagnostics-ux/runtime/json-final/README.md."
artifact: passed
artifact_evidence: "Full ARM64 debug APK at code 6ce2271e7b33f813e098e969b74d191e663b0b61 built and installed; SHA256 7c0110dbfeaa5f5ea632645a93fb527aec920c1b55e40848ee4a1d44e6e9d3c3. Eight packaged native file hashes match PMTU CI inputs from run 37916113188 at 03242e367. Simple bundle is absent."
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UX-CONTROLS | UIX-1791534636961461 | Home, scan identity, cancellation and admission tests; real Quick cancel smoke | Passed locally |
| REQ-UX-OWNERSHIP | UIX-1791534636961461 | Composite lease and admission tests; 1648 diagnostics tests | Passed locally |
| REQ-UX-ACTIONS | UIX-1791534640395114 | Saved persona, honest candidate actions, same-network recheck and startup admission tests | Passed locally |
| REQ-UX-EVIDENCE | UIX-1791534644221588 | Matrix, transfer, HTTP3 and PMTU copy tests; ten-locale lint; 14 approved snapshots | Passed locally |
| REQ-UX-CAUSE | UIX-1791534646233026 | 263 detection and 20 Blockcheck tests; combined gates and source review; each code unit pushed on main | Passed locally |

## Implementation and references

The Mobbin references are recorded in `design.md`. Quick and full analysis have a whole-run stop action. Manual scans share the Home admission lease through capture cleanup. Completion messages wait for the matching persisted terminal result and omit cancellation.

Guided mode uses saved persona, concise results and explicit next actions. Advanced opens technical controls. Candidate review opens the actual candidate details. It does not claim to apply a configuration. Verification requires a known, unchanged network. Transfer rows remain available for detail and copy.

## Observed validation

- Combined tree: `:core:diagnostics:testDebugUnitTest` and focused Home/diagnostics app tests, then `:app:lintGithubFullDebug :core:service:lintDebug staticAnalysis`. All passed after rebase onto PMTU commit `03242e367`.
- App tests: 467 passed, 0 failed, 0 skipped. Diagnostics tests: 1648 passed, 0 failed, 0 skipped.
- The exact 14 authorized PNG updates were recorded and verified by the isolated golden reviewer. No other fixtures were changed. Large font and Arabic layouts were inspected.
- Final structured follow-up review: no actionable P0-P2. Independent source and PMTU merge reviews: no P1/P2. The earlier timestamp finding was rejected: the null-progress branch already clears the timestamp, and two tests cover a next scan without a saved row. The lease launch-rejection finding was rejected: `clearRejectedRun` releases the reservation in `NonCancellable` after capture cleanup.
- Code commits on main: `9a17e69ab`, `11a1e2f27`, `d32c87174`, `9e28d0330`. Native extraction and bounded-cause commits are `c2baf9931` and `33f1ffdc3`.
- Appium Python and Maestro YAML contracts were parsed. Their full suites were not run.

## Device and artifact limits

Final Full APK build passed. The exact Full Approaches instrumentation check passed again on `9e28d0330` (1 test, 0 failed, 0 skipped). Real Full UI smoke confirmed quick and full whole-run stop, manual scan stop, guided/Advanced navigation, partial session detail, and the copy action. Final description text was checked on the resource-only APK. The clipboard preview was observed; the complete copied payload is covered by unit tests. The local Simple APK build requires the absent user relay asset `app/src/simple/assets/embedded-relay-bundle.json`. No fixture bundle was used as proof of Simple acceptance. Physical-device and provider-path acceptance are not established by the emulator.

The final description update removes the unsupported automatic-apply promise in all ten locales. At `9e28d0330`, 129 focused UI tests and app/service locale lint passed, with no failures or skips. Structured review was clean. Kotlin logic is unchanged from the 467-test combined run at `d32c87174`.

## Final source gate and inherited CI failure

At `6ce2271e7`, the full app suite passed 2277 tests, diagnostics passed 1650 tests and model passed 39 tests. All had zero failures, errors and skips. App/service locale lint and `staticAnalysis` passed in the same command. Architecture health reported 21 current and 21 baseline indicators, with no new, worse or stale entries. Locked Cargo metadata passed.

CI exposed five private JSON builders from earlier diagnostics work. Commit `6ce2271e7` uses shared JSON instances and preserves each parser's unknown-key, default-value and explicit-null rules. Contract tests cover these rules. Independent and structured reviews reported no actionable P0-P2.

The native size gate already failed on PMTU commit `03242e367`, before the UI commits. Its four `libripdpi.so` sizes and total 78098812 bytes are identical to run `37924895021` on `9e28d0330`. Native source diff between these commits is empty. Arm64 was already 11847288 bytes at extraction commit `c2baf9931`; PMTU added 24608 bytes. The extraction CI passed, but its Android size gate was skipped. A matched build is needed to separate the earlier HTTP3 and extraction contributions. No size limit or baseline was changed.

The native extraction task has a committed terminal record in `5d9c44fe7` and a separate task purge commit. This does not establish release size acceptance. The UI task stays in review until remote CI confirms the approved native size policy.

Final Full and AndroidTest builds passed in 1 minute. On the exact final code APK, Approaches passed 1 test with no failure or skip in 4.665 seconds. Production navigation and saved real DNS results passed a repeat smoke. System and encrypted DNS stage data decoded correctly. The crash buffer was empty and no app ANR or fatal signal was found. Build commands, source hashes, APK hashes, packaged native hashes, PNGs and logs are in `build/diagnostics-ux/runtime/json-final/README.md`.

The exact-source remote CI jobs `build-android-tests`, `kotlin-coverage`, `verify-roborazzi` and `gradle-static-analysis` passed in run [37927644982](https://github.com/po4yka/RIPDPI/actions/runs/37927644982). This records UI and Kotlin gate success, not a green full release pipeline.

Final exact-source APK job [113814874231](https://github.com/po4yka/RIPDPI/actions/runs/37927644982/job/113814874231) completed Github Full assembly successfully in 6 minutes 52 seconds, then failed the native size check. All four library sizes and the total match the pre-UX failure. At that point, remediation required a scoped native investigation or explicit approval to change the size policy. The approved threshold follow-up is recorded below. Other release/device jobs were still running at report time; a documentation push can cancel them through the standard push concurrency rule. They are not counted as device or release acceptance.

## Approved native budget follow-up

The user approved increasing native size limits on 2026-10-09. Commit `20df27917` raises per-library growth from 128 to 384 KiB and total absolute growth from 256 KiB to 1.5 MiB. All September measurements and the 2 percent total cap remain unchanged. The owning verifier passed all eight real libraries from run 37927644982 with the new policy. The previous failures above are historical evidence under the old limits. The fresh combined CI result remains pending. No app or native implementation changed in this follow-up.
