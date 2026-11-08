---
task_id: VPN-1794141873537976
change: vpn03-udp-association-repairs
commit_sha: 53a3a84bafb8a38ba0d20d43467b36f05e73ee4e
local: passed
local_evidence: Final tunnel 429 unit and 9 integration tests passed; Clippy, format, ci-gates, architecture, metadata, pinned Xray and Kotlin checks passed.
remote_ci: required
remote_ci_evidence: Pending push of the integrated candidate.
device: not_applicable
device_evidence: This repair task uses host and loopback validation. Physical device and external-network acceptance remain unverified.
artifact: not_applicable
artifact_evidence: No release artifact is published by this change. Patched Xray source tests do not prove the shipped AAR.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN03-XRAY-AUTHORITY | VPN-1794142081038544 | Exact pinned Xray Go suites; direct and SOCKS loopback; managed gate, pinned host, wrong source, empty payload and capacity tests; Kotlin catalog 9 and service 4 tests; both modules ktlint/detekt; Python artifact 11 tests | Passed on host; shipped AAR/device not established |
| REQ-VPN03-REPLY-OWNERSHIP | VPN-1794142081837995 | Two observed RED assertions; 34 UDP association GREEN tests; Clippy all targets | Passed |
| REQ-VPN03-LEASE-LIFETIME | VPN-1794142082448229 | Observed idle-lease RED; 100 UDP GREEN tests; Clippy and format; independent expiry, shared pin, stale event and monotonic activity tests | Passed in the merged suite |
| REQ-VPN03-COMPLETE-DATAGRAM | VPN-1794142083191753 | Five observed RED tests; 105 UDP GREEN tests; independent IPv4/IPv6 reassembly and checksum oracle, exact-fit receive, large reply, same-control EMSGSIZE recovery | Passed in the merged suite |
| REQ-VPN03-CHECKSUM | VPN-1794142083879816 | Two observed RED checksum assertions; 26 classifier GREEN tests; routing rejection before hooks/admission; Clippy | Passed |
| REQ-VPN03-DUPLEX | VPN-1794142084413873 | Controlled real-worker RED/GREEN; merged full suite, general and async review | Passed |

## Observed regression evidence

F1: unmodified pinned Xray timed out on the private authority frame. Review found two additional faults in the first candidate: a wrong numeric reply source could be relabelled, and empty UDP payloads were dropped. Both failed in the observed historical candidate and pass in the final pinned-source run. Final Go suites test managed and ordinary SOCKS gates, two host routes at one pinned IP with poison DNS, IPv6 authority parsing, wrong IP/port, empty direct/SOCKS replies, bounded admission and reclaim. This is local compatibility evidence for the exact patched source, not the distributed Android artifact or every Xray carrier. VLESS/XUDP carrier codecs still reject empty payloads at their separate boundary.

F2: the original 256-entry response table evicted an accepted source; a refused destination acquired a DNS lease. Two observed RED assertions became GREEN. Admission now bounds destinations before queueing and pinning. Refused queue/budget work does not refresh activity or consume a lease.

F3: the old implementation retained an idle destination pin. The RED test observed one lease where zero was expected. A destination now owns immutable authority and an independently retired identity. Main-loop delivery validates both liveness and Arc identity, so a queued reply cannot pass after expiry and re-admission. A shared synthetic IP is unpinned only after its last destination expires. Time uses a monotonic origin with max updates. No socket-cookie guarantee is inferred from this identity.

F4: five observed RED tests cover exact-fit loss, large receive truncation/drop, IPv4/IPv6 fragmentation, real worker delivery, and outer EMSGSIZE closing an existing association. Final tests use literal SOCKS frames and an independent packet reassembly/checksum oracle. Receive storage is bounded separately from the TUN MTU. Return fragments share one destination identity. EMSGSIZE drops only that datagram and retains the control session.

F5: four independent checksum vectors ran before the fix: two passed and two failed. Corrupted nonzero checksums and IPv6 zero checksums were accepted. Final classifier and routing tests reject them before hooks and association creation. IPv4 zero checksums remain valid.

## Commands and local logs

