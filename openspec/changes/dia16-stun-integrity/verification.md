---
task_id: DGN-1794747967332880
change: dia16-stun-integrity
commit_sha: 35c4600f61924fd127dc90a31500e59aca939038
local: passed
local_evidence: Final rebased revision passed 364 core tests, 92 app detection tests, full staticAnalysis, detection lint/detekt/ktlint, just ci-gates, architecture health and locked Cargo metadata. Independent review has no open findings.
remote_ci: required
remote_ci_evidence: Exact pushed revision has not yet been checked in hosted CI.
device: not_applicable
device_evidence: Physical-device and public-network probes are outside the authorized acceptance scope. No Android VPN path acceptance is claimed.
artifact: not_applicable
artifact_evidence: No APK or release artifact is owned by this change. JVM gates skip native packaging.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DIA16-CONSENT | DGN-1794748226407078 | DetectionResultPresenterTest checks that the network master switch disables WebRTC STUN. The checker retains its disabled early return. | Passed locally |
| REQ-DIA16-PATH | DGN-1794748227184168 | CallTransportLeakCheckerTest checks APP_DEFAULT ownership and an explicit empty capability set. VPN and UNDERLYING require adapters that own those paths. | Passed locally |
| REQ-DIA16-WIRE | DGN-1794748228335587 | StunBindingResponseDecoderTest uses independent RFC 5769 IPv4 and IPv6 bytes and expected addresses. It rejects truncated bodies, invalid suffixes, address lengths, unknown required attributes, wrong cookies and transactions. | Passed locally |
| REQ-DIA16-PEER | DGN-1794748229393696 | StunTransportPeerTest uses real loopback UDP and SOCKS control peers. It checks unrelated senders and transactions, malformed envelopes, and oversized datagrams with a valid prefix. Existing SOCKS IPv6 coverage remains active. | Passed locally |
| REQ-DIA16-CLASSIFY | DGN-1794748230661569 | CallTransportLeakCheckerTest checks neutral exit differences, numeric address equivalence and separate path consensus. Different addresses alone do not prove a leak. | Passed locally |
| REQ-DIA16-OUTCOME | DGN-1794748232537445 | WebRTC and call checker tests, DetectionMeasurementStatusTest, StealthScoreTest and app detection tests check shared execution status, incomplete results and completed-probe counts. Upstream UI and export consumers use CheckExecutionStatus. | Passed locally |
| REQ-DIA16-CANCEL | DGN-1794748233510189 | StunProbeCancellationTest uses waiting loopback UDP/control connections and bounded blocking DNS admission. Cancellation completes within one second and does not start the next request. | Passed locally |

## Implementation units

| Finding | Commit | Correction |
|---|---|---|
| F1 | 9c8d9b72ab | Honor the network master switch. |
| F2 | 7e4a24b321 | Record the actual path and preserve empty capabilities. |
| F3 | c55f4a410a | Validate the whole STUN response before extraction. |
| F4 | 937784b877 | Correlate UDP peers and validate SOCKS envelopes. |
| F5 | 6e775354f9 | Keep address observations separate from proof of a leak. |
| F6 | 81fede34af | Preserve incomplete evaluation through the shared execution status. |
| F7 | 26846a5909 | Close cancelled sockets and bound DNS/network stages. |

Two following refactor commits simplify validation to meet existing complexity gates. They do not weaken validation. No dependency, locale, golden or quality baseline was changed.

## Observed local validation

The final code revision is 35c4600f61924fd127dc90a31500e59aca939038, based on origin/main 4e276953e66692b8f6ae6fa727860b0ff5e6b4de. Heavy commands use the machine build gate and at most four Gradle workers.

- The unfiltered `:core:detection:testDebugUnitTest` run passed: 61 classes, 364 tests, no failures, errors or skipped tests.
- The final app detection package run passed: 14 classes, 92 tests, no failures, errors or skipped tests.
- The earlier import-only integration also passed its full gates. The final DIA-20 rebase required two shared-section resolutions: retain hasIncompleteChecks with directSigns/TLS coverage, and retain the upstream probe counter rules while adding WebRTC/Call Transport. All listed gates and both suites were then repeated on the final revision.
- The first final core retry failed because the disk was full. After preserving the app XML evidence, only this worktree's disposable app/build directory was removed. The unfiltered core retry then passed. No source or validation rule was changed to address the environment failure.

