---
task_id: VPN-1794140727483999
change: repair-vpn02-tcp-stack
commit_sha: 2fc84afab75b55e15a99027625729b548e8dc5b3
local: passed
local_evidence: Native host regressions, workspace Clippy, formatting, architecture, locked metadata and just ci-gates passed on the recorded exact SHA; Miri was blocked before the test body by unsupported macOS kqueue.
remote_ci: not_applicable
remote_ci_evidence: No hosted CI run is required or manually launched by this native repair task.
device: not_applicable
device_evidence: Native host regressions are the acceptance scope. Android transport acceptance remains separate and is not claimed.
artifact: not_applicable
artifact_evidence: No release binary or golden fixture is changed.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

All passed results in this table are native host evidence.

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN02-HALF-CLOSE | VPN-1794140849743430 | F1 commit 28a0de38a (integrated as 961e5117f): 380 unit plus full wire suites passed; close regressions include both FIN orders,17/10000-byte tails,6 s writer and 11 s native RX using paused Tokio time,65-session batch and cancellation. Upstream195 TCP tests per family passed. Independent PR and async reviews clean; clippy/format/hooks passed. | passed |
| REQ-VPN02-IDLE-CLEANUP | VPN-1794140852477934 | F2 commit faaccad6d6 (integrated as 3582a2263): 383 unit and all wire suites passed. Regressions cover idle expiry after EOF without another app packet, completion wake with a retained TIME-WAIT socket, coalesced wake over 65 entries, native RX after the first batch, and late admission. Independent PR and async reviews found no remaining defect; normal hooks passed. | passed |
| REQ-VPN02-INTERFACE-ENTROPY | VPN-1794140854875280 | F3 commit c36c240db (integrated as 0f211d773): tun_entropy RED repeated ISN4260165794, GREEN 1 passed; independent review and pre-commit clippy/format/architecture checks passed. OS entropy error is propagated before ready; no injection seam added. | passed |
| REQ-VPN02-BUFFER-POLICY | VPN-1794140858454513 | F4 commit 4cfa2676c: tcp_buffers RED65535 vs1, GREEN374 unit +1 wire passed; RX/TX capacity includes1,1024,65536,16777216; independent review and pre-commit hooks passed. | passed |
| REQ-VPN02-IPV6-MSS | VPN-1794140863320088 | F5 commit 298cd6f4a (integrated as 4ee303bd7): full packet RED IPv6 536 vs1220, GREEN2; upstream TCP suites192 per family; affected core/WARP545 passed; affected clippy, independent review and hooks passed. | passed |

## Combined validation

Execution step: VPN-1794140869499117. Verified tree: `2fc84afab75b55e15a99027625729b548e8dc5b3`, after a clean rebase onto `c851d162067e4ae3abd7ec18102352250f15d365`. The rebase had no conflicts. Independent integration review confirmed the combined TCP, DNS and UDP paths.

All Cargo commands below ran from `native/rust`, unless a manifest path is shown. Rust was pinned to 1.98.1. Test builds used `CARGO_TARGET_DIR=/private/tmp/ripdpi-vpn02-build-target CARGO_BUILD_JOBS=2`; workspace checks used `/private/tmp/ripdpi-vpn02-workspace-target`.