All directly invoked resolving Cargo commands used `--locked`, the pinned toolchain from `native/rust`, bounded jobs and per-worktree targets. Heavy commands used `build-gate --lane ripdpi`. The existing API-snapshot helper invokes Cargo internally without a lock flag; the final source gate ran offline, and Cargo.lock remained byte-identical. No dependency was resolved to a different version.

- F1: `python3 scripts/native/test-libxray-protection.py /tmp/vpn03-xray-build/libXray /tmp/vpn03-xray-build/xray-core-green --grpc /tmp/vpn03-xray-build/grpc`; source revision and patch hash are pinned by the manifest. Go RED logs: `/tmp/vpn03-f1-red.log`, `/tmp/vpn03-f1-review-red-observed.log`; final Go GREEN `/tmp/vpn03-f1-final-go.log`. Python artifact checks: `/tmp/vpn03-f1-python.log`. Kotlin final unit/lint: `/tmp/vpn03-f1-kotlin-final.log`; `-Pripdpi.skipNativeBuild=true --max-workers=4 --no-configuration-cache --offline`, JDK 21. The first cold build failed test-source lint, then corrected runs passed. Native build skipping is explicit and supplies no Android runtime evidence.
- F2: `cargo test -p ripdpi-tunnel-core --locked --lib udp_assoc`; `cargo clippy -p ripdpi-tunnel-core --locked --all-targets -- -D warnings`. RED `/tmp/vpn03-f2-red.log`; final `/tmp/vpn03-f2-final-commit.log` (34 tests).
- F3: `cargo test -p ripdpi-tunnel-core --locked --lib udp`; same Clippy; `cargo fmt --all --check`. RED `/tmp/vpn03-f3-red-observed.log`; final `/tmp/vpn03-f3-final-checks.log` (100 tests).
- F4: RED `/tmp/vpn03-f4-red-{exact,large,fragments,worker,outer-size}.log`; GREEN `/tmp/vpn03-f4-green.log` (105 tests), `/tmp/vpn03-f4-clippy.log`; receive timeout test-only adjustment rerun in `/tmp/vpn03-f4-commit.log` (3 tests and hooks).
- F5: RED `/tmp/vpn03-f5-red.log`; final `/tmp/vpn03-f5-checks.log` (26 classifier tests, Clippy and doc tests); `/tmp/vpn03-f5-route-red.log` is the post-fix GREEN routing run despite its filename.
- Combined pre-rebase: `cargo test -p ripdpi-tunnel-core --locked --lib`: 397 passed, no failures or ignored tests, `/tmp/vpn03-combined-lib.log`.

An isolated unmodified library run passed 378 tests (`/tmp/vpn03-isolated-baseline-lib.log`). The earlier audit's shared-output observations were not reproduced in this isolated run; their cause remains unknown. They are not treated as current baseline defects. The first source-gate attempt passed early checks but was interrupted during nightly API compilation; it is not a combined gate pass. No baseline or dependency change is used to pass a check.

## Integration and memory-model checks

The combined branch was rebased onto `c851d162067e4ae3abd7ec18102352250f15d365`. VPN-04 original-wire ICMP quotes and setup-failure/UID guards remain present. Forwarding returns the actual queued registration, including replacement, and only queued work marks strategy state. VPN-05 DNS allocation remains unchanged: a retired synthetic address is never assigned again in the same session. Lifetime tests use that newer contract.

The first merged compile exposed fixture API differences (`pending_error`, forwarding Option and ICMP policy callback). The next complete run observed 427 passes and one fixture failure: the replacement test had not assigned its original flow to the old transport. The corrected fixture records that ownership and retains the different-generation assertion and SetupFailed ICMP result. The final pre-H2 merged library run passed 428 tests (`/tmp/vpn03-refactored-lib2.log`). General review confirmed the production merge and the fixture contract. State and housekeeping were moved without behavior changes to meet existing hotspot budgets; no baseline changed.

