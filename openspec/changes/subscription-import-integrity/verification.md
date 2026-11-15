---
task_id: DAT-1794720910687853
change: subscription-import-integrity
commit_sha: null
local: required
local_evidence: Pending targeted JVM, affected lint, task and offline CI gates.
remote_ci: required
remote_ci_evidence: Pending exact pushed commit checks.
device: not_applicable
device_evidence: Parser and storage behavior has local JVM coverage; no device or transport acceptance is claimed.
artifact: not_applicable
artifact_evidence: No binary distribution or generated fixture is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-IMP03-F1 | DAT-1794721000630817 | PackageRoutePredicateAdmissionTest: reject conditional and inverted package routes; equivalent whole-app route remains supported. | lane passed; combined gates pending |
| REQ-IMP03-F2 | DAT-1794721001131546 | ClashTrojanOptionsAdmissionTest: reject certificate pins, ALPN, SS options, fingerprint, dialer proxy, and Reality constraints. | lane passed; combined gates pending |
| REQ-IMP03-F3 | DAT-1794721001621736 | SingBoxDetourAdmissionTest: reject nonempty or malformed detour; preserve absent or empty detour. | lane passed; combined gates pending |
| REQ-IMP03-F4 | DAT-1794721002101586 | XrayShadowsocksSecurityImportTest: reject unsupported security and stream options; retain plain TCP/raw. | lane passed; combined gates pending |
| REQ-IMP03-F5 | DAT-1794721002583546 | SingBoxMalformedOutboundTest: reject scalar, missing, blank, or non-string outbound types atomically. | lane passed; combined gates pending |
| REQ-IMP03-F6 | DAT-1794721003263346 | BootstrapAdmissionTest and BootstrapRejectedImportTest: strict admission, bounded call, terminal receipt, cross-group joiners, and concurrent edit/delete/retarget preservation. | lane passed; combined gates pending |
| REQ-IMP03-F7 | DAT-1794721003916259 | XrayPasswordCredentialImportTest: pinned flat-credential precedence, malformed flat rejection, and removed Trojan flow rejection. | lane passed; combined gates pending |
| REQ-IMP03-F8 | DAT-1794721004414691 | BootstrapSelectorMembershipTest: preserve resolved selected members and order; reject external migration targets. | lane passed; combined gates pending |
| REQ-IMP03-F9 | DAT-1794721004924780 | SelectorUrltestPolicyTest: explicit valid URL required; omitted/zero cadence and tolerance defaults; malformed and overflowing values rejected. | lane passed; combined gates pending |
| REQ-IMP03-F10 | DAT-1794721005420338 | SelectorInitialPreferenceImportTest, SelectorInitialPreferenceTest, SubscriptionInitialPreferenceTest, and BackupGroupRedactionTest: nested default remapping, Room persistence, explicit override/clear, SHARE redaction, and FULL retention. | lane passed; combined gates pending |
| REQ-IMP03-F11 | DAT-1794721005921256 | SubscriptionPayloadEncodingTest, XrayBase64EncodingImportTest, and SubscriptionEncodingRefreshTest: reporting text decoder, terminal bootstrap error, and preservation of saved members/routes. | combined gates pending |
| REQ-IMP03-H3 | DAT-1794721832362283 | SubscriptionDeclaredPolicyTest: explicit manual selector clears previous automatic policy; absent declarations retain it. | lane passed; combined gates pending |
| REQ-IMP03-H2 | DAT-1794722137487267 | Real-parser host probe measured 2/8 MiB without ANR evidence. Manual input budget, background validation, stale result, and deferred restore regression tests remain pending integration. | combined gates pending |

## Observed checkpoints

- Parser, Xray, and selector changes received independent source review. The combined F11 review found no additional blocker. Review is source evidence (C), not runtime or external compatibility evidence.
- The combined subscription JVM suite passed 117 tests, with zero failures, errors, or skipped tests. Runtime-state ktlint, lintDebug, and detekt passed after the bootstrap link-list extraction. Command: `./gradlew :core:data:runtime-state:testDebugUnitTest --tests 'com.poyka.ripdpi.data.subscription.*' :core:data:runtime-state:ktlintCheck :core:data:runtime-state:lintDebug :core:data:runtime-state:detekt --max-workers=2 --no-build-cache --console=plain`.
- Locked Cargo metadata passed for 113 workspace members. The final metadata capture uses a target directory owned by this worktree.
- The first offline CI source checks passed through architecture health, file limits, runtime contracts, route coverage, locale parity, and translation export. Its Rust API phase was stopped after discovery of an ambient shared Cargo target directory. This interrupted run is not a completed `just ci-gates` result.
- The H2 lane passed 35 app tests plus app/service lint and app detekt. The additional deferred-restore regression and final combined checks remain pending.

## Evidence boundaries

- C: source review and contract tracing. V: the JVM, Room, ViewModel, and loopback runs named above. S: the pinned format requirements cited by the IMP-03 audit. No static review raises V or S by itself.
- E: no new external server, device, root activation, VPN transport, or native handshake acceptance was run. No external compatibility claim is made by these fixes.
- Bootstrap single-use receipt persistence prevents replay within the tested consumer and guarded storage paths. Process death before a durable receipt and server-side exactly-once behavior remain unverified (audit H4).
- Explicit unsupported subsets stay explicit: no detour runtime, arbitrary package predicates, unimplemented TLS wrappers, full external-config round trip, or implicit URL-test host is added. A URL-test policy requires an explicit HTTP(S) URL.
