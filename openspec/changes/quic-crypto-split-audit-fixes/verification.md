---
task_id: ENG-1794638026548237
change: quic-crypto-split-audit-fixes
commit_sha: 8676e7a38918ad96d9249befcaceb9e1f9afe0d1
local: passed
local_evidence: 753 Rust unit tests, one runtime receipt regression, 85 Kotlin model tests, ktlint, strict affected-crate Clippy, locked workspace check and isolated just ci-gates passed.
remote_ci: required
remote_ci_evidence: Hosted CI is pending the authorized push; local runs do not establish hosted CI success.
device: not_applicable
device_evidence: Local regression fixes do not claim Android device acceptance.
artifact: passed
artifact_evidence: Real loopback and independent aioquic1.3.0/TShark4.6.9 replay passed 28 datagrams. Archive SHA256 4dfb5a3d727eb2c03851c7a32be6c2e2157c96972e168060be562e7a3ad2ebef; evidence is in /Users/npochaev/Downloads/RIPDPI_network_audits/DPI-19-fix-evidence-20261114/rebase3/.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-QUIC-ORIGINAL | ENG-1794638148243320 | Packet budget regression, IPv4/IPv6 local-error fallback and original-error tests, upstream ICMP-abort test, runtime non-Applied receipt | passed |
| REQ-QUIC-TTL | ENG-1794638149872072 | Real converter projection tests at TTL 1/23/255; earlier socket TTL23 replay | passed |
| REQ-QUIC-OVERLAP | ENG-1794638151583022 | Authenticated v1/v2 conflict rejection, equal overlap in both orders, coalesced RFC byte preservation | passed |
| REQ-QUIC-COUNT | ENG-1794638155153185 | Kotlin/Rust bounds 0/1/64/65/max, aggregate64/65, typed quota and activation tests | passed |
| REQ-QUIC-MINIMUM | ENG-1794638165336906 | v1/v2 300/1199 no-op regressions and independent short-tail replay | passed |
| REQ-QUIC-SCOPE | ENG-1794638177788370 | Original-only partial and relay paths, independent wire oracle, P0-P3 read-only review and local main fast-forward | passed |

## Exact local checks

From native/rust, use RUSTUP_TOOLCHAIN=1.100.0 and
CARGO_TARGET_DIR=/Users/npochaev/Library/Caches/ripdpi-dpi19-native:

```sh
cargo test --locked -p ripdpi-desync -p ripdpi-packets -p ripdpi-proxy-config -p ripdpi-proxy-runtime-desync-adapter --lib
cargo test --locked -p ripdpi-proxy-runtime --lib udp_plain_fallback_is_distinct_from_activation_skip
cargo clippy --locked -p ripdpi-desync -p ripdpi-packets -p ripdpi-proxy-config -p ripdpi-proxy-runtime-desync-adapter --all-targets -- -D warnings
cargo check --workspace --locked
```

All commands exited 0. Unit totals were 214 desync, 313 packets,
178 proxy-config and 48 adapter tests; no failures or ignored tests.
The receipt regression ran one test, with 401 filtered out.

From the checkout root:

```sh
./gradlew :core:data:model:testDebugUnitTest :core:data:model:ktlintCheck --offline --no-daemon
CARGO_TARGET_DIR=/Users/npochaev/Library/Caches/ripdpi-dpi19-native RUSTUP_TOOLCHAIN=1.100.0 just ci-gates
cargo +1.100.0 metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1
```

All exited 0. Kotlin ran 85 tests in 19 suites with no failures, errors or
skips. The metadata command resolved 113 workspace members and 936 packages.
The Darwin API gate skips only the Linux-owned runtime-platform snapshot.

The first shared-target API check read foreign rustdoc JSON and failed.
The isolated target check passed without snapshot or baseline edits. One
intermediate isolated run was stopped for an upstream rebase, and is not
acceptance evidence. An initial receipt filter selected no tests; the exact
filter above subsequently ran and passed the regression.

## Independent wire and source identity

The preserved archive contains the temporary production loopback test,
raw input/output bytes, PCAP, oracle scripts, exact tool versions and
per-file hashes. The temporary source test was removed after its run.
Ten raw RFC fixtures covered token, ACK, coalesced, short-tail and partial
inputs, each in v1/v2. Eighteen datagrams arrived; all ten original payloads
were unchanged. Independent tools decrypted 28 input/output datagrams:
24 complete RFC ClientHello241 streams and four partial100 prefixes.
CRYPTO offsets and lengths were correct; partial input generated no prelude.

Replay started at 44f1e556fc plus the staged local-error policy patch and
ended at d83bfbdd59. All 3,292 source files matched before/after replay.
After the metadata-only squash, the same files matched code commit
8676e7a38918ad96d9249befcaceb9e1f9afe0d1. Composite SHA256:
d62f3b4c5703a6e48ead330e6b00c057893221f1919b40af67dedbe9729eb793.
The independent reviewer found no actionable P0-P3 defects after the final
rebase and policy merge. Earlier autoreview runs passed on prior validated
bases; final closeout review is recorded separately.

## Integration and remaining acceptance

The six planning/fix commits were fast-forwarded into local main from
4ca0947874 to 8676e7a389. Each finding has one fix commit. Follow-up evidence
and contract text do not change application code. Remote push and hosted CI
are separate observations. The task remains in review while hosted CI is
required; it is not archived or declared deployment/device complete.

External QUIC endpoints, Android/VPN behavior, root activation, controlled
PMTU and server receipt of a decoy were not tested. The method remains a
stateless low-TTL prelude; it does not own client packet-number/ACK state.
