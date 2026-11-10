---
task_id: RLY-1794325389593996
change: ssh-key-resource-budget
commit_sha: null
local: passed
local_evidence: 51 native tests, 7 independent Go SSH cases, clippy, source gates, architecture health, and locked metadata passed. Formal pr-reviewer backend was blocked; the separate expanded source and async review passed.
remote_ci: blocked
remote_ci_evidence: Hosted CI has not been observed.
device: blocked
device_evidence: No Android or physical-device run is included.
artifact: blocked
artifact_evidence: No Android artifact build is included.
deployment: not_applicable
deployment_evidence: This change has no deployment.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SSH-KEY-BUDGET | RLY-1794325490443746 | CPU 8 s, wall 10 s, RSS 256 MiB local PoC; metadata boundaries and six real decrypt containers. | C + V; confirmed and regression-tested |
| REQ-SSH-KEY-OWNER | RLY-1794325490946426 | Same heartbeat test fails on old source at1214ms; passes after offload. Queue cancel, deadline, and permit-release regression. | C + V; passed |

RLY-1794325491422744 records source, async, locked native, and required source gates.

## Local reproduction

Base: `71a4c30824c79f6d4c4e886ac2c0903eff9c0529`. Ephemeral key and isolated source copy:
`/tmp/rly11-h1-poc-n8czi2tx`. No original-checkout or real-credential changes.

`python3 /tmp/rly11-h1-poc-n8czi2tx/run_bounded.py` executed six sequential
cases: current-thread and two-worker runtimes, bcrypt rounds 1/16/64. Each process
had CPU 8 s, wall 10 s, and parent-enforced sampled RSS 256 MiB limits. All exited 0;
no watchdog fired. At 64 rounds, current-thread heartbeat stalled 1205 ms and
cancel-to-close took 1224 ms; two-worker cancel-to-close took 1195 ms. Peak RSS was
at most 8128 KiB. Cancel-to-close includes an intentional 80 ms observation delay.
The one-round positive control authenticated successfully. Higher-round vectors
changed only metadata, so they execute real KDF but cannot decrypt successfully.

The first RLIMIT_DATA launcher failed before child execution on macOS. It is
not run evidence. The replacement parent RSS watchdog produced the six results.

The final kept heartbeat regression was also copied into the isolated old source.
`cargo test --locked -p ripdpi-ssh --test key_resources -- --nocapture` failed
as expected: 5 ms heartbeat stalled 1.214070375 s. The patched source passes the
same assertion with maximum gap below 400 ms and an observed actual decryption error.
Receipts: `/tmp/rly11-h1-final-red.log`, `bounded-results.json`, and the bounded
case logs. The earlier 100 ms-cancel test failed at 1.219936916 s; it was strengthened
so slow key exchange cannot let the test pass before reaching KDF work.

## Design acceptance and reviews

The user selected an explicit cost budget and retained pinned crypto. The final
PPK limit is 64 passes and 524288 KiB memory*passes, preserving the pinned public
34-pass fixture. Scrypt's 32 MiB total retains OpenSSL's default N=16384/r=8/p=1.
These limits do not guarantee an elapsed-time bound on every device.

The independent async reviewer found no production defects. It also checked the
resource guard against pinned decoder branches, including relabeled encrypted
DER and duplicate PPK fields. Its two test findings were corrected: continuous
heartbeat must span actual decryption; peer timeout retains then abort/joins its
handle. The formal pr-reviewer backend rejected its request before review with
`This content was flagged for possible cybersecurity risk`. That formal review
is blocked, not passed. No policy, gate, or dependency was weakened.

## Source and compatibility scope

The only executable decoder caller is ripdpi-ssh authentication. Validation occurs
before cloning credentials or starting the network constructor. Password auth is
unchanged. Invalid or over-budget private keys fail runtime admission; the Android
profile import/editor does not receive a new parser or schema. Decryption moves
into one owned blocking job, with a process-wide permit. Active work cannot be
preempted; constructor and successful close join it. Queue cancellation starts no
job. Test fixtures are disposable or unchanged public upstream examples; expected
fingerprints come from independent OpenSSL DER or OpenSSH public blobs.

