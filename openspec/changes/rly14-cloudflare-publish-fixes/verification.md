---
task_id: RLY-1794300683762457
change: rly14-cloudflare-publish-fixes
commit_sha: 2215d745483395d636512589176fdbbe3168ffcb
local: passed
local_evidence: On this source commit, 30 origin tests, 115 selected Kotlin tests, clippy, fmt, locked Cargo metadata, just ci-gates, full staticAnalysis and the local pinned cloudflared HTTP/2 proxy regression passed; independent code and async reviews approved the fixes.
remote_ci: not_applicable
remote_ci_evidence: Local regression fixes; hosted CI is not acceptance evidence for this change.
device: not_applicable
device_evidence: No Android lifecycle or JNI contract changes; no device probes were run.
artifact: not_applicable
artifact_evidence: No packaging change; skipNativeBuild and the Xray stub do not prove APK or provider artifact compatibility.
deployment: not_applicable
deployment_evidence: No deployment, tunnel registration, real credentials, or public edge probes were used.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RLY-1794300683762457-001 | RLY-1794300805274937 | Real loopback delayed upload, half-close and response-drop tests; pinned cloudflared HTTP/2 proxy regression. | passed |
| REQ-RLY-1794300683762457-002 | RLY-1794300823103520 | Credential parse regression checks safe messages, causes and suppressed exceptions; service suite passed. | passed |
| REQ-RLY-1794300683762457-003 | RLY-1794300828691953 | Owning path/query cases and actual helper GET with a configured query passed. | passed |
| REQ-RLY-1794300683762457-004 | RLY-1794300834987199 | Raw command 2/3 wire fixtures cannot authenticate or connect to a real TCP listener. | passed |
| REQ-RLY-1794300683762457-005 | RLY-1794300841013581 | IPv6 and port-boundary regressions; actual helper listener on [::1] passed. | passed |
| REQ-RLY-1794300683762457-006 | RLY-1794300844455627 | Both modes reject an empty identity and accept a configured identity; editor validation suites passed. | passed |

## Commands and results

All final build and test commands ran in the separate `rly14` build-gate lane.
Cargo and CMake used at most four jobs. Gradle used four workers, a 5 GiB
heap and a 3 GiB Kotlin heap. Cargo ran from `native/rust` with the pinned
toolchain. The source tree stayed fixed during the final run.

```text
cargo test -p ripdpi-cloudflare-origin --locked --jobs 4
  30 passed; 0 failed; 0 ignored
cargo clippy -p ripdpi-cloudflare-origin --all-targets --locked --jobs 4 -- -D warnings
  passed
cargo build -p ripdpi-cloudflare-origin --locked --jobs 4
  passed
cargo fmt --check
  passed
cargo metadata --locked --format-version 1
  passed
just ci-gates
  passed; architecture --check, source budgets, contracts, API snapshots and locales
```

The final Gradle command selected these existing test classes and ran
`staticAnalysis -Pripdpi.skipNativeBuild=true --max-workers=4 --console=plain --continue`:

| Task | Class | Tests |
|---|---|---:|
| :core:service:testDebugUnitTest | CloudflarePublishRuntimeTest | 12 |
| :core:service:testDebugUnitTest | AppsScriptRelayCertificateStoreTest | 3 |
| :app:testGithubFullDebugUnitTest | ConnectionViewModelTest | 34 |
| :app:testGithubFullDebugUnitTest | ConnectionCloudflareValidationTest | 2 |
| :core:data:runtime-state:testDebugUnitTest | XrayConfigImportParserTest | 42 |
| :core:data:runtime-state:testDebugUnitTest | TrojanImportRejectionTest | 2 |
| :core:data:testDebugUnitTest | SingBoxSubscriptionParserTest | 20 |

The seven JUnit XML reports contain 115 tests with no failures, errors or
skips. Gradle finished with `BUILD SUCCESSFUL in 27m 58s`; the combined script
finished with `ALL_FINAL_GATES_PASSED` and exit 0. This is a selected regression
run, not the complete Kotlin test suite. The final command list is in the
local script `/tmp/rly14-final-rebased-gates.sh`; its output is in
`/tmp/rly14-rebased-final.log`.

## Independent local peer

```text
python3 /tmp/rly14-run-cloudflared-verify.py /tmp/rly14-streaming-target/debug/ripdpi-cloudflare-origin
  responseStillOpenAfterDelay=true
  delayedUploadWrite=success
  responseClosedAfterUpload; proxyReturned; pocExit 0
python3 /tmp/rly14-origin-host-smoke.py /tmp/rly14-streaming-target/debug/ripdpi-cloudflare-origin
  configured query path: HTTP 200
  IPv6 loopback: HTTP 200
```

The peer uses real proxy modules from cloudflared 2026.2.0 commit
`66587173e2cd5b2ea6d495e97aec6551e5e18c30`. It uses local HTTP/2 and loopback
HTTP/1.1. The upload has a deliberately wrong synthetic UUID and must close
after the complete header arrives. It does not register a tunnel or contact
the Cloudflare edge. The durable origin regressions also check delayed data,
half-close and cancellation against actual loopback sockets.

## Review and gate repairs

Independent review approved all six fixes, the test-class extraction, and
the final Trojan guard refactor. The async review approved the streaming
response lifetime and cancellation path. No unsafe code was added.

The first local runs reproduced the six audit failures before their fixes.
Later integration gates also found a large test class and existing constant
and guard style violations. The test extraction preserves the same cases.
Constant naming and extraction preserve their values. Trojan guard changes
preserve TLS checks, refusal order, reasons and profile fields. The SingBox
file remains at its original 700 code lines. No baseline, dependency, schema,
locale or golden fixture was changed by these fixes.

The old-source Gradle run was cancelled after main advanced; its partial
lint output is not a successful gate. The final source above passed the full
required command after rebase on origin/main 4f3b6298e2e19a6b965195aeda11f702a2b7991c.

## Evidence boundary

C is the inspected code. V is the observed local run. S remains the pinned
specification and source review in the audit. E is limited to the concrete
local cloudflared HTTP/2 proxy regression. None implies public edge, QUIC,
remote client, Android APK or real tunnel auth compatibility.

Public-hostname gRPC handling, downstream CDN buffering, production reachability
of the concurrent ownership hypothesis, named/token auth, external TLS refusal,
QUIC fallback, and shipped artifact receipts remain outside this local fix
acceptance. No physical device, root activation or real secret was used.
