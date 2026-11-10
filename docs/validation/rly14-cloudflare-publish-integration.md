# RLY-14 Cloudflare publish integration verification

This record adds post-rebase evidence to the completed RLY-14 fix task.
It does not change the original archive or its generated receipts.

- Tested source: `0f0ef85bb7d658fe88a9f9206324ae7731675517`.
- Integration base: `b6c7274ebb6d59e64cf3502a88e80d4a8bb13351`.
- Historical tested source: `2215d745483395d636512589176fdbbe3168ffcb`.
- Historical record: [archived verification](../../openspec/changes/archive/2026-11-10-rly14-cloudflare-publish-fixes/verification.md).

Main advanced during integration. Its commits already supplied the constant
naming and Trojan guard repairs. The rebase keeps those incoming versions and
removes the duplicate local repairs. The six Cloudflare fixes remain separate
commits. The seven Cloudflare source and regression files have identical blobs
to the historical tested source. The incoming SingBox string helper already
rejects blank values; its early return keeps the same profile refusal.

The original archive binds the run made before this rebase. It remains
historical evidence. The local branch `audit/rly14-tested-local` retains that
source and its closure history. This document binds the new full run below.

## Observed results

| Check | Result |
|---|---|
| Origin tests with --locked | 30 passed; none failed or ignored |
| Origin clippy, all targets, --locked, -D warnings | Passed |
| Origin host build, --locked | Passed |
| Rust fmt --check and locked metadata | Passed |
| Pinned cloudflared HTTP/2 proxy, delayed upload | Passed |
| Actual origin query path and IPv6 listener | HTTP 200 |
| just ci-gates | Passed; no new architecture or source-budget violations |
| Selected Kotlin regression tests | 115 passed; no failures, errors or skips |
| Full staticAnalysis | BUILD SUCCESSFUL in 9m 23s |
| Combined script | ALL_FINAL_GATES_PASSED; exit 0 observed |
| Task history after rebase | taskctl validate --base origin/main passed |

The Kotlin classes and counts are:

| Class | Tests |
|---|---:|
| CloudflarePublishRuntimeTest | 12 |
| AppsScriptRelayCertificateStoreTest | 3 |
| ConnectionViewModelTest | 34 |
| ConnectionCloudflareValidationTest | 2 |
| XrayConfigImportParserTest | 42 |
| TrojanImportRejectionTest | 2 |
| SingBoxSubscriptionParserTest | 20 |

All build and test commands ran in the separate `rly14` build-gate lane.
Cargo and CMake used at most four jobs. Gradle used four workers, a 5 GiB
heap and a 3 GiB Kotlin heap. No application source changed during the run.

## Exact final command

```bash
CMAKE_BUILD_PARALLEL_LEVEL=4 CARGO_BUILD_JOBS=4 CARGO_TARGET_DIR=/tmp/rly14-streaming-target build-gate --lane rly14 -- bash /tmp/rly14-final-rebased-gates.sh > /tmp/rly14-post-main-rebase-final.log 2>&1
```

The executed script was:

```bash
#!/usr/bin/env bash
set -euo pipefail
task_root=/Users/npochaev/GitHub/RIPDPI-worktrees/rly14-streaming
cd "$task_root"
printf 'SOURCE_SHA=%s\n' "$(git rev-parse HEAD)"
(cd native/rust && cargo test -p ripdpi-cloudflare-origin --locked --jobs 4)
(cd native/rust && cargo clippy -p ripdpi-cloudflare-origin --all-targets --locked --jobs 4 -- -D warnings)
(cd native/rust && cargo build -p ripdpi-cloudflare-origin --locked --jobs 4)
python3 /tmp/rly14-run-cloudflared-verify.py /tmp/rly14-streaming-target/debug/ripdpi-cloudflare-origin
python3 /tmp/rly14-origin-host-smoke.py /tmp/rly14-streaming-target/debug/ripdpi-cloudflare-origin
printf 'HOST_SMOKE_PASSED\n'
(cd native/rust && cargo fmt --check)
(cd native/rust && cargo metadata --locked --format-version 1 > /tmp/rly14-final-metadata.json)
just ci-gates
env -u CARGO_BUILD_JOBS ./gradlew :core:service:testDebugUnitTest --tests com.poyka.ripdpi.services.CloudflarePublishRuntimeTest --tests com.poyka.ripdpi.services.AppsScriptRelayCertificateStoreTest :app:testGithubFullDebugUnitTest --tests com.poyka.ripdpi.ui.screens.connection.ConnectionViewModelTest --tests com.poyka.ripdpi.ui.screens.connection.ConnectionCloudflareValidationTest :core:data:runtime-state:testDebugUnitTest --tests com.poyka.ripdpi.data.subscription.XrayConfigImportParserTest --tests com.poyka.ripdpi.data.subscription.TrojanImportRejectionTest :core:data:testDebugUnitTest --tests com.poyka.ripdpi.data.SingBoxSubscriptionParserTest staticAnalysis -Pripdpi.skipNativeBuild=true --max-workers=4 --console=plain --continue '-Dorg.gradle.jvmargs=-Xmx5g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:SoftRefLRUPolicyMSPerMB=50 -XX:MaxMetaspaceSize=1g -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8' '-Pkotlin.daemon.jvmargs=-Xmx3g -XX:+UseG1GC -Dfile.encoding=UTF-8'
printf 'ALL_FINAL_GATES_PASSED\n'
```

The peer uses real proxy modules from cloudflared 2026.2.0 source commit
`66587173e2cd5b2ea6d495e97aec6551e5e18c30`. The observed delayed upload stayed
open, accepted the second write, and closed after the complete wrong-UUID
header reached the origin. This test uses local HTTP/2 and HTTP/1.1 sockets.
Durable origin tests also verify delayed data, half-close and cancellation.

## Evidence limits

C is code inspection. V is the observed host run. S remains the pinned
specification and upstream source review in the audit. E covers only the
concrete local pinned cloudflared HTTP/2 proxy case. These results do not prove
public edge, QUIC, real named/token auth, remote-client or Android APK
compatibility. The Gradle run uses skipNativeBuild and the Xray stub.

Public-hostname gRPC handling, CDN buffering and production reachability of
the concurrent ownership hypothesis remain unverified. A shipped Android
binary receipt is still absent. No physical device, root activation,
external server probe or real secret was used.
