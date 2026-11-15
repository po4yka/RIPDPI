---
task_id: OUT-1794722656313653
change: complete-proxy-uri-import
commit_sha: 2f3438a9fc0fda85a4b6f31e118f693d745b07df
local: passed
local_evidence: "All eight final-rebase-v1 jobs passed on this exact SHA: staticAnalysis, explicit app/service lint, 436 fresh affected Kotlin tests, 74 affected Rust tests, locked workspace all-targets compile, actual native wire 18 accepted/54 rejected, ci-gates, architecture and locked metadata. Full coverage on unchanged owned inputs passed 10421 fresh tests on 41129892bd; retained native scope passed 389 tests, Clippy and two pinned Go peers. Receipts: /Users/npochaev/GitHub/RIPDPI-worktrees/imp01-evidence/integration."
remote_ci: required
remote_ci_evidence: The first published candidate failed; its three owned failures are fixed and locally verified. Check the next exact published SHA before acceptance.
device: not_applicable
device_evidence: No device or external server acceptance is claimed. JVM Room, native config and local Go peers cover the owned source contracts.
artifact: not_applicable
artifact_evidence: This change delivers source commits, not a packaged or signed application artifact.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence


| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-IMP01-F1 | OUT-1794723300448448 | ProxyUriCodecTest checks encoded security names, duplicate scalar names and retained raw escaping. | passed |
| REQ-IMP01-F2 | OUT-1794723305001424 | Real Room persistence and transaction tests check repeated imports, reopen, separate selection, export, rollback and cancellation. | passed |
| REQ-IMP01-F3 | OUT-1794723307980598 | Saved Mieru TCP protocol, multiplexing and MTU survive share and parse. | passed |
| REQ-IMP01-F4 | OUT-1794723312688319 | SIP002 SS2022 export matches an independently specified plain percent-encoded userinfo value. | passed |
| REQ-IMP01-F5 | OUT-1794723319097202 | SSH tests preserve literal plus credentials and reject malformed percent values. | passed |
| REQ-IMP01-F6 | OUT-1794723323595628 | Scanner tests assert redacted failures for all twelve URI schemes and aliases. | passed |
| REQ-IMP01-F7 | OUT-1794723325992640 | Shadowsocks scheme case variants reach the parser and return the same profile. | passed |
| REQ-IMP01-TUIC | OUT-1794723329887849 | Typed URI, real Room persistence, explicit selection, native config and lossless share tests pass. No live TUIC peer acceptance is claimed. | passed |
| REQ-IMP01-MIERU | OUT-1794723333000951 | Presence-aware protobuf and saved share tests pass. Pinned Go vectors and two real Go 1.27.4 loopback cases verify advanced TCP and Off shutdown; 389 native tests and Clippy passed on retained native inputs. | passed |
| REQ-IMP01-BACKUP | OUT-1794756875357718 | Upstream REDACTED SHARE policy is retained for credentials and identifying Mieru traffic patterns. FULL retains all 12 presence combinations. The reconciled policy passed 17 fresh backup tests, including sealed-type coverage; independent review approved. | passed |
| REQ-IMP01-ROUNDTRIP | OUT-1794758046683376 | Engine change preserves raw optional values. Fresh RED: 53 tests and 5 assertion failures; GREEN: 53 passed, independent review and hooks passed. Actual runtime normalization remains separate. | passed |
| REQ-IMP01-SELECTION | OUT-1794759937653382 | RED returned old no_wait for selected null. The fix removes only the two known prior Mieru keys before selected-profile merge. The 72-test engine selection set and dedicated 12-pair regression passed; independent review approved. | passed |


## Accepted local source

The final code candidate is `2f3438a9fc0fda85a4b6f31e118f693d745b07df`, rebased without conflicts onto `e7b871df4df26bb72fb3db44fd89b73311387077`. Its tracked source SHA256 is `91c9ef745594bccdc4f8e08eb1c066009c2e5c84146ef267a5b2559177f3c79f`. All eight `final-rebase-v1` jobs passed on that candidate. Each receipt records the exact command, source hash, SHA, time and result.

| Check | Observed result |
|---|---|
| Static analysis and explicit lint | `staticAnalysis :app:lintGithubFullDebug :core:service:lintDebug`: passed. |
| Affected Kotlin tests | 436 fresh passed, zero failures/errors/skips: data 7, engine 354, service 75. Service includes 29 geo tests and 46 runtime cleanup tests. |
| Affected native tests | 74 passed, zero failed/ignored: Android proxy adapter 41 and geo 33. Cargo used `--locked`. |
| Workspace compile | `cargo check --workspace --all-targets --locked --jobs 2`: passed. Compile-only evidence. |
| Actual Kotlin-to-native wire | Current native decoder accepted 18 Kotlin payloads and rejected 54 invalid schemas. One contract test passed, 17 unrelated tests were filtered. |
| Offline source gates | `just ci-gates`: passed. |
| Architecture | 18 retained indicators, zero new, worsened or stale. |
| Locked metadata | Passed. |

