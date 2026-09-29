---
task_id: RST-1790682603953151
change: actual-lua-mss
commit_sha: 92d9eeea0c6c0f8ad4242fb64f7bc24fa7641649
local: passed
local_evidence: Five affected full package suites passed 407 tests; affected Clippy, fmt, locked metadata, architecture health/contracts/hotspot budgets and independent review passed. Existing ignored tests are listed below.
remote_ci: required
remote_ci_evidence: Exact-SHA CI run 36565867413 is pending; CodeQL and other workflow runs are in progress. Hosted acceptance is not yet passed.
device: passed
device_evidence: Full Android proxy library and bundled Lua compatibility binaries passed 251 tests on emulator-5556 API 37 ARM64 with 16K pages; actual socket MSS values were 524 and 1188.
artifact: passed
artifact_evidence: Rust 1.98.1 and NDK 29.0.14206865 ARM64 test artifacts built with 16K LOAD alignment; hashes and limits below.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-MSS-001 | RST-1790682917860124 | Full Android proxy library, physical socket TCP_MAXSEG oracle, first and steady sends on two sockets | passed |
| REQ-MSS-002 | RST-1790682917860124 | Optional context, fallback and UDP tests; existing safe TCP_INFO reader | passed |
| REQ-MSS-003 | RST-1790682917860124 | Full bundled Lua compatibility binary; ordered bytes and bounded writes for 536, 1200, 1448, 1, 65535, zero and missing values | passed |

## Observed regression

Before the Lua implementation changed, the supplied MSS 536 test returned
1460 and failed. Log: `/tmp/ripdpi-mss-red.log`. The test then passed with
measured metadata, including zero and missing-value fallback.

## Android execution

From `native/rust`, with the existing owning NDK environment:

```bash
cargo test --locked --target aarch64-linux-android --profile android-jni-dev -p ripdpi-proxy-runtime --lib --no-run --message-format json-render-diagnostics
cargo test --locked --target aarch64-linux-android --profile android-jni-dev -p ripdpi-strategy-lua --test selected_payload_compat --no-run --message-format json-render-diagnostics
```

Both complete binaries ran with `--test-threads=1 --nocapture` on
emulator-5556, API 37, ARM64, 16384-byte pages. Proxy library: 238 passed;
Lua compatibility: 13 passed. Zero failures, ignored or filtered cases.
Listener MSS requests 536 and 1200 produced actual send MSS 524 and 1188.
Lua output matched the independent TCP_MAXSEG socket oracle on each first
and steady send, while the logical target remained port 443.

Artifacts have 0x4000 LOAD alignment. Proxy binary: 129587336 bytes,
SHA256 `0aaea7eadfc989777f8a6ff89daf3617dbe532d9a7cb2a0a432cf945a4d742f6`.
Lua binary: 12731800 bytes,
SHA256 `fa53981874176a386095d7d4967ee11ac2d838af0c27d0861a53689f1e412829`.
Logs: `/tmp/ripdpi-mss-android-{proxy,lua}-{build,test,elf}.log`.
Environment: `/tmp/ripdpi-mss-android-commands.txt`.
Artifact inventory: `/tmp/ripdpi-mss-android-artifacts.json`.
The temporary device directory was removed and its absence verified.
No app state or device policies changed.

This is native socket and Lua evidence. It does not prove APK acceptance,
privileged raw transmission or exact on-wire segment boundaries. The kernel
can combine TCP writes. No root backend behavior changed.

## Local checks

From `native/rust`, under pinned Rust 1.98.1:

```bash
cargo nextest run --locked -p ripdpi-strategy-lua -p ripdpi-strategy-trait -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-proxy-runtime -p ripdpi-tunnel-intercept
cargo test --locked --doc -p ripdpi-strategy-lua -p ripdpi-strategy-trait -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-proxy-runtime -p ripdpi-tunnel-intercept
cargo clippy --locked --no-deps --all-targets -p ripdpi-strategy-lua -p ripdpi-strategy-trait -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-proxy-runtime -p ripdpi-tunnel-intercept -- -D warnings
cargo fmt --all --check
cargo metadata --locked --format-version 1
```

407 tests passed across 31 binaries. Thirteen existing ignored tests were
not executed: six relay interoperability cases, four opt-in load cases and
three opt-in soak cases. No ignores or skips were added. The host macOS
build excludes the live TCP_INFO test; the complete Android binary above
executed it. No doctests are defined in these packages.

Architecture health has zero new, worsened or stale indicators. Native
architecture contracts report zero violations. Hotspot budgets and all 18
checker tests pass. Independent review found no actionable issues.
Logs: `/tmp/ripdpi-mss-rust-packages.log`, `/tmp/ripdpi-mss-doc-tests.log`,
`/tmp/ripdpi-mss-clippy.log`, `/tmp/ripdpi-mss-fmt.log` and
`/tmp/ripdpi-mss-{native-architecture,native-hotspots,native-architecture-tests,architecture-health}.log`.

An initial host run failed before tests because GIT_WORK_TREE reached a
nested boring-sys git init. The corrected run scoped that variable to Git
operations and used the native workspace directory to select Rust 1.98.1.
No product source or quality gate was changed to resolve this environment issue.

## Publication

Commit `92d9eeea0c6c0f8ad4242fb64f7bc24fa7641649` was fetched and rebased
against origin/main; its base did not change. Combined-tree full package
tests passed again: 407 passed, 13 existing ignores. Pinned fmt, locked
metadata, architecture health and native contracts passed again. The
pre-commit hook also passed workspace all-targets Clippy and staged
architecture validation.

The clean main checkout fast-forwarded. Push succeeded, and ls-remote
confirmed the exact implementation SHA on refs/heads/main. GitHub reported
the repository's direct-main bypass for PR, pending ci-required and pending
CodeQL requirements; no repository rules were changed.

Exact-SHA hosted CI: https://github.com/po4yka/RIPDPI/actions/runs/36565867413
was pending when observed. CodeQL, Secret Scan, fleet-fixtures and the
monitor dependency guard were in progress. Both execution steps are complete;
the portfolio task stays in review until required hosted evidence passes.