The commands below ran under `build-gate -- python3 -u <local driver>`. The driver records each exit code and copies the test XML into this worktree's ignored build/dia16-results directory. It does not filter the core suite. The app filter selects the owning detection package only.

```sh
env -u CARGO_BUILD_JOBS ./gradlew :core:detection:testDebugUnitTest --max-workers=4 -Pripdpi.nativeCpuBudget=2 -Pripdpi.skipNativeBuild=true --console=plain
env -u CARGO_BUILD_JOBS ./gradlew :app:testGithubFullDebugUnitTest --tests 'com.poyka.ripdpi.ui.screens.detection.*' --max-workers=4 -Pripdpi.nativeCpuBudget=2 -Pripdpi.skipNativeBuild=true --console=plain
env -u CARGO_BUILD_JOBS ./gradlew :core:detection:detekt :core:detection:ktlintCheck :core:detection:lintDebug staticAnalysis --max-workers=4 -Pripdpi.nativeCpuBudget=2 -Pripdpi.skipNativeBuild=true --console=plain --continue
just ci-gates
python3 scripts/ci/check_architecture_health.py
cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1
```

All listed commands passed on the final code revision. Full staticAnalysis completed 827 tasks in 3m 56s and covered GithubFull and GithubSimple lint. Architecture health reported 18 existing indicators, no new or worsened indicators and no stale entries. Rust API snapshots matched; the owning script skipped the Linux-owned runtime-platform snapshot on Darwin. Locked Cargo metadata passed. Cargo.lock, dependency manifests, quality baselines and golden fixtures have no change in the DIA-16 diff.

The source gates and tests use disposable outputs. The final command logs and copied XML are under the integration worktree's ignored build directory. Tests use `ripdpi.skipNativeBuild=true`; no native binary, APK or device acceptance is implied.

## Independent review

The read-only pr-reviewer checked F1–F7 as separate slices and on the combined tree. Review found two additional P2 gaps: an oversized UDP datagram could be truncated into a valid prefix, and skipped Call Transport could render as OK in Markdown. Both were corrected and reviewed again.

The final seam review on 35c4600f61924fd127dc90a31500e59aca939038 against 4e276953e66692b8f6ae6fa727860b0ff5e6b4de found no open correctness or integration findings. It confirmed the single upstream CheckExecutionStatus owner, path attribution, strict framing, peer checks, cancellation budgets, numeric normalization and path-specific consensus. The DIA-20 resolution review also confirmed preserved direct/TLS incomplete coverage, the direct-sign completion guard and exclusion of TLS from network probe counts. No conflict markers or unmerged entries remained.

## Evidence limits and remaining acceptance

- C: The complete source route and consumers were checked. The original audit is /Users/npochaev/Downloads/RIPDPI_network_audits/DIA-16.md. That historical report and the registry were not edited.
- V: Host JVM tests and local loopback network fixtures establish only the stated behavior. They do not establish Android VPN routing. No physical device, external STUN server, root activation or real secret was used.
- S: The audit checked the relevant RFC 8489, RFC 5769 and SOCKS5 RFC 1928 requirements. Pinned independent Pion STUN v0.6.1 (e56f1f82cd67337fd13713c396d4e942d10c5c36) and RFC vectors served as wire oracles. This is not a claim of complete STUN conformance or authenticated-server identity.
- E: No concrete external service interoperability run was made. This remains unverified.

Controlled Android VPN and underlying adapters remain unsupported by the default app socket. Adding them, ICE/TURN, authenticated STUN, SOCKS credentials, server failover and full retransmission policy are separate scope extensions. Unauthenticated STUN reachability is not proof of a leak. The one-shot probe remains a bounded observation; no response is inconclusive.

If device acceptance is later authorized, run the real VPN with its own-UID exclusion and confirm APP_DEFAULT attribution, cancellation and no STUN/DNS work when Network requests is off. Controlled VPN/underlying comparison needs adapters that bind and protect their sockets. If external acceptance is authorized, record each selected server, address family, path, request transaction and validated response. Check hosted CI on the exact pushed revision separately.
