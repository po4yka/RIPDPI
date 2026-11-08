---
task_id: DNS-1794142176893534
change: vpn05-dns-safety
commit_sha: eb229812220c44509d11effd5888c769245a4a95
local: passed
local_evidence: The rebased combined tunnel-core package passed 404 unit tests and 9 host integration tests with no failures or ignored tests. Package rustfmt, all-target clippy with warnings denied, cargo metadata --locked, just ci-gates, and independent reviews passed. Linux-only targets contain zero tests on macOS; no Linux or Android acceptance is claimed.
remote_ci: required
remote_ci_evidence: Normal push CI has not yet been observed for the published candidate. No hosted workflow was manually dispatched.
device: not_applicable
device_evidence: This change repairs native cache and response logic. Host tests do not establish Android non-root routing, physical handover, revoke, or external peer compatibility.
artifact: not_applicable
artifact_evidence: No APK or native release artifact is published by this repair.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN05-RESPONSE-LEASES | DNS-1794142289514887 | RED: one response lease test failed at unexpected successful rewrite with identical A addresses. GREEN: three response lease regressions and 376-test slice suite passed. Combined package gate passed. | passed |
| REQ-VPN05-SYNTHESIS-AD | DNS-1794142290766144 | RED: AD remained set after A synthesis. GREEN: five tests cover A, AAAA filtering in all sections, unchanged TXT/NXDOMAIN/routed AAAA, raw AD bit, other flags and EDNS. Slice suite passed 381 tests. | passed |
| REQ-VPN05-ISSUED-OWNER | DNS-1794142292240560 | RED: four ownership tests failed. GREEN: default 10,000-entry churn, reset with active leases, /30 exhaustion, and rejected-allocation cursor tests passed. Slice suite passed 377 tests. | passed |
| REQ-VPN05-RESERVED-LISTENER | DNS-1794142293202272 | RED: six cases failed and one control passed. GREEN: seven tests cover default/custom listener, first/middle/last /30 positions, /31 capacity, supplied live/evicted/reset caches, conflicting reservations and leased allocation failure. Slice suite passed 384 tests. | passed |
| REQ-VPN05-STALE-ISOLATION | DNS-1794142294080225 | RED: fresh mapping vanished after a stale result. GREEN: four current-lease/result combinations preserve the mapping and emit SERVFAIL. Existing wait-event regression retains the actual generation-loss flush. Combined package gate passed. | passed |

## Combined local gate

Execution step DNS-1794142294899581 owns combined review and validation. An independent reviewer approved F1, F2, F3, F4, and F5 separately, then approved their combined diff without actionable findings. Review agents changed no files.

From `native/rust`, with Rust 1.98.1, an isolated target directory, debug information disabled, incremental compilation disabled, and the repository build lane:

```sh
cargo fmt --package ripdpi-tunnel-core -- --check
cargo metadata --locked --format-version 1
build-gate --lane ripdpi -- sh -c 'cargo test -p ripdpi-tunnel-core --locked --jobs 4 && bash ../../scripts/ci/cargo-guarded.sh cargo clippy -p ripdpi-tunnel-core --locked --jobs 4 --no-deps --all-targets -- -D warnings'
```

Observed after rebase: 404 unit tests and 9 host integration tests passed; no failures or ignored tests. Three Linux-only integration targets and the doctest target contain zero tests on macOS. Clippy passed. `just ci-gates` passed architecture health, file and hotspot limits, cross-language contracts, protocol fixture versions, diagnostics boundaries, route coverage, locale parity, translation export, and Rust API snapshots. The Linux-owned runtime-platform API snapshot was not checked on macOS, as the owning gate reports.

Local evidence logs: `/tmp/ripdpi-vpn05-rebased-native.log` and `/tmp/ripdpi-vpn05-rebased-ci-gates.log`. Per-fix RED/GREEN logs are under `/tmp/ripdpi-vpn05-mapping-evidence/`, `/tmp/ripdpi-vpn05-rewrite-evidence/`, and `/tmp/ripdpi-vpn05-f3-*.log`. Initial fixture/API setup errors were corrected before the recorded RED assertions; they are not application failures. The first F3 full run exposed an existing test that required the reported bug. Its current-generation preservation expectation was corrected while its real generation-loss, SERVFAIL, counters, and unregister checks were retained.

## Rebase validation

Origin main advanced to `1b8e7da6fa6ae58d9644692107d3c39ddf1cf112` with six ICMP/UDP repair commits. The job branch rebased cleanly. The earlier combined run passed 393 unit and 8 integration tests; because the incoming commits changed the same crate, the full package, clippy, formatting, metadata, and source gates were repeated on the rebased source. The final counts above include the incoming UDP/ICMP tests. An independent reviewer also checked the changed UDP/error callers against the DNS fixes and found no interaction defect. No incoming source or fixtures were discarded.

## Commit slices

- F2: `e7fc8bf9aa0c59773a64e2e441698160370543ac` — retain mappings throughout response rewrite.
- F1: `c64a43aa820a9053b0575328856e81bdd1da6c25` — prevent issued-address reassignment.
- F5: `ec68695d83b686b55e0ecda1270c542afa2f9fb9` — clear AD after synthesis.
- F4: `1734bcf1d7ee419bb76c5a12eeec5b8642b0f812` — reserve the configured listener.
- F3: `eb229812220c44509d11effd5888c769245a4a95` — preserve fresh mappings on stale results and correct DNS contract documentation.

All five commits include their regression tests. No dependency, lockfile, JNI schema, protobuf, locale, baseline, golden, or compiled native library changed.

## Limits

A native session can issue at most 131,070 addresses with the default /15 and its reserved listener. Exhaustion fails closed and needs a new native session; live cache memory stays bounded. DNSSEC validation and transparent RRSIG/TSIG compatibility are not added. JNI stall timing, IPv6 domain affinity, Android and physical transport acceptance remain outside the observed host evidence. The audit registry is unchanged. The task stays in review until required remote CI evidence is resolved; it is not archived as accepted prematurely.
