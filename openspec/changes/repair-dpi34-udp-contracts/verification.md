---
task_id: RST-1794723522152914
change: repair-dpi34-udp-contracts
commit_sha: a91d4558fc096a6ab4306ce091e07888eacce6f6
local: passed
local_evidence: "Six-package nextest: 887 passed, 13 existing opt-in tests not run; doctests and strict Clippy passed. Architecture, locked metadata and ci-gates passed. Read-only code and unsafe reviews have no remaining findings. Miri passed 11 tests."
remote_ci: required
remote_ci_evidence: "Source commit was pushed to main. CI run 43691630344 is queued; other checks are in progress. No hosted CI pass is claimed."
device: not_applicable
device_evidence: No root activation or device acceptance is owned by this local repair; actual Android raw emission remains unverified.
artifact: not_applicable
artifact_evidence: No APK or release artifact is produced.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI34-ISOLATION | RST-1794723793879997 | Constructor and replacement-tuple regressions; independent rootless Linux IPv4/IPv6 exclusive-bind oracle | passed |
| REQ-DPI34-REBIND | RST-1794723799353040 | Real proxy loopback: generic 00/40 replies keep their tuple; QUIC v1/v2 context permits one rebind; SOCKS upstream stays pinned | passed |
| REQ-DPI34-DELTA | RST-1794723803895791 | YAML/TOML and Lua admission tests; zero-action composition and DROP tests; independent packet bytes | passed |
| REQ-DPI34-IPV6 | RST-1794723808701492 | Safe option-chain bytes, checksum oracles 0x561c/0x513d, original-packet preservation and 3072 bounded malformed inputs; Miri | passed |
| REQ-DPI34-LOWPORT-DOC | RST-1794723813599604 | Documentation matches the existing 1024..4096 helper and OS-assigned fallback | passed |
| REQ-DPI34-PATH-DOC | RST-1794723818128018 | RFC 9000 endpoint roles and opaque-proxy limits are stated; path validation is not claimed | passed |
| REQ-DPI34-ISOLATION | RST-1794723821878358 | Combined gates passed; main fast-forward and push observed; ls-remote confirms the source SHA | passed |

## Local commands and results

Run from native/rust with Rust 1.100.0, --locked, four build workers and NEXTEST_TEST_THREADS=4. The user authorized the rly14 build-gate lane for this work.

Packages: ripdpi-proxy-runtime-adapter, ripdpi-proxy-runtime, ripdpi-strategy-config, ripdpi-strategy-lua, ripdpi-strategy-udp and ripdpi-tunnel-intercept.

- cargo nextest run --locked --jobs 4 --no-fail-fast with all six -p selections: 887 passed, zero failed; 13 existing opt-in tests not run (three soak, four load and six relay interop).
- cargo test --locked --jobs 4 --doc with the same packages: exit 0; no doc test cases exist in these packages.
- cargo clippy --locked --jobs 4 --all-targets with the same packages and -- -D warnings: exit 0.
- python3 scripts/ci/check_architecture_health.py: exit 0.
- cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1: exit 0.
- just ci-gates: exit 0 after rebase. The Linux-owned runtime-platform API snapshot is skipped on Darwin by the existing host policy; other selected snapshots match.
- cargo +nightly miri test --locked -p ripdpi-strategy-udp --no-default-features with Tree Borrows and symbolic alignment: 11 passed, zero failed, zero ignored. Its raw packet source and test bytes match the final tree.
- Independent Linux socket oracle: UID 65534, no capabilities, network none; IPv4 and IPv6 competing binds return EADDRINUSE and replies reach the owner. This is kernel evidence, not an Android constructor run.

The first combined nextest run had one TCP reset route-advancement failure. It passed on the first isolated attempt without retry, then in the full 887-test rerun. The cause is not established; TCP code and tests were not changed.

All 5246 inputs were unchanged during the final three-stage Rust run. The rebase changed none of those inputs. Relative to the first run, only the restored F6 comment differs. Nine raw Rust files were recovered from Git blobs after the temporary worktrees disappeared; all nine match their earlier SHA-256 receipts. Documentation was reconstructed and inspected separately.

## Integration and external limits

Each finding has its own commit. Main was fast-forwarded and pushed to a91d4558fc096a6ab4306ce091e07888eacce6f6. GitHub accepted the direct push and reported bypassed PR, ci-required and CodeQL requirements. Repository rules were not edited. Hosted results remain required before task closure.

Local packet conversion, loopback and Miri evidence do not establish Android raw transmission, MTU behavior, peer path validation, or external QUIC compatibility. The original audit keeps C, V, S and E separate.
