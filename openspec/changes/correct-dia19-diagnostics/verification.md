---
task_id: DGN-1794755310955921
change: correct-dia19-diagnostics
commit_sha: 8297887e986d48dcd16cc51d8e3a42ef18836f9d
local: passed
local_evidence: 109 focused core tests, 94 Detection UI tests, three Xray loopback cases, Detection Detekt, Android lint and source gates passed.
remote_ci: required
remote_ci_evidence: CI for the published main commit has not been verified.
device: not_applicable
device_evidence: This is host regression evidence. Android device acceptance remains unverified.
artifact: not_applicable
artifact_evidence: No release artifact was requested.
deployment: not_applicable
deployment_evidence: Git publication only.
---

# Verification

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DIA19-1 | DGN-1794755483004045 | Settings, scheduler, port range, and exclusions. See F1 to F3. | passed |
| REQ-DIA19-2 | DGN-1794755485411376 | Loopback HTTP and gRPC, direct routing, cancellation, deadlines, scan status and progress. See F4, F7 to F9, and F11. | passed |
| REQ-DIA19-3 | DGN-1794755486252489 | Pinned Xray, proc evidence, privacy, and outbound validation. See F5, F6, F10 and F12. | passed |

## Finding evidence

| Finding | Execution step | Evidence |
|---|---|---|
| F1 | DGN-1794755483004045 | Presenter, settings ViewModel and run coordinator tests verify the network switch and the selected ports. |
| F2 | DGN-1794755483771520 | Presenter and Bypass checker tests verify API-only, proxy-only, both enabled and both disabled. |
| F3 | DGN-1794755484652128 | Proxy scanner and Bypass checker tests verify ranges, port exclusions and mode limits. |
| F4 | DGN-1794755485411376 | Loopback HTTP peers exercise complete final 2xx, informational, truncated, rejected and stalled responses. |
| F5 | DGN-1794755486252489 | Verdict tests and pinned Xray show local API evidence without remote endpoint claims for direct and blocked outbounds. |
| F6 | DGN-1794755487144262 | Proc fixtures cover loopback filtering and indirect evidence. Verdict tests verify that a proc finding cannot also count as a direct proxy. |
| F7 | DGN-1794755487781051 | Bypass, verdict and report tests distinguish skipped, no match, rejection, early match and unavailable results. UI tests cover incomplete check labels and counts. |
| F8 | DGN-1794755488351824 | Loopback and gRPC tests verify deadline, cancellation, early match, permanent rejection and the IP follow-up. |
| F9 | DGN-1794755488913381 | IPv4 and IPv6 loopback pass. A test proxy selector cannot route Xray RPCs through an HTTP or SOCKS proxy. |
| F10 | DGN-1794755489493806 | Xray and proc identifiers are covered by privacy tests for report, debug, JSON and Markdown output. |
| F11 | DGN-1794755490098190 | Scanner tests verify settled probe counts when the first match ends a scan. |
| F12 | DGN-1794755490869303 | Parser and gRPC tests cover invalid ports and names, the inbound-message limit and bounded outbound summaries. |

## Primary commits

| Finding | Code commit |
|---|---|
| F1 | `4c68ce44f` |
| F2 | `a62e91a5c` |
| F3 | `592156b90` |
| F4 | `2773aef3d` |
| F5 | `6fe4b5789` |
| F6 | `9913137ae` |
| F7 | `35fa1301e` |
| F8 | `d6586f6e0` |
| F9 | `783150994` |
| F10 | `f7adfe00a` |
| F11 | `55d6ecece` |
| F12 | `a4d413490` |

## Observed checks

The combined check and review step is DGN-1794755491490539.

- Gradle ran 109 focused core tests and all 94 Detection UI tests successfully. Detection Detekt and Android lint for the app, detection and service modules passed.
- `build-gate -- just ci-gates`, locked Cargo metadata, repository task validation and strict OpenSpec validation passed.
- The preceding full core run passed 368 of 369 tests. One case failed in the unchanged `SystemPingProberTest`. The failure is outside the DIA-19 paths. The unmodified ping tests also had varying outcomes in DIA-20 validation.
- Three independent loopback cases passed on integration commit `286f6028f` with Xray `v1.260327.1-0.20261109052709-676eaf66f8f0` and a separate Go gRPC peer. The sessions covered actual HandlerService replies on IPv4 and IPv6, VLESS decoding and export privacy, and one RPC with a deadline for an unauthenticated reply. Later edits kept the same RPC transport and deadline. The parser readability change is covered by the focused tests.
- Independent code review covered scanner outcomes, cancellation, input validation, and the shared DIA-20 report privacy code. The repair also includes the reviewed changes for socket and RPC cleanup.
- The host Gradle checks used `-Pripdpi.skipNativeBuild=true`. Physical Android visibility of proc rows, UIDs and packages and a full device UI run remain unverified.
- JSON export now uses `meta.formatVersion = 2`. The Xray outbound field `transport` replaces the previous `protocol` field, whose value described a transport.

Host tests, Detekt and Android lint ran on `98ec6bf2f`. The rebase onto
`54dc92af8` added shared-priors build configuration and network changes.
The app sources, detection module and Xray protocol sources have the
same Git blobs before and after this rebase.