Miri command prefix: `MIRIFLAGS=-Zmiri-many-seeds=0..4 cargo +nightly miri test -p ripdpi-tunnel-core --locked --lib <filter>`. Filters `numeric_reply_requires_a_live_admitted_destination`, `touch_udp_activity_updates_timestamp`, and `neighboring_fragmented_replies_use_distinct_identifications` each passed on all four seeds. Logs: `/tmp/vpn03-miri-{identity,clock,fragments}.log`. These reach the liveness, monotonic max-update and fragment ID atomics. The Tokio lifetime filter could not execute: macOS Miri does not support foreign function `kqueue` (`/tmp/vpn03-miri.log`). No stub or ignored test was used to hide that limit. The host is aarch64 macOS. Relaxed fields publish no non-atomic payload; immutable metadata is passed through Arc and channels.

## Scope and remaining acceptance

H1 remains unverified: late wire datagrams for exactly the same app IP:port and destination can be indistinguishable after kernel socket reuse. Required next check: two non-root Android apps, forced same source reuse across UIDs, delayed old reply, current Builder routing and UID-cache TTL. No physical device, root activation, external server or secret use was authorized or run.

H2 has an observed controlled real-worker RED: ready inbound replies waited for the full 16-datagram outbound queue (zero entries remained). The separate fair-polling fix leaves 15 entries before the second ready reply. The exact test and 106 UDP tests passed on its branch; the final merged 429-test suite also passed. General and cancellation reviews found no remaining issue. The merged fixture resets activity to zero after a bounded wait for a nonzero monotonic clock, because max-update cannot replace the old u64::MAX test sentinel. H3's wall-clock risk is removed by monotonic activity; this does not claim an observed Android clock-change run.

No release artifact is built or published. Required remaining artifact check: rebuild the pinned patched Xray Android AAR and native tunnel, verify manifest/checksums, then run the managed UDP handoff through the real non-root VPN. Host unit tests, desktop SOCKS loopback and Kotlin tests cannot replace that check. External relay/provider interoperability remains unverified.


## Final local acceptance

- `cargo test -p ripdpi-tunnel-core --locked`: 429 library unit tests and 9 integration tests passed; zero failed and zero ignored. Doc tests ran zero. Linux-only TUN, soak and SO_BINDTODEVICE binaries contain zero tests on this macOS host and are not Linux acceptance. Log: `/tmp/vpn03-final-tunnel-tests2.log`.
- `cargo clippy -p ripdpi-tunnel-core --locked --all-targets -- -D warnings`: exit 0, `/tmp/vpn03-final-clippy.log`; final commit hooks also passed after the fixture adjustment.
- `cargo fmt --all --check`: exit 0. `just ci-gates`: exit 0, `/tmp/vpn03-final-ci-gates3.log`. The Linux-owned runtime-platform API snapshot is explicitly skipped on Darwin; other eligible snapshots match.
- `python3 scripts/ci/check_architecture_health.py`: exit 0, `/tmp/vpn03-final-architecture.log`; the ci-gates `--check` mode also passed. `check_file_loc_limits.py` after the last test change: no new or baseline growth violations, `/tmp/vpn03-final-fileloc.log`.
- `cargo metadata --locked --format-version 1 --no-deps`: exit 0, `/tmp/vpn03-final-metadata.json`.
- `just checkout-doctor`: ready after rebase. General review covered each repair, the F3/F4 seam, current-main DNS/ICMP integration, state moves and H2 port. The async specialist reviewed the lifetime and polling paths. No finding remains open from those reviews.

## Repair commits

| Finding | Commit | Change |
|---|---|---|
| F1 | 00b98fae0 | Managed Xray destination authority, exact source validation and empty UDP |
| F2 | 467413351 | Stable bounded destination admission and reply ownership |
| F3 and H3 | b67790a47 | Independent leases, retired event identities and monotonic activity |
| F4 | 7c6675bf6 | Complete receive, IPv4/IPv6 return fragments and EMSGSIZE isolation |
| F5 | 7d6d4a7ae | UDP ingress checksum validation |
| H2 | 53a3a84ba | Cancel-first fair duplex polling |

`6f1e41614` retains the merged DNS/ICMP test contracts and splits state/housekeeping to meet existing source budgets. Planning and verification use separate documentation commits. Integration uses a fast-forward to main; push is explicitly authorized by the user. Remote CI is not inferred from these local results.
