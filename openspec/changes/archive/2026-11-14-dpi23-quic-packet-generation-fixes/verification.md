---
task_id: ENG-1794637912833130
change: dpi23-quic-packet-generation-fixes
commit_sha: 90728fab830c7b4fd26a3e27989fe8ff231becc9
local: passed
local_evidence: Nine local gates passed with 1314 Rust tests and 87 Kotlin tests; reviewed source hashes were unchanged. Permanent gap and response regressions passed again at the exact code commit.
remote_ci: not_applicable
remote_ci_evidence: No hosted CI run is owned by this local acceptance. Push and remote status are reported separately.
device: not_applicable
device_evidence: Host and loopback acceptance only; Android device and VPN acceptance were not run.
artifact: not_applicable
artifact_evidence: No APK or release artifact is produced by this source correction.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Observed evidence | Result |
|---|---|---|---|
| REQ-DPI23-BOUNDS | ENG-1794638238128582 | Full native admission/planner libraries and model JVM suite; counts 0/64/65/i32::MAX, aggregate count and inactive-step budget regressions | Passed |
| REQ-DPI23-CONTEXT | ENG-1794638241897818 | Four permanent v1/v2 gap tests, entropy-error unit test, production UDP emitter and independent TShark decoding | Passed |
| REQ-DPI23-RESPONSE | ENG-1794638245227662 | Five permanent receive tests, including real loopback decoy-only then original-correlated VN and bounded multiplexed contexts | Passed |
| REQ-DPI23-BOUNDS | ENG-1794638247470573 | Independent review; nine gates; architecture health in ci-gates; locked metadata | Passed |
| REQ-DPI23-CONTEXT | ENG-1794638247470573 | Review of incoming SCID/transport-parameter contract; independent wire equality and ALPN h3 | Passed |
| REQ-DPI23-RESPONSE | ENG-1794638247470573 | Independent review of receive/feedback, overflow, retry and fallback interactions | Passed |

## Exact scope and evidence

The three fix commits are c538b19c81a3c19aff3537e51e8ea15d16d35472, 4ee12ad1c0ad3b7339fcc5151a14aa3e84fe2caf and 90728fab830c7b4fd26a3e27989fe8ff231becc9. The local batch used main base 4f0d5e827900f125583c0fb8ebccbd349a7bc0c7. All nine gates passed on the reviewed working-tree source. SHA-256 manifests tie those bytes to the code commits; the permanent gap and receive regressions then ran at 90728fab830c7b4fd26a3e27989fe8ff231becc9. Task and verification documentation followed those code checks.

The observed Rust total is 1314 passed, zero failed, ignored or filtered. The model Debug library JVM total is 87 passed, zero failed, errors or skipped. Clippy with warnings denied, Rust formatting, the two-file ktlint check, and just ci-gates all exited zero. The direct Cargo test, Clippy and metadata commands used --locked; test and Clippy commands used four jobs. Gradle used four workers and native CPU budget four. The unchanged repository ci-gates recipe checked applicable Darwin API snapshots; its Linux-owned ripdpi-runtime-platform snapshot was skipped on Darwin.

A temporary production-adapter fixture ran eight IPv4/IPv6 by v1/v2 by direct/relay cases. It received 20 real UDP datagrams. Each direct case received three decoys plus the exact original. Each relay case received one framed original and reported RelayPathSkippedPacketMutation. The probe source was saved outside the checkout, then removed before the fix commits.

TShark independently decoded 16 actually received QUIC payloads in four groups. It read packet numbers 2, 4, 6 and original 0, a four-byte packet-number encoding and no decryption failure. It checked stable IDs within a series, distinct DCIDs across series, configured fake authority, header SCID equal to initial_source_connection_id and ALPN h3. PCAP files add synthetic IPv4/UDP headers around unchanged received payloads; they are not raw IP captures and do not prove transmitted TTL.

The independent reviewer found no actionable CRITICAL, WARNING or SUGGESTION findings after both incoming-main merges. The review preserved the independent dummy/churn/crypto/gap budgets and all adjacent tactic limits. The fresh DCID contains raw 128-bit OS entropy. SCID and ClientHello fields derive from separate OS entropy through the existing helper. A 32-bit SCID is not promised to be unique; Initial key freshness comes from the DCID.

## Commands

All heavy commands ran within one build-gate --lane ripdpi invocation, with CARGO_TARGET_DIR set to a persistent isolated target, CARGO_INCREMENTAL=0 and CMAKE_BUILD_PARALLEL_LEVEL=4.

```text
cargo test --locked --jobs 4 -p ripdpi-config -p ripdpi-packets -p ripdpi-proxy-config -p ripdpi-desync -p ripdpi-proxy-runtime --lib --no-fail-fast
cargo test --locked --jobs 4 -p ripdpi-desync --test quic_packet_number_gap
cargo test --locked --jobs 4 -p ripdpi-proxy-runtime-desync-adapter --test dpi23_local_acceptance -- --nocapture
cargo clippy --locked --jobs 4 -p ripdpi-config -p ripdpi-proxy-config -p ripdpi-desync -p ripdpi-proxy-runtime --all-targets -- -D warnings
cargo fmt --all -- --check
python3 /Users/npochaev/GitHub/dpi23-recovery-evidence/final-8676/wire_oracle.py
/opt/homebrew/bin/ktlint core/data/model/src/main/kotlin/com/poyka/ripdpi/data/StrategyChainValidation.kt core/data/model/src/test/kotlin/com/poyka/ripdpi/data/StrategyChainValidationTest.kt
env -u CARGO_BUILD_JOBS ./gradlew :core:data:model:testDebugUnitTest --max-workers=4 -Pripdpi.nativeCpuBudget=4 --console=plain
just ci-gates
cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1
cargo test --locked --jobs 4 -p ripdpi-proxy-runtime --lib runtime::udp::tests::quic_response
```

## Boundaries and evidence levels

- C: current config, planner, emitter and feedback paths were reviewed. Counts are bounded at admission and direct model planning. Reply attribution remains a bounded local heuristic, not peer authentication or a QUIC endpoint.
- V: the local commands and actual loopback datagrams above were observed. Earlier queued attempts were canceled before execution when main changed; they provide no test result. Earlier old-base passing results are retained separately and are not substituted for this batch.
- S: the checked requirements are QUIC v1/v2 Initial protection and packet-number rules, key/nonce context, and SCID/transport-parameter equality. References: [RFC 9000 sections 7.3 and 17](https://www.rfc-editor.org/rfc/rfc9000.html#section-7.3), [RFC 9001 sections 5.2-5.4](https://www.rfc-editor.org/rfc/rfc9001.html#section-5.2), and [RFC 9369 section 3](https://www.rfc-editor.org/rfc/rfc9369.html#section-3). This is not a claim of complete QUIC compliance.
- E: external interoperability, full server handshake, Android/VPN paths, root activation and physical-device behavior were not run or established.

Incoming main fixes now reject input datagrams shorter than 1200 bytes and conflicting CRYPTO overlaps during normalization; the gap planner preserves original-only forwarding in those cases. Incoming main also makes configured UDP TTL reachable and preserves original forwarding after local size/buffer errors when default TTL restoration succeeds. Those adjacent fixes retain their own commits and ownership.

Local logs, source manifests, exact commands, JUnit XML, received bytes, TShark output, independent review and probe source are saved in /Users/npochaev/GitHub/dpi23-recovery-evidence/final-8676/. The original audit report and registry remain unchanged.