| Command | Observed result | Local log |
|---|---|---|
| `cargo test -p ripdpi-tunnel-core -p ripdpi-tunnel-android -p ripdpi-tunnel-config -p ripdpi-warp-core --locked` | PASS: 754 passed, 0 failed, 1 existing ignored startup latency smoke. Core: 415 unit plus 17 packet integration tests. Linux-only suites compile to zero tests on macOS. | `/tmp/vpn02-rebased-tests.log` |
| `cargo clippy --workspace --all-targets --locked -- -D warnings` | PASS, 1m25s. | `/tmp/vpn02-rebased-workspace-clippy.log` |
| `cargo fmt --all --check` | PASS. | Command output was empty; exit 0. |
| `cargo metadata --locked --format-version 1 --no-deps` | PASS. | `/tmp/vpn02-rebased-metadata.json` |
| `python3 scripts/ci/check_architecture_health.py` from the root | PASS: 0 new, 0 worsened, 0 stale indicators. | `/tmp/vpn02-rebased-architecture.log` |
| `just ci-gates` from the root, with `RUSTUP_TOOLCHAIN=1.98.1` | PASS, including API snapshots. The Linux-owned runtime-platform snapshot is skipped by the gate on macOS. | `/tmp/vpn02-rebased-ci-gates.log` |
| Pinned `cargo-deny 0.19.8 --locked check advisories bans licenses sources` | PASS for all four categories. No lock, manifest or deny input changed after this run. | `/tmp/vpn02-dependency-deny-pinned.log` |
| `cargo test --locked --manifest-path native/rust/vendor/smoltcp/Cargo.toml --lib --no-default-features --features std,medium-ip,proto-ipv4,socket-tcp socket::tcp::test` from the root; repeat with `proto-ipv6` | PASS: 195 TCP tests per family. The vendor source is unchanged by F2 or the rebase. | `/tmp/vpn02-f1-upstream-ipv4.log`, `/tmp/vpn02-f1-upstream-ipv6.log` |
| `MIRIFLAGS=-Zmiri-strict-provenance cargo +nightly miri test --locked -p ripdpi-tunnel-core --lib a_panicking_session_reports_failure_before_its_duplex_end_closes` with its own temporary target | BLOCKED, exit 1: Miri does not support macOS `kqueue`. The Tokio runtime failed to initialize before the test body. Dependency compilation passed. No source seam or stub was added. | `/tmp/vpn02-f2-miri.log` |

The Miri command ran before the rebase on `a8cf3876a8e10094d8fe2865ef3c7ec6d820485e`. Its environment limitation is not a passing test or a defect. The normal host regression passed after the rebase. This change adds an Acquire read to the existing Release/Acquire outcome flag and a wake; it adds no unsafe code or lock-free data structure.

Each fix passed an independent PR review and normal commit hooks. F1 and F2 also passed independent async cancellation review. The final F2 review covered 8 async functions: 0 missing annotations and 0 cancel-safety bugs. Its specialist Clippy run passed. Vendored patch replay matched the recorded upstream archive plus `integration.patch`. The standalone upstream Clippy warnings were reproduced on the pristine archive and were not suppressed; application workspace Clippy passed.

## Evidence limits

- C: all five confirmed defects have a reviewed repair and permanent regression. Hypotheses from the original audit are not silently classified as defects or implemented.
- V: native host unit tests and packet loops only. The packet fixtures use real smoltcp, duplex, SOCKS tasks and kernel loopback sockets. A Unix datagram pair substitutes for the Android TUN transport. Paused-time unit tests exercise the 6 s and 11 s close boundaries; they are not wall-clock throughput evidence.
- S: the original audit supplies the checked protocol requirements and upstream provenance. These repairs do not establish complete RFC conformance. Fresh entropy does not prove the full ISN algorithm contract.
- E: no Android device, physical transport or external-server compatibility was established. Root was not enabled and real secrets were not used.
- Remaining acceptance: on a non-root Android device, run IPv4/IPv6 TCP through the real TUN at MTU 1280/1320/1500; test both half-close orders and stalled writers; stop/revoke during SYN, SOCKS handshake and drain; verify worker exit, duplicated FD closure and no post-stop traffic. These checks were not launched.
- Linux-only real TUN, bind-to-device and soak tests were not run on this macOS host. Hosted CI, release builds and deployment were not launched.

The original audit `/Users/npochaev/Downloads/RIPDPI_network_audits/VPN-02.md` and registry were left unchanged. This record is the repair evidence; it does not rewrite the original findings.
