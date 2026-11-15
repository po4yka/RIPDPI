---
task_id: DAT-1794720910687853
change: subscription-import-integrity
commit_sha: e6b124f0e0a105b46d501c1d53cee58b582fe6ea
local: required
local_evidence: IMP-03 regressions passed. Final five owning tests, diagnostics lint and ktlint, app compilation, source gates, locked metadata and task contracts passed. Module detekt is blocked by sixteen inherited DIA-15 issues outside the owned patch.
remote_ci: required
remote_ci_evidence: Pending exact published main checks; no green CI claim is made.
device: not_applicable
device_evidence: JVM and loopback coverage only; no device or native transport acceptance is claimed.
artifact: not_applicable
artifact_evidence: No binary distribution or generated fixture is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-IMP03-F1 | DAT-1794721000630817 | PackageRoutePredicateAdmissionTest: reject conditional and inverted package routes; equivalent whole-app route remains supported. | passed in scoped JVM suites |
| REQ-IMP03-F2 | DAT-1794721001131546 | ClashTrojanOptionsAdmissionTest: reject certificate pins, ALPN, SS options, fingerprint, dialer proxy, and Reality constraints. | passed in scoped JVM suites |
| REQ-IMP03-F3 | DAT-1794721001621736 | SingBoxDetourAdmissionTest: reject nonempty or malformed detour; preserve absent or empty detour. | passed in scoped JVM suites |
| REQ-IMP03-F4 | DAT-1794721002101586 | XrayShadowsocksSecurityImportTest: reject unsupported security and stream options; retain plain TCP/raw. | passed in scoped JVM suites |
| REQ-IMP03-F5 | DAT-1794721002583546 | SingBoxMalformedOutboundTest: reject scalar, missing, blank, or non-string outbound types atomically. | passed in scoped JVM suites |
| REQ-IMP03-F6 | DAT-1794721003263346 | BootstrapAdmissionTest and BootstrapRejectedImportTest: strict admission, bounded call, terminal receipt, cross-group joiners, and concurrent edit/delete/retarget preservation. | passed in scoped JVM suites |
| REQ-IMP03-F7 | DAT-1794721003916259 | XrayPasswordCredentialImportTest: pinned flat-credential precedence, malformed flat rejection, and removed Trojan flow rejection. | passed in scoped JVM suites |
| REQ-IMP03-F8 | DAT-1794721004414691 | BootstrapSelectorMembershipTest: preserve resolved selected members and order; reject external migration targets. | passed in scoped JVM suites |
| REQ-IMP03-F9 | DAT-1794721004924780 | SelectorUrltestPolicyTest: explicit valid URL required; omitted/zero cadence and tolerance defaults; malformed and overflowing values rejected. | passed in scoped JVM suites |
| REQ-IMP03-F10 | DAT-1794721005420338 | SelectorInitialPreferenceImportTest, SelectorInitialPreferenceTest, SubscriptionInitialPreferenceTest, and BackupGroupRedactionTest: nested default remapping, Room persistence, explicit override/clear, SHARE redaction, and FULL retention. | passed in scoped JVM suites |
| REQ-IMP03-F11 | DAT-1794721005921256 | SubscriptionPayloadEncodingTest, XrayBase64EncodingImportTest, and SubscriptionEncodingRefreshTest: reporting text decoder, terminal bootstrap error, and preservation of saved members/routes. | passed in scoped JVM suites |
| REQ-IMP03-H3 | DAT-1794721832362283 | SubscriptionDeclaredPolicyTest: explicit manual selector clears previous automatic policy; absent declarations retain it. | passed in scoped JVM suites |
| REQ-IMP03-H2 | DAT-1794722137487267 | Real-parser host probe plus XrayImportBudgetTest, XrayProfileImportViewModelTest, and XrayProfileImportAdmissionTest: byte/node budgets, off-Main validation, stale generations, blank confirmation before validation/provider toggle, and late restore ownership. | passed in scoped JVM suites |

## Observed results

| Snapshot | Executed checks | Result |
|---|---|---|
| 78ef1c6c2dcbdae3f9fcf3682dc7d9db6f3725e3 | Six selected JVM suites, complete staticAnalysis and all independent source phases | PASS. Gradle 7m37s, 85 tasks executed and 825 up-to-date. 1886 module cases plus 23 detekt-rules cases; zero failures/errors/skips. 623 fresh and 1286 carried results. |
| 92bf3f8d54c3e9a67b71521394013c30acb13065 | Six selected JVM suites after incoming DIA-11/14 | 1886 cases passed, 623 fresh and 1263 UP-TO-DATE. App Full lint/detekt and diagnostics ktlint passed. Only an inherited four-term condition failed detekt; it was then corrected with independent review and five owning tests. All source phases passed. |
| e6b124f0e0a105b46d501c1d53cee58b582fe6ea | Final integrated minimal check on c5cc65dd777ca7c90c62ea4706fca719d6aa8a69 | Five fresh ActiveProbeSafetyPolicyTest cases passed. Diagnostics ktlint/lint and app Full compilation executed and passed. Module detekt failed only on sixteen incoming DIA-15 issues. |

