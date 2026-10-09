---
task_id: UIX-1791534456996035
change: diagnostics-guided-flow
commit_sha: 9e28d03302a9c7e2ab8f2873b5e4dc2a4e42f708
local: passed
local_evidence: Final ten-locale copy update passed 129 UI tests and locale lint; rebased combined code tree passed 1648 diagnostics tests and 467 app tests with no failures or skips; staticAnalysis and app/service locale lint passed; approved 14 Roborazzi fixtures verified; architecture health had no new or worse indicators; Cargo metadata locked and translation export passed; structured copy review and independent rebase review had no actionable P0-P2.
remote_ci: required
remote_ci_evidence: null
device: passed
device_evidence: "API35 ARM64 Pixel7 emulator; final APK 9e28d0330. Exact Full Approaches instrumentation passed 1 test, 0 failed, 0 skipped. Real Quick/Full/Manual start and Stop, partial cancelled sessions, guided/Advanced, details and Copy action passed. Final text inspected; no crash or ANR. Simple APK, full 12-stage completion, VPN/provider path and full clipboard payload were not verified. Runtime evidence is build/diagnostics-ux/runtime/README.md."
artifact: passed
artifact_evidence: "Full ARM64 debug APK at 9e28d03302a9c7e2ab8f2873b5e4dc2a4e42f708 built and installed; SHA256 b0315db8a284b235c626c5c97dd5738a447a0076b1f5c1ec3fce1d3cd634a421. PMTU JNI artifact comes from CI run 37916113188 at 03242e367. Simple bundle is absent."
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
