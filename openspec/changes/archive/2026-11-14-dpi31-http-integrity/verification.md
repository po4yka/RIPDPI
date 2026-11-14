---
task_id: ENG-1794677657331523
change: dpi31-http-integrity
commit_sha: 98795ce72429f556e4fc8f4c663245ecdcd45fe5
local: passed
local_evidence: Post-rebase nine-crate tests passed 1936 cases with 15 existing ignored cases; strict Clippy, Rust format, Kotlin tests and lint, ci-gates, architecture health and locked metadata passed.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI was not observed and is not claimed by this local repair acceptance.
device: not_applicable
device_evidence: No physical device or emulator run is owned by this data-integrity and evidence repair.
artifact: passed
artifact_evidence: Real loopback receipt tests, exact UA and body byte checks, Rust wire compatibility and eight Kotlin contract tests passed.
deployment: not_applicable
deployment_evidence: No application deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI31-UA | ENG-1794677777812641 | RED reproduced loss for UA ab; short, structured and optional-whitespace UA cases preserve significant data and binary body | passed |
| REQ-DPI31-PREFIX | ENG-1794677778638690 | RED reproduced repeated prefix; CRLF and LF inputs retain bytes, Host markers and complete-head boundary | passed |
| REQ-DPI31-RECEIPT | ENG-1794677779483214 | Real loopback RED then GREEN; changed, unchanged, partial-write failure, projection, TCP-only metadata and absent/false/true serialization tests | passed |
| REQ-DPI31-BOUNDARY | ENG-1794677781056020 | Independent review and final local gates below; no new protocol, privilege or activation path | passed |

## Implementation commits

- `64116847f8`: preserve meaningful User-Agent bytes. Reclaim only two trailing SP/HTAB bytes; otherwise leave the request unchanged.
- `39796cb3d3`: decline Method EOL for a nonzero method-start offset.
- `98795ce724`: carry independent optional HTTP mutation evidence through planning, native receipts, runtime API, monitor projection, Rust wire and Kotlin.

The mutation result describes the prepared bytes. It does not prove delivery. Existing chain disposition, family validation and committed-byte accounting remain separate. Legacy absence stays unknown. The runtime API excludes HTTP evidence from UDP receipts. Existing Kotlin readers ignore unknown keys; default null is omitted from new output. No schema version change or golden blessing was needed for this additive field.

H2 was not a confirmed defect: the inspection cap does not define a universal peer or output limit. No speculative limit or historical upstream pin was added.

## Final post-rebase Rust commands

Run from `native/rust/` with `CARGO_TARGET_DIR=/tmp/ripdpi-dpi31-packets-target`:

```sh
cargo test --locked -p ripdpi-packets -p ripdpi-desync -p ripdpi-desync-runtime -p ripdpi-runtime-api -p ripdpi-proxy-runtime -p ripdpi-monitor-proxy-runtime -p ripdpi-diagnostics-contracts -p ripdpi-monitor-engine -p ripdpi-proxy-config
cargo clippy --locked -p ripdpi-packets -p ripdpi-desync -p ripdpi-desync-runtime -p ripdpi-runtime-api -p ripdpi-proxy-runtime -p ripdpi-monitor-proxy-runtime -p ripdpi-diagnostics-contracts -p ripdpi-monitor-engine -p ripdpi-proxy-config --all-targets -- -D warnings
cargo fmt --all -- --check
cargo metadata --manifest-path Cargo.toml --locked --format-version 1
```

All commands exited 0 on the recorded implementation SHA. Tests: 1936 passed, zero failed or filtered, 15 existing ignored. The ignored cases are six relay-interoperability, four load and five soak tests; they require separate lanes or environment flags and were not verified here.

| Crate | Passed | Ignored |
|---|---:|---:|
| ripdpi-packets | 335 | 0 |
| ripdpi-desync | 251 | 0 |
| ripdpi-desync-runtime | 194 | 0 |
| ripdpi-runtime-api | 19 | 0 |
| ripdpi-proxy-runtime | 562 | 13 |
| ripdpi-monitor-proxy-runtime | 9 | 0 |
| ripdpi-diagnostics-contracts | 71 | 0 |
| ripdpi-monitor-engine | 297 | 2 |
| ripdpi-proxy-config | 198 | 0 |

Logs: `/tmp/ripdpi-dpi31-rebased-nine-tests.log`, `/tmp/ripdpi-dpi31-rebased-nine-clippy.log`, `/tmp/dpi31-format-rebased.log`, `/tmp/dpi31-cargo-metadata.json`.

Before rebase, the seven-crate suite passed 1403 tests with the same 15 existing ignored tests. The combined packets/planner/runtime suite then passed 780 tests without ignored cases. Rebase brought in completed DPI-33 validation repairs; final nine-crate checks include the updated proxy-config dependency. The Kotlin source and wire model inputs did not change during rebase, so their results below remain valid.

## Kotlin and source gates

```sh
./gradlew :core:diagnostics:testDebugUnitTest --tests '*DiagnosticsWireContractTest' --console=plain
./gradlew :core:diagnostics:ktlintCheck :core:diagnostics:detekt -Pripdpi.skipNativeBuild=true --console=plain
CARGO_TARGET_DIR=/tmp/ripdpi-dpi31-api-target just ci-gates
python3 scripts/ci/check_architecture_health.py
```

The final full Kotlin command exited 0, with its test task executed: eight tests, zero failures, errors or skipped cases. The separate source lint command passed. The new contract test keeps absent, false and true evidence distinct. Logs: `/tmp/dpi31-f2-kotlin-final.log` and `/tmp/dpi31-f2-kotlin-lint-retry.log`.

The full Kotlin command first stopped before tests because a pinned NaiveProxy artifact was absent. The owning `python3 scripts/ci/fetch-naive-upstream.py --abi arm64-v8a` fetched the ignored artifact and checked APK and ELF digests. No manifest, dependency or tracked binary changed. The full command subsequently passed without the native-skip flag. A separate codec-only run also passed; it was not used to replace the failed full command.

`just ci-gates` passed again after rebase. Architecture health reported zero new or worsened indicators. Logs: `/tmp/dpi31-ci-gates-rebased.log`, `/tmp/dpi31-architecture-final.log`.

API snapshots were regenerated through the owning script. The first shared-target run returned stale output and was not accepted. The isolated command `CARGO_TARGET_DIR=/tmp/ripdpi-dpi31-api-target SCCACHE_RECACHE=1 python3 scripts/ci/check_rust_api_snapshots.py --update` produced only five expected declarations in three snapshots. Subsequent source gates matched them. The tool skips the Linux-owned runtime-platform snapshot on macOS; that API was not changed. Logs: `/tmp/dpi31-api-update.log`, `/tmp/dpi31-api-update-isolated.log`.

## Independent review and limits

Independent read-only review approved F1, H1 and F2 without blocking findings. Review checked bounds, preservation of body and significant UA bytes, partial-write/fallback accounting, legacy decoding, TCP-only evidence and required projections. A formatting correction in the Kotlin test passed lint and the final executed test gate.

No dependency, lockfile, locale, baseline, golden fixture, root activation or configuration schema was changed by this repair. No physical device, external server compatibility or bypass-effectiveness result is claimed. Local loopback and serializer tests do not raise external compatibility evidence.
