---
task_id: TRN-1794387054491059
change: repair-trn08-pool-lifecycle
commit_sha: 062db9e313301cd4a67d633a7691b4cefb55ef4a
local: passed
local_evidence: Owning suites passed 407 tests with four existing ignored tests; workspace Clippy and ci-gates passed. Independent PR and async reviews passed. Miri passed four seeds for the shutdown race. Architecture health and full locked metadata passed.
remote_ci: not_applicable
remote_ci_evidence: This repair owns local verification and an authorized source push. Hosted CI is observed if triggered, not a substitute for local proof.
device: not_applicable
device_evidence: No Android UI, JNI signature, physical transport, or device-specific implementation change is planned. The runtime-owner regression runs locally.
artifact: not_applicable
artifact_evidence: No release binary is owned by this repair.
deployment: not_applicable
deployment_evidence: No deployment is owned by this repair.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TRN08-GENERATION-IDLE | TRN-1794387330412891 | Two generation regressions failed before the fix. All three generation tests passed in the combined mux suite. Independent review passed. | passed |
| REQ-TRN08-OPEN-BOUND | TRN-1794387337580080 | The original audit observed 16 pending real loopback connections. Both late-open regression variants now admit eight attempts, release a completed slot, and return capacity on cancellation. Independent review passed. | passed |
| REQ-TRN08-CLEANUP-RETENTION | TRN-1794387345077452 | Terminal-error owner retention and cancelled late-create cleanup each failed before their fixes. All nine registry tests passed, including distinct first and later errors. Independent async review passed. | passed |
| REQ-TRN08-DATAGRAM-ISOLATION | TRN-1794387354791990 | All three independent-datagram regressions failed before the fix and passed afterward. The production VLESS constructor test passed without establishing a yamux carrier. Independent review passed. | passed |
| REQ-TRN08-TERMINAL-SHUTDOWN | TRN-1794387367702473 | Five shutdown regressions passed, including queued and active opens, cancelled shutdown, and a reentrant wake. Miri passed four seeds for the constructor/admission race. Independent PR and async reviews passed. | passed |
| REQ-TRN08-RUNTIME-TEARDOWN | TRN-1794387381526749 | The actual runtime-owner regression observed loopback EOF before teardown returned. The combined suites, Clippy, ci-gates, architecture and locked metadata checks passed. | passed |

## Observed validation

The combined gates ran on the exact code revision above. Later task documentation
commits do not change the tested Rust sources. Build-gate lane `rly14` and a
four-job limit were explicitly authorized by the user.

From `native/rust`:

```sh
cargo test -p ripdpi-relay-mux -p ripdpi-relay-tls-transports -p ripdpi-relay-core -p ripdpi-relay-android --locked --jobs 4 -- --test-threads=4
bash ../../scripts/ci/cargo-guarded.sh cargo clippy --workspace --all-targets --locked --jobs 4 -- -D warnings
cargo metadata --manifest-path Cargo.toml --locked --format-version 1
```

The suites passed 407 tests: Android 17, core 256, mux 89, and TLS transports 45.
No new regression was ignored. Full workspace Clippy passed with warnings denied.
Locked metadata resolved 113 workspace members and 935 packages.

From the checkout root, `just ci-gates` and
`python3 scripts/ci/check_architecture_health.py` passed. Architecture health had
18 baseline and 18 current findings, with zero new or worsened findings. No
baseline, lockfile, production dependency, or generated API snapshot was changed.
The runtime-platform API snapshot is Linux-owned and the gate skipped it on
Darwin. Other applicable API snapshots matched.

The following Miri command passed all four seeds on the combined candidate with
the same shutdown implementation. It used the separately authorized `default`
lane and disposable output:

```sh
MIRIFLAGS='-Zmiri-many-seeds=0..4' build-gate --lane default -- bash ../../scripts/ci/cargo-guarded.sh cargo +nightly miri test -p ripdpi-relay-mux --locked --jobs 4 --target-dir /tmp/ripdpi-trn08-miri-target tests::shutdown::shutdown_cancels_a_constructor_and_queued_lease_without_publishing_cache -- --exact
```

Local logs are `/tmp/trn08-affected-suites.log`,
`/tmp/trn08-workspace-clippy.log`, `/tmp/trn08-ci-gates.log`,
`/tmp/trn08-architecture.log`, `/tmp/trn08-metadata-full.json`, and
`/tmp/trn08-miri-shutdown.log`. Logs are local evidence and are not source files.

## Limits of the evidence

- Four existing tests remained ignored: the live Kotlin config export, Mieru
  upstream fixture, VLESS REALITY/XHTTP/XUDP upstream fixture, and Hysteria
  remote-hop fixture. Their runners are `scripts/tests/run-relay-config-contract.py`
  and `scripts/tests/run-outbound-interop.py`. These were not run by this repair.
- No physical-device, external-peer, root activation, or real-secret test ran.
  No external compatibility claim is made.
- Local execution establishes V only for the named tests. Static review is C;
  this internal pool repair does not establish wire-protocol S or external E.
- F1 reused the original audit's pre-fix loopback reproduction. The final
  late-open regressions were not rerun against the original revision.

## Rebase acceptance

Before integration, `origin/main` advanced to
`5e282528a0de7f9c0361be75e2c9f8935d4fee51` with unrelated AWG input repairs.
The job branch was rebased without conflicts. The original local code revision
`062db9e313301cd4a67d633a7691b4cefb55ef4a` maps to published code revision
`d3b4282cb48f28e1d319a430cd200be74868ae67`. A diff of `native/rust` between the
original local revision and the rebased branch was empty.

The complete combined test command, workspace Clippy command, and `just ci-gates`
were run again at `ad14dcdb2c8b70540535e40d6d9d3620d3be6cb8`. All passed:
407 tests passed, zero failed, and the same four existing tests were ignored.
The Darwin API snapshot limitation above still applies. `./taskctl validate`
also passed after the rebase and closure. The final documentation commit removes
an extra end-of-file blank line from the archived delta and records these
results; it does not change Rust sources.

Each confirmed defect has a separate commit after the rebase:

| Defect | Commit |
|---|---|
| Terminal cleanup owner retention | `743ed1116964c016179b32dbe768e89ea008a177` |
| Generation-specific idle accounting | `75cc1d0f1e4613b32efbc12e39b4cab78335d4cd` |
| Late cleanup cancellation ownership | `c287fe900ed48f138f175809cf850dd2e795fd5b` |
| Establishment admission bound | `1cec86388b9982b39e7cf678e16d05672f4e087b` |
| Independent XUDP isolation | `b2eaf7a1f31f66ce9b0470adf46b0fa4c2e6854f` |
| Terminal shutdown and racing opens | `d3b4282cb48f28e1d319a430cd200be74868ae67` |

Runtime-owner teardown needs no production fix. Its regression is in
`55ce1ce235291d794615a71b34de8fbe70409b34`.