Final independent source checks all passed: 11 just ci-gates checks; Rust API snapshots 12 matched, zero failed, one Linux-owned runtime-platform host skip on Darwin; locked Cargo metadata with 113 workspace members; task contracts with 237 tasks and 921 steps. Final phase exits are [gradle=1, ci-gates=0, locked-metadata=0, taskctl=0], aggregate 1. The implementation steps are complete; the final execution step remains open for quality and remote CI evidence. The task stays in review, with no closure or OpenSpec archival claim.

## Inherited DIA-15 blocker

The sixteen issues are in TelegramSpeedTest.kt, Tcp16FatHeaderProbe.kt, NativeEchTlsHandshake.kt, Tcp16Target.kt and NativeEchTlsHandshakeTest.kt. Rules are MagicNumber, LoopWithTooManyJumpStatements, ThrowsCount, ReturnCount and MaxLineLength. Every affected file has identical raw bytes and Git blob to admission base c5cc65dd777ca7c90c62ea4706fca719d6aa8a69, outside the owned IMP-03 patch. Exact lines and metrics, plus parent proof, are archived. The requested adjacent-ID boundary is preserved. No baseline, suppression or gate was changed to hide the failure.

## Exact commands and durable evidence

Evidence root: /Users/npochaev/GitHub/RIPDPI-worktrees/imp03-combined-evidence/.

- final-independent-quality contains the complete staticAnalysis command and 1909-case proof.
- post-dia11-rebase contains the 1886-case post-rebase results and passing source phases.
- final-minimal-safety contains gradle-phase.sh, phase-results.json, per-phase logs and exit files, summary.json, manifest.json, incoming-parent-proof.json, exact detekt reports and the five current JUnit cases. Other module XML files were not counted as newly invoked tests.
- /Users/npochaev/GitHub/RIPDPI-worktrees/imp03-xray-evidence/safety-predicate/HANDOFF.md contains the one-file correction and passing owning gate. transfer-quality/HANDOFF.md contains the 54-case check and original bridge control.

Gradle used two workers, --continue, no daemon/file watching/configuration cache/build cache, disabled Kotlin incremental compilation, the in-process compiler, native builds skipped and the existing required libXray AAR. Final targets were ActiveProbeSafetyPolicyTest, diagnostics detekt/ktlint/lintDebug and app compileGithubFullDebugKotlin. Source phases use CARGO_TARGET_DIR=<integration-worktree>/native/rust/target and CARGO_BUILD_JOBS=2 for just ci-gates, then cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1 and taskctl validate. Exact environment and commands are in the phase ledger.

## Review and controls

Independent review cleared all F1-F11, H2, H3 and the mechanical integration repairs. Test splits retained their original bodies and scenarios. The continuation bridge uses an action-only Result and rethrows non-Exception failures before one resumeWith outside capture. The original bridge control reproduced the same IOException identity failure caused by pinned coroutines 1.11 recovery. The final oracle requires exact class/message and either original identity or one recovery copy with the original cause and coroutine boundary frame. Fatal Error retains strict identity, including cancellation. Debug recovery was not disabled.

Earlier failures and interrupted runs remain in their evidence directories and Git history. Missing generated lint/build outputs were observed; their cause was not established. Successful runs used an isolated source-equivalent checkout with intact archived reports. The owned diff has no dependency, Rust source, baseline, golden, suppression or gate changes. Checkout doctor, source status, diff whitespace and Git metadata were checked; no hook was bypassed.

## Evidence boundaries and remaining checks

- C is source and caller review. V is the named JVM, Room, ViewModel and loopback evidence. S is the pinned format evidence in /Users/npochaev/Downloads/RIPDPI_network_audits/IMP-03.md. Passing code checks do not automatically raise S or E.
- No new E is claimed. No external server, physical device, root activation, VPN transport or native handshake acceptance was run.
- H4 remains unverified: process death before a durable bootstrap receipt and server-side exactly-once behavior. Tested receipts prevent replay in the tested consumer and guarded storage paths; no cross-process exactly-once guarantee is claimed.
- Selector initial preferences persist without starting VPN or replacing an explicit choice. Failed imports preserve saved data. Parameters discarded by older versions cannot be reconstructed from the saved model; existing imports need comparison with their source before relying on lost routing or trust constraints.
- Unsupported subsets remain explicit: arbitrary package predicates, detour runtime, unsupported TLS wrappers and full external-config round trip were not added. URL-test requires an explicit HTTP(S) URL.
- Remaining acceptance is inherited DIA-15 quality, exact published-commit CI, separately authorized device/server checks and a defined H4 crash-recovery contract.
