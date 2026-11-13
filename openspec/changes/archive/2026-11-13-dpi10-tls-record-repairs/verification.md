---
task_id: RST-1794557076132326
change: dpi10-tls-record-repairs
commit_sha: a66db0527290dd16df76ad21c64279066dd28814
local: passed
local_evidence: Exact source SHA passed 439 owning Rust tests, workspace all-targets check, targeted Clippy, formatting, ci-gates, locked metadata and task validation. Each finding has a separate commit and regression evidence. Independent integration review is recorded below.
remote_ci: not_applicable
remote_ci_evidence: No hosted CI run is requested. Local gates passed; publication does not establish hosted CI acceptance.
device: not_applicable
device_evidence: No Android lifecycle or JNI contract changed. Physical-device, root activation and external probes were not authorized or run. Device compatibility is not claimed.
artifact: passed
artifact_evidence: Production runtime adapter and actual loopback peer passed 31 independent OpenSSL wire cases, including 28 complete TLS handshakes. All cases preserved reassembled payload bytes. Three ECH cases prove opaque-byte preservation and activation only.
deployment: not_applicable
deployment_evidence: No release or deployment is owned by this change.
---

# Verification

## Findings and requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TLSREC-OFFSET | RST-1794557341725489 | F1, commit 77d1a57509. Complete captured TLS 1.2/1.3 inputs, exact payload boundaries, applied counts and reassembly. The initial regression failed at 100 versus independently expected 105. Both adaptive callers now apply one conversion. Resolver fallback is tested directly; its end-to-end reachability on a recognized ClientHello is not claimed. | passed |
| REQ-TLSREC-BUDGET | RST-1794557342575436 | F2, commit e34ab7f1bc. The planner accepts the 1024-iteration boundary, rejects 1025 before mutation, and retains useful skip behavior after zero/duplicate positions. The pre-fix regression failed. Existing runtime planner-error handling sends the original effective payload. | passed |
| REQ-TLSREC-EVIDENCE | RST-1794557343352527 | F3, commit 3b5acce2e5. Adaptive regressions use complete captures, require an applied operation, and assert independently known boundaries and payload bytes. Random-record test limits permit a valid layout. | passed |
| REQ-TLSREC-MULTIRECORD | RST-1794557344427913 | G1, commit a66db05272. Corpus checks all 1116 inter-byte input splits across three captures. Tests cover split handshake header/vectors/SNI, existing boundaries, coalesced final payload, later records and incomplete contributing records. Production adapter integration plus independent OpenSSL handshakes exercise actual received bytes. | passed |
| REQ-TLSREC-ECH | RST-1794557344427913 | G1, commit a66db05272. Split-SNI ECH activation, no-ECH negatives, invalid envelope classification negatives and three independent opaque-byte wire cases. Regression without the classification guard fails; the guarded metadata path is not inferred from a parser-only assertion. | passed |

The four finding commits remain separate after rebase onto origin/main
`2824082bff0428eb269e0576712174f6f6a91776`. Upstream DPI-11 bounds and
DPI-13 runtime fixes are retained. The only final rebase conflict was in the
proxy-engine documentation; both the TLS repeat budget and upstream capability
and fallback descriptions were kept.

## Exact local checks

Compiler-backed commands ran through `build-gate --lane ripdpi`, with
`CARGO_BUILD_JOBS=4` and `CMAKE_BUILD_PARALLEL_LEVEL=4`.
From `native/rust`:

```sh
cargo test -p ripdpi-desync -p ripdpi-desync-runtime -p ripdpi-proxy-runtime-desync-adapter --locked --jobs 4
cargo check --workspace --all-targets --locked --jobs 4
cargo clippy -p ripdpi-desync -p ripdpi-desync-runtime -p ripdpi-proxy-runtime-desync-adapter --all-targets --locked --jobs 4 -- -D warnings
cargo build -p ripdpi-proxy-runtime-desync-adapter --locked --jobs 4 --message-format=json
cargo fmt --all -- --check
```

From the checkout root:

```sh
python3 /tmp/ripdpi-dpi10-acceptance.L0tbZj/compile_probe.py
python3 /tmp/ripdpi-dpi10-acceptance.L0tbZj/oracle.py
just ci-gates
cargo metadata --manifest-path native/rust/Cargo.toml --locked --no-deps --format-version 1
./taskctl validate
```

All ten stages returned exit 0 at the exact source SHA above. The owning suites
reported 204 + 183 + 52 = 439 passed, with zero failed, ignored or filtered.
The workspace check included all targets. The API gate matched all applicable
snapshots; the Linux-owned runtime-platform snapshot is not evaluated on Darwin.
The full locked metadata command without `--no-deps` also passed before the last
rebase; no manifest or lockfile changed in that rebase.

The temporary Rust harness links the current production adapter, uses a real
127.0.0.1 TCP receiver, and saves raw received bytes. It uses no fake platform or
recorded-call oracle. Python SSL MemoryBIO with OpenSSL 3.5.5 independently parses
record headers, checks expected boundaries, payload and handshake lengths, and
completes TLS 1.2/1.3 handshakes with a server SNI callback. Seven input cuts,
two marker modes and two TLS versions give 28 handshakes; three captured ECH
cases check the extension gate and opaque-byte equality. TLS record boundaries
are verified on the received TCP byte stream; TCP packet segmentation is not
claimed. Synthetic local certificate material is used; no real secrets are read.

Exact commands, exit codes and source SHA are in
`/tmp/ripdpi-dpi10-acceptance.L0tbZj/checks.json`; independent case results are in
`results.json` in the same directory. Raw input/output files and per-stage logs
are retained there. Previous source-SHA results are preserved in
`verified-1dd4132597/`; the final results above supersede them.

## Review and limits

Independent pr-reviewer found no actionable issue on the final source SHA.
The review confirmed the preserved upstream multidisorder validation, adaptive
bounds, classification guards and multi-record/tail behavior. It also checked
the resolved documentation conflict and `git diff --check`.

C covers the implementation and callers reviewed. V covers the observed tests
and actual loopback bytes. S is limited to the record framing and byte-preserving
requirements checked during the audit; this is not full TLS conformance.
External-network E, Android VPN/JNI acceptance, physical transport, root activation,
HRR/renegotiation activation and cryptographic ECH acceptance were not run.

The budget is per step, not a total chain/parser budget. Inputs above the existing
synthetic record's 16-bit length representation remain an explicit no-op. The
first-flight inspection window and size limits are unchanged. No-SNI and
unsupported metadata layouts remain outside transformation support. The public
first-flight IR, dependencies, schemas, baselines and golden fixtures were not
changed. These boundaries are documented in `docs/native/tls-record-prelude.md`.
