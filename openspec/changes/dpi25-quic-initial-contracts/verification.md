---
task_id: ENG-1794638006879351
change: dpi25-quic-initial-contracts
commit_sha: 5cc3d66283a19621deada6f96aaffe28d7704b53
local: blocked
local_evidence: 966 owning Rust tests and 11 owning Kotlin tests passed. Build, wire oracle, ci-gates, architecture health, locked metadata and separate review passed. Full staticAnalysis is blocked by missing shared-cache files and Maven Central No route to host; the single online recovery restored Kotlin tests but not full static analysis.
remote_ci: not_applicable
remote_ci_evidence: Local acceptance owns these source gates. No hosted CI result is claimed; the authorized main push can start hosted checks.
device: not_applicable
device_evidence: No Android lifecycle or JNI contract change. Host socket acceptance does not establish Android device or external network acceptance.
artifact: passed
artifact_evidence: Production config conversion, planner and UDP emitter passed 40 IPv4/IPv6 loopback cases. Independent aioquic 1.3.0 decrypted and admitted 156 decoys, with 156 unique key/nonce pairs; 20 original and 20 SOCKS original datagrams were preserved.
deployment: not_applicable
deployment_evidence: No release or deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Observed evidence | Result |
|---|---|---|---|
| REQ-DPI25-SEED | ENG-1794638217039651 | Both profiles, v1/v2: empty legacy session ID, TLS 1.3, h3, QUIC transport parameters and matching initial_source_connection_id; independent decode and server admission | passed |
| REQ-DPI25-STATE | ENG-1794638218458787 | Each decoy uses an independent connection, fresh 16-byte DCID/SCID, PN zero and one complete CRYPTO transcript at offset zero; 156 unique key/nonce pairs in received bytes | passed |
| REQ-DPI25-TTL | ENG-1794638219805332 | Fixed and adaptive settings survive UDP group conversion; resolver result reaches the planner; actual IPv4/IPv6 socket tests verify selected settings and restoration | passed |
| REQ-DPI25-COUNT | ENG-1794638221270523 | Kotlin/native per-step and chain limits of 32; count one means two packets, zero disables; invalid internal counts return original-only before chain cloning | passed |
| REQ-DPI25-SIZE | ENG-1794638223409621 | Encoded size is 1200 bytes; boundary tests cover both versions, a 253-byte host, input sizes through 65000, and oversized seed omission; independent decrypt verifies emitted packets | passed |
| REQ-DPI25-FALLBACK | ENG-1794638231446674 | Original-only invalid-model cases, upstream prelude-error fallback tests, real direct/relay execution, source gates and independent review; full staticAnalysis remains blocked by repository access | behavior passed; gate blocked |

## Repair commits

| Finding | Commit | Change |
|---|---|---|
| F1 | 178beffb33 | Build a separate valid synthetic QUIC seed instead of using the TCP TLS template on the DPI-25 path |
| F2 | c4b513c8ce | Give each alternating decoy an independent connection and fresh seed/CIDs |
| F3 | 07398ce468 | Preserve fixed/adaptive UDP TTL and consume the existing resolver result |
| F4 | 86c4a2c35c | Bound each step and the aggregate DPI-25 chain at 32 packets across Kotlin, native conversion and planner |
| F5 | f7d47d01ee | Check actual encoded length against the fixed 1200-byte unknown-PMTU budget |

The combined tree preserves the concurrent CRYPTO split, CID churn, DummyPrepend and padding ladder repairs. The ladder keeps its own connection basis, numbered packets and length checks. The integration removes one duplicate fixed-TTL assignment; adaptive TTL remains present.

The original audit is `/Users/npochaev/Downloads/RIPDPI_network_audits/DPI-25.md`. It records the original checkout and remains unchanged. The audit registry, dependencies, baselines and frozen fixtures are unchanged by this repair.

## Exact final checks

Commands run from `native/rust` use the pinned Rust toolchain, `CARGO_BUILD_JOBS=4`, the guarded build lane and the isolated target at `/Users/npochaev/GitHub/RIPDPI-dpi25-repairs/target`.

```sh
bash ../../scripts/ci/cargo-guarded.sh cargo test --locked --jobs 4 \
  -p ripdpi-config -p ripdpi-packets -p ripdpi-desync \
  -p ripdpi-proxy-config -p ripdpi-proxy-runtime-desync-adapter \
  --lib --no-fail-fast
bash ../../scripts/ci/cargo-guarded.sh cargo build --locked --jobs 4 \
  -p ripdpi-proxy-runtime-desync-adapter --lib --message-format=json
cargo metadata --locked --format-version 1
```

| Suite | Passed | Failed / ignored / filtered |
|---|---:|---|
| ripdpi-config | 191 | 0 / 0 / 0 |
| ripdpi-desync | 225 | 0 / 0 / 0 |
| ripdpi-packets | 317 | 0 / 0 / 0 |
| ripdpi-proxy-config | 183 | 0 / 0 / 0 |
| ripdpi-proxy-runtime-desync-adapter | 50 | 0 / 0 / 0 |
| Total | 966 | 0 / 0 / 0 |

Commands from the checkout root:

