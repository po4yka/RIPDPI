---
task_id: PRX-1794076309764211
change: loc02-udp-association-fixes
commit_sha: 2931361e90be8a516a4819f688e62d5cde4fe402
local: passed
local_evidence: 893 library tests, 25 UDP integration and 8 network UDP tests passed; affected all-target Clippy, workspace check, locked metadata, ci-gates and independent reviews passed. See commands and limits below.
remote_ci: not_applicable
remote_ci_evidence: This change owns local verification and a requested main push; no hosted CI acceptance is claimed.
device: not_applicable
device_evidence: No device, root or external server activation is in scope; local loopback covers these regressions.
artifact: not_applicable
artifact_evidence: No release artifact is produced by this repair task.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UDP-SETUP-CANCEL | PRX-1794076380668103 | udp_setup_shutdown; udp_dns_cancellation; blocking_cancel and WS cancellation tests; independent TCP, DNS and caller reviews. | passed |
| REQ-UDP-CONTROL-LIFETIME | PRX-1794076381212455 | udp_control_lifetime; upstream_pump control_lifetime_tests, including fresh FIN, queued downlink and no cached-payload replay. | passed |
| REQ-UDP-REPLY-AUTHORITY | PRX-1794076381693446 | udp_reply_authority; upstream_pump tests; standard upstream codec and logical-edge mapping. | passed |
| REQ-UDP-UDP-GATE | PRX-1794076382119939 | udp_profile_gate tests; all 19 kind predicates and the real SS2022 listener. | passed |
| REQ-UDP-DATAGRAM-SIZE | PRX-1794076382558326 | udp_datagram_size: small, oversized, small for AES128, AES128 identity, AES256 and ChaCha20. | passed |
| REQ-UDP-SOURCE-STABILITY | PRX-1794076382995792 | udp_source_rebind: two real proxy listeners retain 4 replies; direct rebind remains active. | passed |

## Commands and observed results

Commands ran from native/rust with isolated Cargo target directories and --locked.

- `cargo test --locked -p ripdpi-proxy-runtime -p ripdpi-proxy-runtime-adapter -p ripdpi-session -p ripdpi-relay-core -p ripdpi-relay-tls-transports -p ripdpi-dns-resolver -p ripdpi-ws-bootstrap --lib -- --test-threads=1`: 893 passed, 2 existing opt-in tests ignored, 0 failed or filtered.
- `cargo test -p ripdpi-proxy-runtime --locked --test udp_reply_authority --test udp_source_rebind --test udp_control_lifetime --test udp_setup_shutdown --test udp_dns_cancellation -- --test-threads=1`: 25 passed. Shared support tests appear in more than one binary.
- `cargo test -p ripdpi-proxy-runtime --locked --test network_e2e socks5_udp_ -- --test-threads=1`: 8 passed; 37 other network tests were outside this filter.
- `cargo clippy --locked -p ripdpi-proxy-runtime -p ripdpi-proxy-runtime-adapter -p ripdpi-session -p ripdpi-relay-core -p ripdpi-relay-tls-transports -p ripdpi-dns-resolver -p ripdpi-ws-bootstrap --all-targets -- -D warnings`: passed.
- `cargo check --workspace --locked`: passed.
- `cargo metadata --locked --format-version 1`: passed, 112 workspace members.
- `just ci-gates`: passed, including architecture health, LoC/hotspot budgets, contracts, locales and API snapshots. The existing canonical runtime-platform API snapshot check is Linux-owned and was skipped by the tool on Darwin.
- `cargo deny --locked --manifest-path native/rust/Cargo.toml check`: advisories, bans, licenses and sources passed. Existing duplicate, yanked and unused-license warnings remain.
- Independent pr-reviewer passes covered all six fixes and the merged tree. Async-cancel-safety reviewed the relay and DNS changes; no blocking findings remain.
- Both source commits for F1 and F2 passed hooks in isolation. An initial partial-staging hook attempt failed on a mixed F1/F2 tree; F2 was saved separately, F1 was checked alone, and the restored full source matched the validated SHA-256 file digest set.

The final suites contain 926 test executions, not 926 unique tests. Logs are in `/tmp/ripdpi-loc02-validation/`: final-libs.log, final-udp-integration.log, final-network.log, final-clippy.log, workspace-check.log, ci-gates.log and final-validation-summary.json. Temporary logs can be removed by the operating system.

## Limits

- Existing ignored tests `mieru_off_socks_payload_and_stop_with_upstream` and `vless_reality_xhttp_xudp_with_upstream` need pinned external peers. They do not prove these fixes.
- Android/device/root and specific external-server compatibility were not run. No hosted CI, APK or deployment proof is claimed.
- Miri was attempted for the cancellable connector completion test. It stopped on unsupported `socket` under isolation before the assertions. Isolation was not disabled; no Miri success or atomic-safety proof is claimed. See miri.log and miri-status.txt.
- Cancellation is cooperative. The existing synchronous protect factory is not forcibly preempted; its UDS path is bounded, and an arbitrarily stuck JNI callback is outside the protect API contract.
- DoQ can retain its cached endpoint while the resolver is alive. Query cancellation is not a promise to close every shared resolver fd.
- The audit hypotheses were not assumed to be defects. Fragmentation, authenticated NAT mobility and new relay kinds remain out of scope.
