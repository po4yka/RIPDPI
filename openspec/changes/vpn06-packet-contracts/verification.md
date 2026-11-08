---
task_id: VPN-1794152786002343
change: vpn06-packet-contracts
commit_sha: 34437b3a909a4c992e8f904508c3b0e96376d106
local: passed
local_evidence: "547 host tests passed across tunnel-core and tunnel-intercept; Clippy all-targets and just ci-gates passed. Logs /tmp/vpn06-combined-tests-final.log, /tmp/vpn06-combined-clippy.log and /tmp/vpn06-ci-gates-final.log."
remote_ci: not_applicable
remote_ci_evidence: Local integration is requested; no hosted workflow is required for this change.
device: not_applicable
device_evidence: Native packet repairs use host packet fixtures. No Android or raw-device acceptance is claimed.
artifact: not_applicable
artifact_evidence: No package or native release artifact is produced by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN06-F1 | VPN-1794152863829739 | `e880c02c0`; invalid_ipv4_header_checksum_is_rejected_before_routing and invalid_ipv4_fragment_cannot_supply_reassembly_bytes; RED2 then GREEN2 | Passed |
| REQ-VPN06-F2 | VPN-1794152864290248 | `6449d1770`; IPv6 option and per-fragment prefix checks, Scapy ICMP bytes, negative-zero TCP checksum feedback; all 476 final core tests pass | Passed |
| REQ-VPN06-F3 | VPN-1794152864751961 | `2097a746a`; overlap, exact duplicate, fixed lifetime and shared tombstone budget; RED3 then GREEN8 | Passed |
| REQ-VPN06-F4 | VPN-1794152865216118 | `4fecae7c8 and 1c18a4074`; post-fragment DestOpts, offset-zero protocol, differing NH including58 in both orders, orphan/ICMP policy; chain and policy RED then GREEN | Passed |
| REQ-VPN06-F5 | VPN-1794152865671556 | `6cd88948a`; safe extension SYNACK, exact TCP options/data, ten headers and zero-length reject; RED then GREEN4 | Passed |
| REQ-VPN06-F6 | VPN-1794152866132615 | `c7956585c`; checked ingress, bounded tails, negative-zero checksums, trusted Lua UDP decoys and extension TTL; RED then GREEN | Passed |
| REQ-VPN06-F7 | VPN-1794152866565523 | `4b0041db1`; Scapy IPv4-options and IPv6-HBH SYNACK with TCP options and odd data, plus trailing IP bytes; RED then GREEN | Passed |
| REQ-VPN06-F8 | VPN-1794152867032446 | `12ad0bc4f`; IPv6 maximum and overflow boundaries before allocation; RED then GREEN | Passed |
| REQ-VPN06-IO | VPN-1794152867486137 | `f634f1cbe and 34437b3a9`; real UnixStream short-write fault and full loopback packet contract test; RED then GREEN | Passed |

## Exact local gates

From `native/rust`, with Rust 1.98.1 and an isolated target directory:

- `cargo test -p ripdpi-tunnel-core -p ripdpi-tunnel-intercept --locked`: 547 passed, no failures. The Linux-only real-TUN and device-binding targets have zero tests on this macOS host; they are not device evidence.
- `cargo clippy -p ripdpi-tunnel-core -p ripdpi-tunnel-intercept --locked --all-targets -- -D warnings`: passed.
- `cargo fmt --all -- --check`: passed in required commit hooks.
- From the repository root, `just ci-gates`: passed. Rust API snapshots match; the Linux-owned runtime-platform snapshot is skipped on macOS by the owning gate.
- Independent read-only review covered all findings and the combined fragment-policy correction. Cancel-safety review found no remaining authored cancellation bug.

## Scope and limits

No dependency, JNI, protobuf, configuration, baseline, Kotlin or root-activation change. Non-first IPv6 fragments remain unclassified and bounded until offset-zero; they reach neither the interceptor nor smoltcp. Known non-UDP first fragments retain their policy path. Unknown-option errors on real fragments remain a conservative silent discard; no complete RFC feedback certification is claimed. TCP fragment reassembly, IPsec, active source routing and jumbograms remain unsupported.

Android real-TUN/revoke, privileged raw injection and external compatibility were not run. Host socketpair/loopback evidence does not establish these categories. The first broad test exposed the ICMP-fragment classification interaction; its regression and correction are included. The first source-gate run failed on ENOSPC in BoringSSL; the final complete rerun passed after clearing only this job's temporary build caches. No gate or baseline was weakened.
