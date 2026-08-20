# DGN-1786885244559735: Fix split(host+1) strategy execution and evidence

## Objective

Make strategy diagnostics prove whether the exact configured desync plan was
applied, keep endpoint/path failures separate from execution failures, and
project a current-strategy verdict only from complete candidate-scoped evidence.

## Ownership

- Native execution lane owns `ripdpi-desync-runtime`, `ripdpi-runtime-api`,
  `ripdpi-proxy-runtime-desync-adapter`, `ripdpi-proxy-runtime`,
  `ripdpi-diagnostics-transport`, `ripdpi-monitor-proxy-runtime`, the narrow
  evidence port, terminal receipt, attempt/generation correlation, and
  production-runtime behavior tests.
- Candidate/contract lane owns `ripdpi-diagnostics-candidates`,
  `ripdpi-monitor-engine`, `ripdpi-diagnostics-contracts`, engine schema 9,
  Kotlin engine mirrors, wire fixtures, API snapshots, and field manifests.
  This is the only lane allowed to touch those serialized shared files.
- Verdict/archive lane owns `core:diagnostics` persistence, pure evaluator,
  archive schema 11, redaction/allowlists, diagnostics UI projection, and the
  exact schema-11 archive golden family. Goldens require explicit blessing.
- Verification lane is read-only except for `verification.md` and records local,
  device, hosted CI, artifact, and deployment evidence separately.
- Writers use isolated worktrees. Native execution lands before contract
  propagation; verdict/archive work begins only after the typed contract is
  reviewed. No locale, dependency, protobuf, JNI, signing, release, or
  production configuration ownership is assigned unless the implementation
  proves it is required and the scope is updated.

## Execution

- [x] RST-1786885745241507 Add a candidate-generation-bound `DesyncExecutionEvidenceSink`, stack-local first-write receipt, typed terminal runtime receipt, and RED/GREEN production SOCKS tests proving applied `split(host+1)`, plain fallback, activation skip, partial write, worker error, and late-receipt rejection #feature !high @item:DGN-1786885244559735

  Owned paths: `native/rust/crates/ripdpi-desync-runtime`, `ripdpi-runtime-api`,
  `ripdpi-proxy-runtime-desync-adapter`, `ripdpi-proxy-runtime`,
  `ripdpi-diagnostics-transport`, `ripdpi-monitor-proxy-runtime`, the smallest
  required monitor-engine runtime contracts, and focused tests. Return the
  scalar receipt from desync execution, publish once after send completion at
  the proxy boundary, carry an opaque attempt token only across the authenticated
  loopback SOCKS hop, keep packet bytes and auth secrets out of evidence, avoid
  `PcapHook` and per-action callbacks, return typed runtime errors, and keep
  endpoint response outside the execution disposition.

- [ ] DGN-1786885745283306 Isolate canonical candidate configuration, correlate per-attempt execution and response evidence, reject unproved candidates from evaluation or promotion, and atomically bump the Rust/Kotlin diagnostics engine contract from schema 8 to 9 with fixtures, manifests, and API snapshots #feature !high @item:DGN-1786885244559735

  Owned paths: `ripdpi-diagnostics-candidates`, `ripdpi-monitor-engine`,
  `ripdpi-diagnostics-contracts`, Kotlin `EngineContract.kt` and codecs, exact
  schema fixtures, native API snapshots, and field manifests. Clear or evidence
  relay/WARP/WebSocket/routing/rotation/adaptive/activation features, preserve
  `baseline_current host+1` versus catalog `split_host host+2`, handle concurrent
  HTTP/HTTPS attempts without timestamp joins, and make launch/terminal failures
  non-promotable.

- [x] DGN-1786885745300444 Replace whole-report strategy attribution with a pure baseline-current evaluator, separate RAW_PATH candidate and active-service IN_PATH roles, add precise UI/archive states, and bump archive schema 10 to 11 with v10 decode, hostile whole-ZIP privacy tests, and an explicitly approved golden family #bug !high @item:DGN-1786885244559735

  Owned paths: `core/diagnostics` session queries, path-stage execution,
  persistence/export/redaction, archive models and tests, plus the minimal `app`
  diagnostics projection and tests. Other candidates and raw connectivity do
  not credit the current strategy; inner/outer partial, deadline, plan-only,
  launch, skip, fallback, missing receipt, and zero-attempt cases remain
  incomplete or unverified. Do not infer on-wire TCP packet boundaries from
  successful application writes.

- [ ] TST-1786885745317178 Run combined Rust/Kotlin/privacy/architecture gates, independent read-only native and diagnostics reviews, packet-smoke proof of split boundaries, and a physical-device RAW_PATH versus owned active-service IN_PATH matrix; record hosted CI, debug artifact identity, and remaining deployment status without upgrading missing evidence to PASS #test !high @item:DGN-1786885244559735

  The device matrix covers Wi-Fi/cellular where available, IPv4/IPv6, concurrent
  HTTP/HTTPS, QUIC-success with HTTPS-failure, cancellation, and network
  handover. A candidate receipt proves userspace writes only; on-wire segmentation
  requires packet-smoke/PCAP in the controlled test harness.

## Verification

- TDD: record the exact RED failure before each minimum GREEN change and rerun
  after refactor. Source-text assertions and the `DirectCandidateRuntime` test
  double do not satisfy production-runtime acceptance.
- Rust targeted gates:
  - `cargo test --locked --manifest-path native/rust/Cargo.toml -p ripdpi-desync-runtime`
  - `cargo test --locked --manifest-path native/rust/Cargo.toml -p ripdpi-monitor-proxy-runtime`
  - `cargo test --locked --manifest-path native/rust/Cargo.toml -p ripdpi-monitor-engine`
  - `cargo test --locked --manifest-path native/rust/Cargo.toml -p ripdpi-diagnostics-candidates`
  - `cargo test --locked --manifest-path native/rust/Cargo.toml -p ripdpi-diagnostics-contracts`
  - `cargo test --locked --manifest-path native/rust/Cargo.toml --test packet_smoke`
  - `cargo fmt --manifest-path native/rust/Cargo.toml --all -- --check`
  - `cargo clippy --locked --manifest-path native/rust/Cargo.toml --workspace --all-targets -- -D warnings`
- Kotlin/contract gates:
  - `./gradlew :core:diagnostics:testDebugUnitTest`
  - `./gradlew :app:testGithubFullDebugUnitTest`
  - `./gradlew :core:diagnostics:detekt :app:detektGithubFullDebug`
  - owning engine wire, schema-governance, compatibility, archive renderer,
    exporter, redaction, integrity, and golden tests without blessing first
- Combined gates:
  - `./gradlew staticAnalysis`
  - `./gradlew :app:assembleGithubFullDebug`
  - `python3 scripts/ci/check_architecture_health.py`
  - `cargo metadata --manifest-path native/rust/Cargo.toml --locked`
  - `./taskctl validate`
  - strict OpenSpec validation and `git diff --check`
- Golden discipline: present the exact schema-11 fixture family and semantic diff
  for explicit blessing; rerun owning tests without a bless flag afterward.
- Privacy: inject hostile domain, SNI, IP, URL, credential, path, interface, and
  payload sentinels; scan every ZIP entry and verify only allowlisted enums and
  bounded scalars survive.
- Device proof is separate from local gates: exact strategy snapshot, applied
  receipt, owned-route evidence, path role, stable network epoch, control
  outcome, and endpoint stage must correlate for any causal verdict.
- Hosted CI, artifact hashes/signature, and deployment remain distinct evidence
  categories. This proposal turn supplies planning validation only.
