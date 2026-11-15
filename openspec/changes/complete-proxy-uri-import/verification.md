---
task_id: OUT-1794722656313653
change: complete-proxy-uri-import
commit_sha: ee25ddb7f6b33d7a6b5508c953720820f4dcda25
local: passed
local_evidence: "publication-v4 passed on the published SHA: 471 Kotlin tests, full staticAnalysis and app/service lint, Simple compile, locked workspace check, native wire 18 accepted and 54 rejected, ci-gates, architecture and metadata. Detailed receipts and immutable JUnit are in /Users/npochaev/GitHub/RIPDPI-worktrees/imp01-evidence/integration/publication-v4."
remote_ci: required
remote_ci_evidence: "Kotlin coverage failed in BackupAllowlistCoverageTest for the added Mieru fields; correction is in progress. Exact-SHA CI run https://github.com/po4yka/RIPDPI/actions/runs/43729212424 and CodeQL run 43729212391 are in progress; Secret Scan run 43729212320 passed. This is not a claim that the CI aggregate passed."
device: not_applicable
device_evidence: No Android UI layout or physical transport acceptance is claimed; JVM Room and native loopback cover the owned contract changes.
artifact: not_applicable
artifact_evidence: This change delivers reviewed source commits, not a packaged or signed application artifact.
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
| REQ-IMP01-MIERU | OUT-1794723333000951 | Presence-aware protobuf and saved share tests pass. Pinned Go vectors and two real Go 1.27.4 loopback cases verify advanced TCP and Off shutdown; 381 native tests and Clippy passed on retained native inputs. | passed |
| REQ-IMP01-BACKUP | OUT-1794756875357718 | Hosted kotlin-coverage found unclassified new Mieru fields; TUIC registration and FULL/SHARE regression checks are pending. | required |

## Verified candidate and publication

Tested and published SHA: `ee25ddb7f6b33d7a6b5508c953720820f4dcda25` (2026-11-15).

All nine `publication-v4` jobs completed with exit 0. Main was fast-forwarded from `184b55708c67f8d5693fd58e6ff29463a114b148` to this exact SHA with a clean status. Push to origin/main completed with exit 0, and `ls-remote` confirmed the exact published SHA. Integration receipts: `main-merge.json` and `hosted-ci-initial.json` under `/Users/npochaev/GitHub/RIPDPI-worktrees/imp01-evidence/integration`.

| Local check | Observed result |
|---|---|
| Metadata repair | Owning pcap-export metadata task passed after an observed missing-output failure. Generated metadata existed before the full gate resumed. No production fix or baseline change was used. |
| Static and explicit Android lint | `staticAnalysis :app:lintGithubFullDebug :core:service:lintDebug`: exit 0, 828 tasks, 7m15s. |
| Simple compile | Exit 0. |
| Expanded Kotlin tests | **471 passed**, 0 failures/errors/skips: runtime-state 84, settings 3, core data 103, engine 20, service 5, app 256. **468 fresh, 3 settings UP-TO-DATE**. |
| Workspace compile | `cargo check --workspace --all-targets --locked --jobs 2`: exit 0. Compile-only evidence. |
| Kotlin-to-Rust wire | Actual Rust session decoder accepted **18 current Kotlin payloads** and rejected **54 invalid schemas**. One contract test passed; 17 unrelated tests were filtered. |
| Offline CI source gates | `just ci-gates`: exit 0. |
| Architecture | Exit 0: 18 existing indicators, 0 new/worsened/stale. |
| Locked metadata | Exit 0. |

Receipts: `integration/publication-v4`. Current JUnit: `publication-v4/junit-verified/summary.json`; immutable automatic snapshot: `publication-v4/kotlin-junit`. The later wire task replaced live engine XML, so counts use the saved Kotlin snapshot.

The earlier selected suite had **447 passed**. Its last publication run had 249 fresh app tests and 198 library tests FROM-CACHE; an earlier rebase run executed all 447 fresh. The current expanded selection adds 24 existing tests and supersedes that total. Do not sum 447 and 471 or call cached results fresh.

## Retained native and concrete peer evidence

- `publication-native-v1`: **381 passed, 0 failed, 2 opt-in peer cases ignored** across Mieru, relay-core and TLS transports. Clippy passed with `--locked --all-targets -- -D warnings`. These receipts apply to their recorded native input scopes; publication-v4 did not rerun the three-crate unit suite.
- Actual root `publication-peer-v1/advanced-peer`: pinned Mieru source `155ebbd60f86e472586a60d7ffe58ec8f8682cb1`, **Go 1.27.4**, 1 passed, 3 filtered.
- Actual root `publication-peer-v1/off-stop-peer`: same pin/toolchain, 1 passed, 259 filtered. Upstream verified two distinct Off carriers and closure of both before server shutdown. This is separate evidence for a case ignored by the general unit run.

Room tests prove durable ImportOnly save, reopen, rollback and later explicit selection. They do not prove a network handshake. A config decoder or workspace compile does not establish a live Android VPN or external TUIC connection.

## Hosted checks and evidence limits

Initial hosted status for the exact published SHA: [CI 43729212424](https://github.com/po4yka/RIPDPI/actions/runs/43729212424) queued; [CodeQL 43729212391](https://github.com/po4yka/RIPDPI/actions/runs/43729212391) running; [Secret Scan 43729212320](https://github.com/po4yka/RIPDPI/actions/runs/43729212320) success. Fleet fixtures, harness checks and L7 adversarial dry-run were also running. These are observations from `hosted-ci-initial.json`, not a claim that all remote checks passed. Use a later exact-SHA receipt before changing those statuses.

The approved diagnostics author evidence remains historical: **110 fresh selected diagnostics/theme tests** for the original three-file correction and **18 fresh tests** for the status-label follow-up, each with independent review and standalone lint passes. Final rebase follows upstream versions of all four diagnostics files, including new DoQ behavior; there is no own production diagnostics diff. The older tests do not establish coverage of added upstream DoQ behavior. No pixel comparison, screenshot acceptance or golden blessing was performed. Current combined static and explicit lint passed.

The official Go 1.27.2 peer gate remains separate from the concrete Go 1.27.4 supplemental observations. Miri was attempted but blocked by unsupported macOS `kqueue`; it did not pass. No new physical/emulator installation, Android ABI packaging, root activation, real-secret use or external server run is covered. API checking retains its explicit Darwin skip for the Linux-owned runtime-platform snapshot.

C means inspected implementation/reachability; V means the named observed execution; S means a specific checked pinned requirement; E means only the named peer/toolchain/scenario. Static, Room and config evidence do not establish network E.