Receipts and immutable JUnit: `integration/final-rebase-v1`. Summary: `integration/final-rebase-v1-monitor-metrics.json` under the evidence root in frontmatter.

## Full Kotlin coverage and source invariance

The preceding frozen candidate `41129892bd4107f1eb5242555c6a47a2aecbc9aa` passed all nine `final-frontend-v2` jobs, including `coverageReport` and `kotlinCoverageVerification`. All 13 test tasks executed: **10421 fresh passed, zero failures/errors/skips**, no missing JUnit directories and no JaCoCo mismatch warning. Counts: app 3594, data 1029, catalog 57, model 94, runtime-state 489, settings 99, detection 451, diagnostics 1815, diagnostics-data 101, engine 354, engine-api 77, pcap-export 36, service 2225. The separate quality snapshot was not executed by coverage and is excluded. Simple subscription tests passed 9 fresh cases; PCAP viewer tests passed 7 fresh cases. Do not add the later 436 overlapping cases to 10421.

The later rebase added exactly 46 upstream geo/JNI/asset/task files. It changed no owned URI, backup, Mieru codec, runtime-selection or cleanup implementation. The affected source areas were checked again as shown above. These counts are separate runs, not one fresh run on the final SHA.

## Retained native and concrete peer evidence

`postfix-native-v1` passed six jobs on its recorded source scope: **389 Rust unit tests** (Mieru 82, relay-core 258, TLS transports 49), affected all-target Clippy with warnings denied, locked workspace compile, actual native wire, and two real pinned Go peers. Two opt-in units were ignored in the aggregate. The Mieru Off shutdown case was executed separately; the VLESS REALITY XHTTP XUDP opt-in was not.

The peer source is pinned to `155ebbd60f86e472586a60d7ffe58ec8f8682cb1`, with actual Go **1.27.4**. The advanced TCP scenario passed once; the Off/stop scenario passed once and verified that both Off carriers closed before server shutdown. No external server or physical device was used.

The rebase review parsed the complete normal/dev/build local dependency closure of the three IMP native crates: 34 packages, no geo/proxy-runtime/Android proxy adapter dependency, and zero changed paths or manifests. The lockfile added an existing serde edge only to geo and changed no version. Thus 389/Clippy/Go results retain their named scope; they were not rerun or counted as fresh after this rebase. Proof: `integration/final-frontend-v2-monitor-metrics.json`, `rebase_delta_review`.

## Corrections after first publication

The first published SHA `ee25ddb7f6b33d7a6b5508c953720820f4dcda25` reached main and origin. Its CI run 43729212424 ended cancelled, with three substantive failures: backup allowlist, raw Mieru DTO round trip, and a stale unsupported-TUIC fixture. That candidate is not accepted as hosted GREEN.

The corrected backup policy keeps upstream's stronger SHARE rule: credentials and identifying Mieru traffic patterns are REDACTED; FULL keeps all settings. Seventeen fresh reconciled backup tests passed. The raw UI DTO now retains nullable handshake and traffic-pattern presence; runtime-effective defaults resolve separately. Runtime selection removes only the two prior Mieru override keys before merging the selected profile, including absent values. The negative subscription fixture uses unsupported wireguard rather than supported TUIC.

The inherited disclosure assertion correction changes two test files only; production copy and baselines stay unchanged. The final code retains upstream's equivalent PCAP viewer loop repair and all four diagnostics lint repairs, including newer DoQ behavior. It has no own production diagnostics or PCAP viewer diff. The inherited VPN runtime class repair moves two existing private cleanup helpers to file-private scope and passes the same immutable controller; ordering, cancellation, try/finally and exception behavior remain unchanged. Independent review and 46 fresh owning tests passed before integration, then passed again in combined coverage and affected rebase checks. No lint threshold or baseline was changed.

## Environment recovery and evidence limits

Earlier broad attempts encountered missing generated outputs, ENOSPC and then corrupt app test metadata. These are preserved in external receipts. They do not establish source defects. Only owned generated test binaries were removed after copies were saved; no source or Git work was discarded. The later complete fresh coverage pass supersedes those failed attempts.

Room proves durable ImportOnly save, reopen, rollback and later explicit selection. A config decoder or workspace compile does not prove a live Android VPN or TUIC handshake. C means inspected code, V means the named observed run, S means a checked pinned requirement, and E means only the named peer/toolchain/scenario.

Official Go 1.27.2 peer acceptance remains separate from observed Go 1.27.4. Miri was attempted but stopped at unsupported macOS kqueue, so it did not pass. No physical/emulator install, Android ABI packaging, root activation, real-secret use, external TUIC peer, pixel comparison or golden blessing is claimed. API checking retains its explicit Darwin skip for the Linux-owned runtime-platform snapshot.

## Publication acceptance

New main integration, push and exact-SHA hosted CI remain required at this record's commit. Update this section only with observed receipts. The user explicitly authorized main integration and push.
