---
task_id: ENG-1794638026548237
change: quic-crypto-split-audit-fixes
commit_sha: 500374d34dab8d048857c3de0e64f7c9f7d8bb29
local: passed
local_evidence: The 500374d34d test revision passed AndroidTest Kotlin compilation, ktlint and isolated ci-gates. On prior4502477f21,783 owning Rust units and the formerly failing UDP receipt E2E passed. Original repair gates are recorded below.
remote_ci: blocked
remote_ci_evidence: Exact 4502477f21 CI43589374459 finished with50 successful jobs and one primary API35 Xray assertion failure. Test-only pin alignment is pending a new Android CI run. Historical failed/cancelled runs are not claimed as passed.
device: required
device_evidence: The CI admission test repair requires the API35 Xray E2E suite. It does not claim physical-device or DPI-19-specific Android wire acceptance.
artifact: passed
artifact_evidence: Exact500374d34d loopback and aioquic1.3.0/TShark4.6.9 replay passed28 datagrams. Archive SHA256 c0cc60df545f267e2a0e4db3cd401383a720182ee4e30173ba0c8db48c94e4d5; durable evidence is in /Users/npochaev/Downloads/RIPDPI_network_audits/DPI-19-fix-evidence-20261114/post-resume500/.
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

## Implementation-phase checks on code revision 8676e7a389

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
are separate observations. The task remains in review; its latest evidence is recorded below.
No deployment or device completion is claimed.

External QUIC endpoints, Android/VPN behavior, root activation, controlled
PMTU and server receipt of a decoy were not tested. The method remains a
stateless low-TTL prelude; it does not own client packet-number/ACK state.

## Acceptance on resumed main revision 4502477f21

The final code revision is 4502477f217a0b866a518a594467f51329e59b1f.
A bounded read-only review confirmed that subsequent main changes preserve
F1-F5: fixed TTL without adaptive policy, local-error original fallback,
conflicting overlap rejection, independent Crypto64 quota and minimum-size
and input-budget guards. Adjacent DPI-23/24/25 technologies were not audited.

On this exact revision, the four owning Rust library suites passed 783 tests:
226 desync, 323 packets, 184 proxy-config and 50 adapter; no failures or
ignored tests. The exact UDP receipt integration regression also passed
(one test; five filtered out). Both commands used --locked and Rust1.100.0.

The independent wire replay again passed all 28 inputs/outputs: ten fixtures,
eighteen real UDP deliveries,24 complete RFC ClientHello streams and four
partial prefixes. Every original was unchanged; sizes and CRYPTO offsets
were correct. All 3303 source files matched before/after replay; SHA256:
3591adafd4a1d6cbcc43273b6cd120f58d0acc0968d5ecf94200bfbbf88854f9.

The historical 4f CI exposed a 1196-byte shared test seed. Its packet number
was counted twice. Commit a33c8d3afa repairs that helper and adds an owning
1200-byte/authenticated v1/v2 test. The isolated prototype reproduced 1196
before this already-landed repair was adopted; no duplicate test was added.
The current owning test, receipt E2E and enabled hosted CLI packet smoke
pass. The two other 4f failures also occurred on its exact upstream base:
Go asset builder and stale artifact-action contract. Upstream commits
f66af6cce3 and 90e12607c0 repair them; they are outside the DPI-19 code diff.

Historical final autoreview runs raised two P2 claims. Both were rejected
against actual contracts by an independent reviewer. The count<=0 guard
precedes quota subtraction; an exact-input temporary public planner test
passed negative/MIN combinations for v1/v2. AttachDropSack/DetachDropSack are
explicit UDP no-ops, so the early fallback does not leak a socket filter.
Both raw exit1 results and the rejection evidence remain in the report;
no clean exit0 is attributed to those runs. Application code was unchanged.

CI-STATUS: Exact450 CI43589374459 finished with50 successful jobs,18 skips
and two failures: API35 Xray E2E plus its aggregate ci-required. All QUIC/Rust,
coverage, assets, lint and other Android jobs passed. The failing test still
required26.3.27 although the packaging pin and native app log report26.11.9.
The actual snapshot field was not captured in that run, so null/unknown at
the failed instant is not excluded. A test-only repair aligns both real
backend checks with the packaged pin and prints the observed value on failure.
No runtime, version pin, dependency or workflow changes are included.
The local AndroidTest Kotlin compile passed after the repository fetch script
provided the missing verified arm64 NaiveProxy artifact. A fresh hosted API35
run is required to establish the updated assertion's runtime result.

## Latest CI-admission test revision 500374d34d

A concurrent main change c48b84610e replaced the two stale Xray version
assertions with exact equality against 26.11.9. The linked bridge always
returns the prefix "Xray " plus the native version; runtime owner, session
controller, snapshot derivation and diagnostics pass that value unchanged.
The new equality therefore needs the complete expected string "Xray 26.11.9"
with one space after Xray. Revision 500374d34d changes only that shared test
constant and documents the format, preserving both exact assertions.
The unpublished contains-based prototype 7344fba271 was not pushed and was
superseded after main advanced. No production Xray or version pin changed.

Local compileGithubFullDebugAndroidTestKotlin passed on the final test
semantics in 2m19s; ktlint and isolated just ci-gates also passed. The first
local compile attempt lacked the pinned upstream NaiveProxy artifact. The
repository fetch script verified and supplied the arm64 artifact; no task
was skipped. An independent read-only review verified the format contract
and the unchanged F1-F5 boundary in later main commits. The actual snapshot
value in the historical failed CI remains unknown; the new CI is required.

The fresh locked wire probe passed 1/1, and independent tools passed 28/28
input/output datagrams on 500374d34d. All 3312 native/model source files
matched before/after, SHA256:
5010564af633f3be16e6c5598b63c8cad428fef24bb6ca5eba79c17158aef8a4.
Ten originals were unchanged, 18 datagrams arrived, and min-size/offset
checks passed. Artifact SHA256 is in the frontmatter. No full unit suite or
Xray runtime acceptance is inferred from this narrow wire replay.
