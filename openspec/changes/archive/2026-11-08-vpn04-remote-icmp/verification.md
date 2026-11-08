---
task_id: VPN-1794144829236231
change: vpn04-remote-icmp
commit_sha: d893cee212ea096b4e9089c1df9d75b6ae9e055a
local: passed
local_evidence: Final d893cee passes 976 host unit and 17 host integration tests, real non-root Linux dual-family proxy-to-TUN test, Clippy including three await lints, formatting, ci-gates and architecture checks. Initial baseline RED and cargo-deny pass are recorded below. One transient existing UDP endpoint test failure is disclosed.
remote_ci: not_applicable
remote_ci_evidence: No hosted dispatch is owned; automatic push checks reported separately.
device: not_applicable
device_evidence: No Android lifecycle or JNI changes; physical device acceptance is not authorized.
artifact: not_applicable
artifact_evidence: No APK or release artifact is owned.
deployment: not_applicable
deployment_evidence: No deployment is owned.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN04-NEGOTIATE | VPN-1794145593975392 | 7 codec regressions, private source pin, explicit REP 7 fresh authentication, malformed REP 7 no-downgrade and forged-frame receive tests; host crate tests | passed |
| REQ-VPN04-REMOTE | VPN-1794145594992974 | Linux UID 65534, network none, cap-drop ALL: real IPv4 3/3 and IPv6 1/4 through production proxy and TUN; same public-API test fails on baseline 1b8e7da6 with WouldBlock | passed |
| REQ-VPN04-CORRELATE | VPN-1794145594992974 | Unique prefix, repeated/empty quotes, partial writes, mutation, history/budget limits, MapDNS, wrong ID/target and virtual-time idle quote expiry tests | passed |
| REQ-VPN04-TUN | VPN-1794145596192603 | Exact original quote and smoltcp checksum/pseudo-header oracle; numeric type/code/MTU vectors, RemoteError association/registration/UID/cancel gate tests; real Linux live-neighbor flow and bounded cancel/revoke | passed |

## Combined acceptance

VPN-1794145597473218 owns combined Cargo, formatting, source gates and read-only reviews. Linux kernel evidence is distinct from Android app-visible evidence. Parameter Problem, echo, relay-carrier and cross-family translation are explicitly outside this capability.

## Commands and observed results before integration

- From `native/rust`: `cargo test --locked -p ripdpi-socks5-core -p ripdpi-proxy-runtime-adapter -p ripdpi-proxy-runtime --lib`: 76, 75 and 376 passed respectively. Two pre-existing SOCKS external-network tests remain ignored; no new test is ignored.
- `cargo test --locked -p ripdpi-tunnel-core`: 393 unit, 8 tun_e2e and 1 local ICMP integration passed. Linux-only targets have zero tests on macOS.
- `cargo clippy --locked -p ripdpi-socks5-core -p ripdpi-proxy-runtime-adapter -p ripdpi-proxy-runtime -p ripdpi-tunnel-core --all-targets -- -D warnings`: passed. Tunnel rerun after expiry changes also denies await_holding_lock, await_holding_refcell_ref and await_holding_invalid_type.
- `cargo deny --locked check`: advisories, bans, licenses and sources passed. Only existing workspace packages were added as test dependencies; no package version changed.
- `cargo fmt --all -- --check`, `just ci-gates`, `python3 scripts/ci/check_architecture_health.py` and `cargo metadata --manifest-path native/rust/Cargo.toml --locked --no-deps --format-version 1`: passed. The macOS API gate explicitly skips the Linux-owned runtime-platform snapshot; it is not claimed as Linux API proof.
- Linux command: `cargo test --locked --offline -p ripdpi-proxy-runtime --test remote_icmp_tun -- --nocapture` inside Rust 1.98.1 image with `--network none --cap-drop ALL --security-opt no-new-privileges --user 65534:65534`: 6 passed (one new dual-family production-path scenario and five support tests). Linux kernel 7.0.14-linuxkit; custom build image ID sha256:55e338736527f164c686ea03b3e68ed13b2754b7284705b09eab53b3b3f2578d.
- The same E2E file and test-only manifest/lock additions in a temporary export of baseline 1b8e7da6 fail at app receive with WouldBlock: five support tests pass, the production-path scenario fails. Missing exported geosite.proto was added from the same baseline before the valid RED run.
- A shared temporary Linux target retained baseline codec artifacts when returning to the patched source. The observed unresolved udp_feedback symbol failure was corrected by `cargo clean --locked --offline -p ripdpi-socks5-core -p ripdpi-proxy-runtime-adapter -p ripdpi-proxy-runtime -p ripdpi-tunnel-core` in that session-only target; the real E2E rerun passed. This is a verification-cache failure, not an application finding.
- Read-only pr-reviewer and async-cancel-safety reviews are clear. Review regressions cover malformed fallback and partial writes. An idle quote budget retention issue found during self-review was fixed with a separate expiry timer and paused-time regression.

