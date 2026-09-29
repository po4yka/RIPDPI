---
task_id: RST-1790683922892928
change: zapret-verification-examples
commit_sha: 83f5d69967bc7dc66aad21397ad05fce4ac539d8
local: passed
local_evidence: Combined full tunnel and monitor suites passed 300 tests; two existing opt-in soak tests were ignored. Clippy, fmt, locked metadata, architecture health/contracts, task validation and independent review passed.
remote_ci: required
remote_ci_evidence: Exact implementation-SHA CI run 36569659197 is pending; CodeQL and other workflows are in progress. Hosted acceptance has not passed.
device: passed
device_evidence: Complete Android native library binaries passed 40 tunnel tests and 242 monitor tests on API 37 ARM64 with 16K pages as shell UID 2000; no failures, ignores or filtered tests.
artifact: passed
artifact_evidence: Pinned upstream oracle reproduced all 20 packet constants byte for byte twice. Rust 1.98.1 and NDK 29 artifacts have 16K LOAD alignment; hashes are recorded below.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VERIFY-001 | RST-1790684060968448 | Independent upstream C vectors; full host and Android tunnel tests compare complete Lua split, rewrite and fake-TTL output | passed |
| REQ-VERIFY-002 | RST-1790684062504196 | Real coordinator and local HTTP tests retain TCP after QUIC and failed pilots; quick and capped behavior stays intact | passed |
| REQ-VERIFY-003 | RST-1790684062504196 | Active stage deadline test reports PartialResults with DNS fallback; full existing scheduler and cancellation tests pass | passed |

## Packet oracle

The upstream producer is zapret2 ca950d838a0ee7dc32bb6ae5e65e54488c1bfe3b.
The nfqws2 binary SHA256 is
`313584782bc218b5465ee918f74091ea17777bbbbb2fa7d1fcfccbe48d6df645`.
The maintained Lua source emitted all 20 constants in two separate Android
runs. Both runs matched the generated Rust file byte for byte. Logs:
`/tmp/ripdpi-verification-vector-oracle.log` and
`/tmp/ripdpi-verification-vector-oracle-repeat.log`.

CLI bootstrap required shell su 0 for capabilities. Interception was disabled;
the oracle used reconstruction/checksum helpers and did not send packets or
change firewall rules. The Rust tests themselves ran without root.
IPv4 UDP keeps its existing zero-checksum policy. Unsupported IPv6 AH remains
safe passthrough. Existing golden fixtures and baselines did not change.

## Local checks

From native/rust under Rust 1.98.1:

```sh
cargo test --locked -p ripdpi-tunnel-intercept -p ripdpi-monitor-engine
cargo clippy --locked --all-targets -p ripdpi-tunnel-intercept -p ripdpi-monitor-engine -- -D warnings
cargo fmt --all --check
cargo metadata --manifest-path Cargo.toml --locked --format-version 1
```

Final implementation 83f5d699 passed 300 tests: tunnel 40, monitor 242 unit
and 18 integration. The two existing soak cases require RIPDPI_RUN_SOAK=1;
they were not run. Zero failures or filtered tests. Logs:
`/tmp/ripdpi-combined-83f5d699-{tests,fmt,clippy}.log`.
The earlier combined 36db8749 gate also passed before test portability fixes.
Three full-audit regression tests failed before the implementation changed;
`/tmp/ripdpi-exhaustive-red.log` preserves that result.

Architecture health reports 23 existing indicators, zero new, worsened or
stale entries. Native contracts report zero violations. Locked metadata and
task validation pass. Logs: `/tmp/ripdpi-examples-final-{architecture,contracts}.log`.
Repository commit hooks passed workspace all-targets Clippy and their other
required checks. Independent review found no actionable issues in the packet,
audit or test portability changes.

## Android checks

Complete library binaries were built with Rust 1.98.1, NDK 29.0.14206865,
target aarch64-linux-android and profile android-jni-dev. They ran on
emulator-5556, API 37, ARM64, 16384-byte pages, shell UID 2000.

- Tunnel: 40 passed, zero failures/ignores/filtered cases, 1.39 seconds.
  Binary 49109456 bytes, SHA256
  `db17217be33f8339ccc27346bd372624181bbee63b386d0c2f3635369528b3f8`.
- Monitor: 242 passed, zero failures/ignores/filtered cases, 60.75 seconds.
  Final source 83f5d699, binary 117873904 bytes, SHA256
  `a1ad6cf15420e0812fd7f9f1fa2d55093c2d9a3577ef619b053122e6c53952c6`.

All ELF LOAD alignments are 0x4000. Logs and artifact inventories:
`/tmp/ripdpi-packet-android-{build,test,elf}.log`,
`/tmp/ripdpi-packet-android-artifact.json`,
`/tmp/ripdpi-monitor-android-retry-{build,test,elf}.log` and
`/tmp/ripdpi-monitor-android-retry-artifact.json`.

The first full Android monitor run reported 240 passed and two failed. It
could not read compile-host golden paths, and queue setup raced the reaper
thread schedule. Test-only fixes reuse RIPDPI_REPO_ROOT and bound queue setup
retry to one second. The four copied committed goldens matched source hashes;
no blessing occurred. The complete retry passed. Initial failure evidence
remains in `/tmp/ripdpi-monitor-android-test.log` and its logcat/screenshot.
A focused reaper retry was diagnostic only and did not replace the full gate.
Temporary device directories were removed and their absence was checked.

This verifies native computation and local diagnostic execution. It does not
prove DPI evasion, raw delivery, APK root activation or privileged VPN socket
transfer. No app state or device policy changed.

## Publication

The job branch was fetched and rebased on origin/main. It retained the
concurrent E2E fixture-reporting commit 0db64b503. Final source checks ran after
the rebase and test portability fixes. The clean main checkout fast-forwarded,
and push published implementation 83f5d69967bc7dc66aad21397ad05fce4ac539d8.
GitHub reported direct-main bypass of PR, pending ci-required and pending
CodeQL requirements. Repository rules were not changed.
Hosted acceptance remains pending; the task stays in review and is not archived.

Exact-SHA CI: https://github.com/po4yka/RIPDPI/actions/runs/36569659197
was pending when observed. CodeQL, Secret Scan, fleet-fixtures and the
monitor dependency guard were in progress. ls-remote confirmed the exact
implementation SHA on refs/heads/main.
