---
task_id: RST-1794498457070491
change: dpi06-audit-repairs
commit_sha: 3423cf4383e736801e6d57713ce143b0fe346d72
local: passed
local_evidence: Final host suites 284 passed; Linux final clippy and 7 selected regressions passed; source gates passed. The broader native Linux suite had 80 passes and 3 unrelated BPF EPERM failures, recorded below as a coverage limit.
remote_ci: not_applicable
remote_ci_evidence: No hosted workflow run is required by this local repair; report any push-triggered CI separately.
device: not_applicable
device_evidence: No Android integration contract changes; root and device activation are outside authorization and no wire-effectiveness claim is made.
artifact: passed
artifact_evidence: Canonical Linux API snapshot generated with locked rustdoc and normalized by the owning script; 113 lines, SHA256 189b36c4cc2fcaefd85f70b84a331c432a28c4762b086b95b2b6aa39705e6cc5. Independent general and unsafe reviews completed without open findings.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI06-PAGE-RESTORE | RST-1794498720745002 | Linux retained pipe bytes restore REAL after failed enqueue/wait; bounded helper and adapter progress tests; real peer bytes and committed count test | passed |
| REQ-DPI06-FIRST-TTL | RST-1794498721479845 | fake_disorder_preserves_first_original_transport_without_requiring_raw; Linux raw-option merge and packet-count tests | passed |
| REQ-DPI06-TTL-ROLLBACK | RST-1794498722231678 | fake_disorder_boundary_failures_restore_socket_ttl checks actual loopback socket state after write and wait failures | passed |
| REQ-DPI06-RECEIPT | RST-1794498723401160 | fake_disorder_receipt_matches_boundary_regions_and_sender_waits checks ten boundary and branch cases | passed |
| REQ-DPI06-SECONDARY | RST-1794498724282075 | Literal byte, TTL and sequence vectors for four orders and two sequence modes; Linux selected-modifier failures do not silently use stream transport | passed |
| REQ-DPI06-SEQ-WRAP | RST-1794498725723919 | Literal wrap boundary and invalid-length regressions in Linux tcp_repair tests | passed |
| REQ-DPI06-DOCS | RST-1794498726821060 | Current transport owners checked against three native documents and descriptive primitive-probe text | passed |

## Finding commits

| Finding | Commit | Result |
|---|---|---|
| F1 | 0471ad4f6dd7b1ab30b06343555025a8a2044dd0 | Restore retained bytes before socket cleanup; retain the first error and known send progress through the helper and runtime adapter. |
| F2 | e323550d8eb8ac19055c8fdf50631330c7e77230 | Keep first-real TTL and original-transport flags when existing options select raw sending. |
| F3 | 1d210d7b0c053d4c3d6dbba3d22e39e47316eaea | Restore saved TTL after boundary write or wait failure. |
| F4 | c5be6f678172233cab93035b69c5ff678d6298ca | Record the effective fallback family and actual completed writes and waits. |
| F5 transport | 04b7b364bc6153311cb57e9eee606f97a21aa254 | Return selected secondary/timestamp raw-send errors instead of discarding the selected option. |
| F5 runtime | 3423cf4383e736801e6d57713ce143b0fe346d72 | Keep primary and secondary fake regions in supported orders and sequence modes. |
| F6 | 2aec1e53b03f7f3bbf26e3ee1ba4307488ca2fac | Add valid TCP sequence offsets modulo 2^32 and retain the length guard. |
| F7 | 20d46dab6d5386fafeb7435a336f4d5159874c3b | Distinguish stream, selected raw, ordered raw and MultiDisorder; limit primitive-probe claims. |

## Observed checks

Application tests and Clippy used pinned Rust 1.98.1 and `--locked`.
The owning API generator used its configured nightly toolchain and locked rustdoc.
No test, hook, baseline, or permission gate was removed or weakened.

From `native/rust/` on the final code commit:

```sh
CARGO_TARGET_DIR=/tmp/ripdpi-dpi06-target-docs CARGO_PROFILE_DEV_DEBUG=0 CARGO_INCREMENTAL=0 CARGO_BUILD_JOBS=2 cargo test --locked -p ripdpi-desync-runtime -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-runtime-platform -p ripdpi-privileged-ops -p ripdpi-root-helper -- --test-threads=4
```

Exit 0; 284 passed, 0 failed, 0 ignored: desync-runtime 153, privileged-ops host 6,
adapter unit 35, adapter primitive integration 10, root-helper 66, platform host 14.
Zero-test doc tests are not included in this count.
Log: `/tmp/ripdpi-dpi06-host-suites-final.log`.

From the repository root, `just ci-gates` with isolated target
`/tmp/ripdpi-dpi06-target-lead`, debug information disabled, incremental disabled,
and two Cargo build jobs: exit 0 on the final code commit.
Log: `/tmp/ripdpi-dpi06-ci-gates-committed.log`.
This includes architecture, file size and hotspot budgets, contracts, locales,
exports and host-owned API checks. The host correctly skips the Linux-owned
platform snapshot; its canonical Linux generation is separate evidence.