No new S claim is made: these are product resource limits and pinned library
contracts, not SSH wire requirements. Local loopback evidence is V; the independent
Go SSH peer is a separate E claim only after its actual run.

## Final observed checks

All compiler-backed checks used the existing `ripdpi` build-gate lane and at most
two jobs. No temporary lane or machine configuration was added.

```sh
# native/rust, pinned rustc 1.98.1
cargo test --locked -p ripdpi-ssh
cargo clippy --locked -p ripdpi-ssh --all-targets -- -D warnings
cargo metadata --locked --format-version 1 > /tmp/rly11-h1-cargo-metadata.json
# job root
PATH=/tmp/rly11-locked-tools:$PATH just ci-gates
python3 scripts/ci/check_architecture_health.py
zsh /tmp/rly11-h1-peer-checks.sh
```

| Check | Observed result |
|---|---|
| Native suite | 37 unit + 1 compatibility test (6 containers) + 5 resource-limit tests + 1 heartbeat + 7 loopback = 51 passed. |
| Peer suite | All 7 existing SSH peer cases ran explicitly: password, encrypted private key, wrong pin, same-key rekey, changed-key rekey, and unread close on both runtime flavors. Each had 1 passed and 0 ignored. |
| Independent peer | Clean Mieru source `155ebbd60f86e472586a60d7ffe58ec8f8682cb1`, unchanged Go x/crypto/ssh v0.33.0; peer SHA256 `903cec91661ecb5f66b3dee3368d9c12c2cb11cd86932dde7f66abe9cd9ca528`. Loopback only. |
| Clippy | Passed with warnings denied. The initial collapsible-if warning was fixed without an exclusion. |
| Source gates | `just ci-gates` passed. API snapshots matched for 12 eligible crates; the existing Linux-owned snapshot was skipped on macOS. |
| Architecture | No new or worsened indicators: 18 existing indicators. |
| Metadata | Locked workspace resolution passed; Cargo.lock has no diff. |
| Planning | Strict OpenSpec and task contracts passed. |

The ordinary native invocation reports the 7 optional peer tests as ignored;
that is not the peer evidence. The runner executed each of them separately.
All six positive container decodes match independently computed public-key digests.
OpenSSL fixture generation used version 4.1.0; PPK fixtures are unchanged public
ssh-key examples from revision `7a1c757d2e2fcde32680e373d5226481de6d434e`.
The original 32-pass Argon2 draft limit rejected that public 34-pass control;
the final explicit policy admits it and is covered by the positive test.

An early shared-output run reused the old russh metadata after the isolated red
build. The new exports were absent from that cached artifact. After this observed
stale-output failure, `cargo clean --locked -p russh` and the full native run
rebuilt the package. No application source or lockfile was changed to hide it.
The old malformed-key test was updated to require rejection before network/runtime
creation, since resource admission now parses the OpenSSH envelope earlier.

Receipts: `/tmp/rly11-h1-final-native.log`, `/tmp/rly11-h1-ci-gates.log`,
`/tmp/rly11-h1-peer.log`, `/tmp/rly11-h1-architecture.log`, and locked metadata.
The temporary API wrapper inserts --locked into the Cargo metadata/rustdoc calls;
it does not change repository gates. Actual test output paths identify the target
cache used by the shell environment.

## Remaining acceptance

No Android ABI build, emulator/device run, physical-network test, hosted CI, or
artifact publication was performed. The formal pr-reviewer backend remains blocked.
These gaps do not establish those results. This change remains in review and is
not archived. The final integration SHA is recorded in the external RLY-11 audit
report after commit; this in-commit record cannot contain its own hash.
