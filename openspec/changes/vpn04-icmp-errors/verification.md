---
task_id: VPN-1794142179721255
change: vpn04-icmp-errors
commit_sha: null
local: passed
local_evidence: 384 unit tests, 8 tun_e2e tests and 1 IPv4/IPv6 ICMP runtime test passed. Clippy, formatting, ci-gates and independent wire/runtime/async reviews passed; see requirement rows for commands.
remote_ci: not_applicable
remote_ci_evidence: No hosted workflow dispatch is part of this repair; report any automatic push checks separately.
device: not_applicable
device_evidence: The acceptance scope is the real Rust packet loop on a socketpair. Android app-visible delivery remains unverified; no JNI, Builder or Android lifecycle contract changes.
artifact: not_applicable
artifact_evidence: No APK, release or native delivery artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN04-QUOTE | VPN-1794142363121903 | cargo test --locked -p ripdpi-tunnel-core; Scapy 2.6.1 input vectors and independent smoltcp checksum oracle; F2 review clear | passed |
| REQ-VPN04-MTU | VPN-1794142364654791 | IPv6 input lengths 1231/1232/1233 and odd checksum case; 45 packet tests passed; F3 review clear | passed |
| REQ-VPN04-LOCAL | VPN-1794142365887377 | cargo test --locked -p ripdpi-tunnel-core --test udp_icmp_errors; real loopback SOCKS refusal and socketpair TUN; local 3/1 and 1/3, exact original prefix | passed |
| REQ-VPN04-BOUNDS | VPN-1794142365887377 | Error bucket burst/refill, stale association/registration, UID rejection, cancellation, replacement registration and reassembly provenance tests; async review clear | passed |

## Observed checks and boundaries

- Initial regression: the real packet-loop test failed with WouldBlock because the baseline emitted no local ICMP error. F2 RED changed quote bytes; F3 RED length was 104 instead of 1279. Each fix has a separate commit.
- `cargo test --locked -p ripdpi-tunnel-core`: 384 unit + 8 existing packet-loop + 1 new dual-family packet-loop tests passed. Linux-only targets and doctests contain zero tests on macOS; this is not Linux or Android proof.
- `cargo clippy --locked -p ripdpi-tunnel-core --all-targets -- -D warnings` and `cargo fmt --all -- --check`: passed after the final test borrow correction.
- `just ci-gates`: passed; architecture, LoC/hotspot, API, contract, locale and translation gates remain intact. The raw quote validator has its own module; no baseline was extended.
- Independent pr-reviewer passes cover F2, F3 and F1. Review found a replacement registration bug and a missing moved quote-limit constant; both were fixed and the named checks rerun. Async-cancel-safety found no production defect.
- Local errors use the configured tunnel source. A standard SOCKS5 association refusal is not remote port evidence. Remote ICMP errors, echo and PMTU relay, fragmented quote support, Android app-visible delivery and physical-network interoperability remain outside the verified host scope.
- Runtime data stays in bounded RAM. No dependency, schema, JNI, locale, golden or release artifact changed.