Integration checks `python3 scripts/ci/check_architecture_health.py` and
`cargo metadata --locked --format-version 1` also returned 0.
Logs: `/tmp/ripdpi-dpi06-integration-architecture.log` and
`/tmp/ripdpi-dpi06-integration-metadata.json`.

The Linux runner used image
`rust@sha256:93ce27a88655056a51dbdd8f5f2d7ddc071c7b0070fb288a37b5a285fc83971e`,
the caller's nonroot UID, `--cap-drop ALL`, `no-new-privileges`, a read-only
source mount and `--network none`. Public tool downloads were separate setup.
Its final command was:

```sh
cargo clippy --locked -p ripdpi-privileged-ops -p ripdpi-runtime-platform -p ripdpi-desync-runtime -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-root-helper --no-deps --all-targets -- -D warnings
```

Exit 0 on the final code commit. Four subsequent test commands also returned 0:

```sh
cargo test --locked -p ripdpi-runtime-platform root_helper_client::transport::progress_tests -- --nocapture
cargo test --locked -p ripdpi-proxy-runtime-desync-adapter fake_tcp_progress -- --nocapture
cargo test --locked -p ripdpi-desync-runtime secondary_payload_bytes -- --nocapture
cargo test --locked -p ripdpi-desync-runtime strategy_error_keeps_known_committed_bytes -- --nocapture
```

Selected results: 3 + 2 + 1 + 1 passed; 0 failed or ignored. The adapter's separate
integration binary selected zero tests and is not counted. All 5125 tracked Rust
file hashes matched before and after. Manifest SHA256:
`27c0b181707bf887978f02f85db61963d7e0f66469ca2c2f17dc35c21427c8d3`.

Linux logs and exact runner commands are in
`/var/folders/z7/0w5jl61d0qq811sptzvn99n5ww3hp6/T/dpi06-linux-verification-z4xwxuvi/`:
`linux-clippy-final-source.log`, `final-regression-commands.json`,
`final-helper-progress.log`, `final-adapter-progress.log`,
`final-secondary-payload.log`, `final-strategy-error.log`.
The prior full owning platform run passed 49 tests with no failures or ignores;
`runtime-platform-suite.log` records that run. Its tested source bytes did not change.

## Native Linux result and limits

The full `cargo test --locked -p ripdpi-privileged-ops -- --nocapture` run had
80 passes, 3 failures and no ignores. All seven new native regressions passed.
The three failures were unchanged BPF attach/detach tests with `EPERM` under
the explicit capability drop:

- `bpf_drop_sack_filter_attaches_and_detaches_cleanly`;
- `bpf_filter_detach_removes_program`;
- `bpf_strip_timestamps_filter_attaches_and_detaches_cleanly`.

This full native suite is not reported as green. Its log is
`/var/folders/z7/0w5jl61d0qq811sptzvn99n5ww3hp6/T/ripdpi-dpi06-linux-ba4rpktj/green-child-suite.log`.
The same directory retains `run-linux.sh` and the failing pre-fix F1/F5/F6 logs.
No root activation or capability grant was used to hide these failures.

Initial lint runs found three warnings in new code/tests. They were corrected
without exemptions. An intermediate partially-staged hook run produced E0308
while enum and callers were temporarily from different steps. That run is not
final evidence. Each repair commit subsequently passed normal hooks with a
consistent source tree; final code bytes matched the saved reviewed tree.

## Review and evidence boundary

Independent general review and unsafe review ended with no open findings.
The only new unsafe operation is test-only capability removal in a child test
process; the reviewer checked its ABI, live buffers and drop-only masks.
Miri was not run: it cannot establish Linux retained-pipe or capability behavior.

C covers the repaired owners, callers and contracts. V covers the observed
host and nonroot Linux checks above. The literal layout and pipe-byte tests
prove their narrow contracts; neither a call-recording fake nor a plan proves
the complete emitter or a DPI effect. S is not promoted to complete TCP/IP or
upstream conformance. The sequence-wrap check addresses the arithmetic rule
in RFC 9293 section 3.4. E remains unverified.

The following checks require separate authorization or a suitable environment:

- Run the three BPF tests with the required kernel support and privileges.
- Capture a repaired full raw path and independently check TTL/Hop Limit,
  order, both profiles, sequence offsets and genuine peer-stream bytes for
  each supported order, sequence mode, boundary, round and activation gate.
- Check Android root-helper integration, backpressure, cancellation/close and
  retransmission behavior on an explicitly authorized device.
- Check concrete external-peer and DPI compatibility. No external target,
  physical device, real secret, root activation, release or deployment was used.

The earlier audit's hypotheses remain hypotheses. These repairs address its
seven confirmed findings within the existing approximation scope.
