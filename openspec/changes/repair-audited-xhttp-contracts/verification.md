---
task_id: TRN-1794371418861139
change: repair-audited-xhttp-contracts
commit_sha: 8b481b5d0afa52925dbe66babc24e1894ef251e8
local: passed
local_evidence: 114 xHTTP tests, 34 origin tests, 16 relay cases and the explicit secured upstream case passed; clippy, workspace check, ci-gates and independent reviews passed.
remote_ci: not_applicable
remote_ci_evidence: Local native and offline CI source gates define this repair acceptance; hosted CI is separate.
device: not_applicable
device_evidence: Native carrier and helper repair verified by local peers; Android activation and physical transport acceptance are outside this change.
artifact: not_applicable
artifact_evidence: No release APK, Android AAR or packaged native artifact is produced.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TRN04-OPEN | TRN-1794371483895973 | stream_up_tests withheld-header exchange and late rejection/reset on both runtimes; independent Xray H2c without heartbeat | Passed |
| REQ-TRN04-DROP | TRN-1794371484875463 | cleanup_tests idle Drop, sibling echo, half-close and pending POST cancellation; peer bodies close in both modes | Passed |
| REQ-TRN04-URL | TRN-1794371485689522 | Nine Go URL vectors, thirteen actual HTTP helper cases and ten independent Xray exchanges | Passed |
| REQ-TRN04-POOL | TRN-1794371486485508 | Four capacity_tests: queued second-carrier acquisition on both runtimes, cancellation and loser reservations | Passed |
| REQ-TRN04-DOCS | TRN-1794371487347249 | Pin and mode document review; spec metadata checks and final just ci-gates | Passed |

## Exact source and observed checks

- Source commit: `8b481b5d0afa52925dbe66babc24e1894ef251e8`. Rebased onto published Vision, gRPC and XUDP repairs; those changes were preserved. The only merge conflict was the order of identical existing Tokio test features. No manifest or lockfile delta remains.
- Rust and Cargo 1.98.1. Compiler-backed checks ran through `build-gate --lane ripdpi`, with at most four jobs.
- `cargo test --locked --jobs 4 -p ripdpi-xhttp -p ripdpi-cloudflare-origin`: xHTTP 114 tests and origin helper 34 tests passed, no failures or ignored tests; zero doctests.
- `cargo test --locked --jobs 4 -p ripdpi-relay-core xhttp`: 16 passed, one upstream-only case ignored in the ordinary suite. Its explicit execution is recorded below, rather than counted as an ordinary suite pass.
- `cargo clippy --locked --jobs 4 -p ripdpi-xhttp -p ripdpi-cloudflare-origin --no-deps --all-targets -- -W clippy::await_holding_lock -W clippy::await_holding_refcell_ref -W clippy::await_holding_invalid_type -D warnings`: passed.
- `cargo check --workspace --locked --jobs 4`: passed for the 113-member workspace.
- `just ci-gates`: passed after rebase; Rust API snapshots match. The Linux-owned runtime-platform snapshot is explicitly skipped on Darwin.
- `python3 scripts/ci/check_architecture_health.py`: no new or worsened indicators. `cargo metadata --locked --format-version 1 --no-deps`: passed with 113 members.
- Logs: `/tmp/trn04-rebased-checks.log`, `/tmp/trn04-rebased-ci-gates.log`, `/tmp/trn04-architecture.log`, `/tmp/trn04-cargo-metadata.json`.

## Regression and independent peer evidence

- Original F1/F2 failures were reproduced by the audit on `57d9bcdf722510ef9e1de482cd41859b77a1ef47`. New regression tests were then run on an immutable copy of that production source: seven expected failures (withheld headers, late POST error and idle/pending HTTP cleanup), eight control tests passed. Log: `/tmp/trn04-stream-red-result.log`. No compile failure was counted as RED.
- F3 fixed request-target test failed before the repair (`%2F` instead of `%252F`). An independent Go net/url oracle confirmed all nine expected vectors. Origin helper regression then failed with HTTP 404 instead of 200 for `/literal%253Fpath/sid`; its fixed HTTP test passed all thirteen cases.
- F4 queued-acquisition test failed before the repair when only the second carrier became free. The fixed queued operation completes on that carrier; both runtime variants and reservation-release cases passed.
- Independent host Xray 26.3.27, executable SHA256 `5d9dd24c0aba4b6cfcc6a33a5d67f854816ee17f392bf932ec8176da46f7e404`: ten H2c payload exchanges passed (both modes, ordinary/query/percent/repeated-slash paths and disabled heartbeat). Six temporary verification tests passed, including peer-side body cleanup and the queued second carrier. Log: `/tmp/trn04-wire-proof.log`.
- The temporary verification copy's seven xHTTP production files were checked byte-for-byte against the rebased sources; its only added pool declaration was the documented `cfg(test)` oracle module. The original diagnostic PoC source is preserved at `/tmp/trn04-original-audit-poc.rs`.
- Secured REALITY/H2/xHTTP/XUDP peer check passed on the actual rebased checkout: `cargo test --locked -p ripdpi-relay-core --lib --jobs 2 tests::backend_fixture_tests::vless_reality_xhttp_xudp_with_upstream -- --ignored --exact --no-capture`; one passed, zero failed or ignored, peer exit 0. It covers both modes and two UDP targets with server heartbeat disabled. Log: `/tmp/trn04-rebased-peer.log`. The independent Go peer pins Xray revision `0afec66085a85191e9dd2a4a6646c7c9a94688d1`; executable SHA256 `6672636f63d061a98dc4e0ef40f516b2084b6521e3c9abe35b732ee91dbe7ecd`. The repository oracle source was copied into a temporary directory with only `scStreamUpServerSecs: -1` added to its server configuration. Synthetic credentials and all endpoints stayed on loopback.

## Reviews and limits

- Independent pr-reviewer passes covered F1, F2, F3 and F4. The review found the shared Cloudflare origin path mismatch; the same F3 commit repairs it and contains its HTTP regression.
- Independent async-cancel-safety review found no production ownership or wakeup defect. The semaphore fairness description and the test helper cancellation annotation were corrected.
- Native stream-up/stream-one and the existing import restrictions remain unchanged in scope. No packet-up, downloadSettings, H1/H3 mode, arbitrary header or Android Xray provider feature was added.
- E evidence is specific to independent loopback peers. No external server, CDN, physical device, root activation or real secret was used. Android Xray AAR linkage and physical-network acceptance remain unverified and are not claimed by this repair.