```sh
env -u CARGO_BUILD_JOBS ./gradlew :core:data:model:testDebugUnitTest \
  --tests com.poyka.ripdpi.data.StrategyChainValidationTest --no-configuration-cache \
  --max-workers=4 -Pripdpi.skipNativeBuild=true -Pripdpi.nativeCpuBudget=4 \
  -Dorg.gradle.internal.http.connectionTimeout=3000 -Dorg.gradle.internal.http.socketTimeout=5000
env -u CARGO_BUILD_JOBS ./gradlew staticAnalysis --no-configuration-cache \
  --max-workers=4 -Pripdpi.skipNativeBuild=true -Pripdpi.nativeCpuBudget=4 \
  -Dorg.gradle.internal.http.connectionTimeout=3000 -Dorg.gradle.internal.http.socketTimeout=5000
just ci-gates
python3 scripts/ci/check_architecture_health.py
just checkout-doctor
```

The Rust checks, build, owning Kotlin test, ci-gates, architecture and locked metadata returned exit 0. The copied recovery JUnit XML reports 11 tests and no errors, failures or skipped tests. Rust API snapshots match. No baseline was expanded. Commit hooks passed strict Clippy for the changed Rust crates and formatting checks.

The final full staticAnalysis did not pass. Its first offline run found missing shared Gradle transforms and dependencies. One online retry with a fresh configuration restored the Kotlin test gate, but staticAnalysis stopped after 252 tasks because Maven Central returned `No route to host` for the gson 2.8.9 POM and kotlin-stdlib-common 2.2.21 POM. The failed tasks include `:core:service:checkDebugTestFixturesAarMetadata` and `:app:loadKtlintReporters`. This result establishes an environment blocker, not a source defect. Exact errors are in `recovery-static-analysis.log` and exit codes in `release-status.json` and `recovery-status.json`. No dependency version, repository or trust setting was changed.

The earlier combined tree passed full staticAnalysis in 3m03s with 827 tasks; this result is historical and is not transferred to the final rebased tree. The next required check is the same full staticAnalysis command above when Maven Central is reachable. Keep the task in review with local acceptance blocked; do not archive it as fully accepted.

Earlier shared-target API errors were not used as acceptance. The final source gates use the isolated Cargo target and passed. Temporary worktrees and later `build/reports` disappeared during the session; their exact removal cause is not established. The current evidence is outside `build` and includes fresh raw captures.

## Independent wire acceptance

Persistent scratch evidence is under `/Users/npochaev/GitHub/RIPDPI-dpi25-repairs/.audit-dpi25/`. It is outside the committed application source. The final batch is the release validation script; individual exit codes are in `release-status.json` and `release-status.tsv`. The sole Gradle retry is recorded in `recovery-status.json`.

The normal Cargo JSON identifies the exact linked libraries. `compile.py` links an external public-caller harness against those artifacts. The harness uses production UI config conversion, group selection, planning and the actual UDP emitter over connected loopback sockets. It does not use a recorded-call fake.

```sh
.audit-dpi25/venv/bin/python .audit-dpi25/oracle.py inputs
python3 .audit-dpi25/compile.py
.audit-dpi25/venv/bin/python .audit-dpi25/oracle.py
```

The matrix is v1/v2 × IPv4/IPv6 × counts 0/1/2/3/32 × direct/SOCKS relay: 40 cases and 196 received UDP datagrams. The independent aioquic 1.3.0 crypto/parser implementation verified 156 decoys. For each it checked the actual encoded length, version, PN, CRYPTO frame, SNI, ALPN, TLS version and transport parameters. A real local server connection admitted every decoy, negotiated h3 and produced a response without a close event. Test keys exist only in memory; no real secrets or external server are used.

| Wire result | Count |
|---|---:|
| Independently decrypted and admitted decoys | 156 |
| Unique version/DCID/PN key-and-nonce identities | 156 |
| Byte-identical direct originals | 20 |
| SOCKS frames containing byte-identical originals | 20 |

Raw data: `v1_input.bin`, `v2_input.bin` and the 196 `*_relay*.bin` outputs. `release-decoded.json` records decoded fields, receiver events and SHA-256 values. `release-linked-artifacts.json` records library hashes. `release-rust-tests.log`, `recovery-kotlin-gradle.log`, the copied recovery JUnit XML, `recovery-static-analysis.log`, `release-ci-gates.log` and release architecture/metadata files preserve the gate evidence.

## Evidence limits and remaining checks

- C: Source paths and callers were inspected. Separate read-only review found no actionable issue in the repairs or either conflict resolution.
- V: The final combined source passed the Rust/Kotlin tests, source gates and host socket runs above. Full staticAnalysis remains blocked as described above. Socket option snapshots and restoration were observed; received IP TTL/hop-limit ancillary data was not captured.
- S: Selected Initial/TLS/ALPN/transport-parameter/nonce requirements were checked. This does not certify all QUIC behavior. See RFC 9000 sections 12.3 and 14.1, RFC 9001 sections 5.3, 8.2 and 8.4, and RFC 9114 section 3.2, as cited in the audit.
- E: Exact Chrome/Firefox version fingerprints, a complete browser handshake, Android device behavior, external network compatibility and DPI effectiveness remain unestablished. No physical-device probe, external active probe or root activation was run.

OS entropy failure returns no decoy through the inspected Option path; that system failure was not fault-injected. Full browser-capture comparison and device/external acceptance need a separate authorized run. These checks are not substituted by enum, planner or fixture presence.