## Sources and limits

RFC 1928 section 7 defines standard UDP ASSOCIATE without network-layer feedback: https://www.rfc-editor.org/rfc/rfc1928.html. ICMP formats, quote and checksum rules: https://www.rfc-editor.org/rfc/rfc792.html and https://www.rfc-editor.org/rfc/rfc4443.html. Kernel provenance was checked against pinned Linux v6.12 `net/ipv4/udp.c`, `net/ipv4/ip_sockglue.c`, `net/ipv6/udp.c` and `net/ipv6/datagram.c`; implementation uses pinned nix 0.31.3 and libc 0.2.191 safe APIs, with no new unsafe.

C: production owners and Android local-auth/ephemeral-listener callers are traced. V: host and Linux loopback kernel-to-TUN runs only. S: the listed wire requirements and private extension are checked. E: concrete Linux local-kernel and old-peer fallback fixtures only; no external server or Android application delivery is claimed.

Parameter Problem pointers, echo, carrier translation and cross-family translation are not implemented. Unsupported or ambiguous errors are dropped. Feedback can stop for a flow after bounded history exhaustion or a transformed/untracked write; UDP data continues. Actual PMTU/error-router interoperability and non-root Android app-visible errors plus permission revoke remain separate acceptance checks. No physical device, real secret, privileged activation or external active probe was used.

## Final acceptance after concurrent repair integration

The feature commit is `d893cee212ea096b4e9089c1df9d75b6ae9e055a`, rebased onto origin/main `029c0d226ff9ff0be38a2c1a25730269c3060724`. It preserves the concurrent TCP, DNS and UDP repairs. The merge keeps live destination leases, pointer identity checks, full UDP receive buffers with a sentinel, MTU-based reply fragmentation and the fair receive/send selector. Remote errors retain the same destination lease; closed-channel replacement updates both that lease and the registration. The private wire ceiling returns EMSGSIZE so one oversized datagram does not close the association. Independent pr-reviewer and async-cancel-safety integration reviews are clear.

Final commands used `RUSTUP_TOOLCHAIN=1.98.1 CARGO_TARGET_DIR=/tmp/vpn04-remote-host-target`:

- `cargo test --locked -p ripdpi-socks5-core -p ripdpi-proxy-runtime-adapter -p ripdpi-proxy-runtime --lib`: 76 + 75 + 376 passed, two existing external-network tests ignored.
- `cargo test --locked -p ripdpi-tunnel-core`: 449 unit and 17 integration passed; macOS Linux-only targets contain zero tests.
- `cargo clippy --locked -p ripdpi-socks5-core -p ripdpi-proxy-runtime-adapter -p ripdpi-proxy-runtime -p ripdpi-tunnel-core --all-targets -- -D warnings -D clippy::await_holding_lock -D clippy::await_holding_refcell_ref -D clippy::await_holding_invalid_type`: passed.
- `cargo fmt --all -- --check`, `just ci-gates`, locked metadata and architecture health: passed. No baseline or API snapshot changed.
- The real Linux E2E command was rerun on this commit: 6 passed, including both kernel ICMP families, live neighbor flow, original quote/checksum and bounded stop/revoke. Actual proxy feedback unit tests: 2 passed; actual kernel error queue unit tests: 3 passed. Their proxy/codec/adapter source inputs are unchanged by the rebase. No device or external-network acceptance is inferred.

The first final combined host run had one failure in unchanged codec `server::udp_endpoint_tests::listener_udp_reply_is_reachable_and_released_on_control_eof` at the immediate bind assertion on line 76. The exact isolated test, full codec suite and original full combined command then passed without edits. Root cause is not established; transient ephemeral-port reuse is only a hypothesis. Tests were not weakened or skipped. Logs: `/tmp/vpn04-rebased-host-tests.log`, `/tmp/vpn04-rebased-host-tests-retry.log`, `/tmp/vpn04-rebased-{tunnel-tests,clippy,fmt}.log`, `/tmp/vpn04-rebased2-{ci-gates,architecture,linux-e2e}.log`.

A host pre-commit Clippy attempt also observed stale shared-worktree codec artifacts (unresolved udp_feedback export). The commit hooks and final checks passed with the private session target above; no shared cache or user output was removed. Earlier disk exhaustion was recovered by removing only this session's temporary build outputs; Docker was restarted with no running containers and the Linux image rebuilt. These environment failures are not test passes or application findings.
