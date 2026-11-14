---
task_id: RST-1794677660326601
change: dpi29-ordered-tcp-safety
commit_sha: 5f65d3c1fb340ca9d4a03d8c3a179cf695b400a8
local: passed
local_evidence: 284 host tests, 7 pure production-source tests, Android compile check, Clippy, ci-gates and independent reviews passed; see the limits below.
remote_ci: required
remote_ci_evidence: Hosted CI will be checked after the authorized publication; no remote pass is claimed.
device: required
device_evidence: Privileged device and raw-wire acceptance remain unverified; activation needs separate authorization.
artifact: not_applicable
artifact_evidence: No release artifact is produced.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-ORDERED-SIZE | RST-1794677880409389 | 22 host tests, 7 pure production-source tests, Android aarch64 --tests check, PR and raw-boundary review passed | passed |
| REQ-ORDERED-DISPATCH | RST-1794677885136148 | runtime activation/source-index matrix and production adapter peer test (14 custom variants, zero committed bytes) passed | passed |
| REQ-HOSTFAKE-DEFAULT | RST-1794677892236274 | four literal bytes/offset layouts and documentation review passed | passed |
| REQ-ORDERED-SIZE | RST-1794677898562549 | PR review, raw-boundary review, 284 host tests, Clippy and ci-gates passed | passed |

## Observed local checks

The validated source tree is the tree at `commit_sha`. The later verification
commit changes task evidence only. All Cargo commands used the pinned toolchain,
`--locked`, four build jobs, and a worktree-local target directory. Heavy commands
ran through `build-gate --lane ripdpi`.

| Check | Observed result | Local log |
|---|---|---|
| `cargo nextest run --locked --jobs 4 --no-fail-fast -p ripdpi-privileged-ops -p ripdpi-desync-runtime -p ripdpi-proxy-runtime-desync-adapter` | Exit 0; 284/284 passed, zero skipped, no retries; Darwin arm64, default profile | `/tmp/dpi29-combined-nextest-final.log` |
| `cargo test --locked --jobs 4 -p ripdpi-privileged-ops -p ripdpi-desync-runtime -p ripdpi-proxy-runtime-desync-adapter --doc` | Exit 0; these packages contain zero doctests | `/tmp/dpi29-combined-doctests.log` |
| `cargo clippy --locked --jobs 4 -p ripdpi-privileged-ops -p ripdpi-desync-runtime -p ripdpi-proxy-runtime-desync-adapter --all-targets -- -D warnings` | Exit 0; Cargo still reports existing unused workspace-dependency warnings | `/tmp/dpi29-combined-clippy.log` |
| Pure production-source packet-builder harness | Exit 0; 7/7 passed, including four ordered-batch regressions and three existing overlap tests | `/tmp/dpi29-f1-pure-tests-final.log` |
| `cargo check --locked --jobs 4 -p ripdpi-privileged-ops --tests --target aarch64-linux-android` | Exit 0; actual Android sender and test modules compiled with NDK 30.0.16248370, API 27 | Redirected log is no longer present; observed completion and Cargo test metadata remain |
| `just ci-gates` | Exit 0; source gates and applicable API snapshots passed | `/tmp/dpi29-ci-gates.log` |
| Final architecture, LoC and hotspot checks | Exit 0 each; no new, worsened or stale architecture indicators; no LoC baseline growth | `/tmp/dpi29-final-{architecture,loc,hotspots}.log` |
| Locked Cargo metadata | Exit 0; 113 workspace members | `/tmp/dpi29-cargo-metadata.json` |
| Task validation and commit hooks | Passed; 216 tasks and 784 execution steps validated | Commit hook output and taskctl output |

The packet-builder harness compiled the unchanged production packet builder,
types and test modules with explicit snapshot inputs. It did not emulate a
successful privileged syscall. Literal packet bytes, sequence wraparound,
checksums and MSS/MTU boundaries were checked. The unsupported adapter test used
a real loopback TCP peer for all 14 non-default FakeSplit/FakeDisorder variants
and observed zero committed bytes.

The new dispatch regression failed before the production fix. Its expected
special-execution decision was true; the old implementation returned false.
The first combined nextest invocation stopped before running tests because an
explicit test-thread option duplicated the local wrapper option. The final
command above removed that duplicate; no test was skipped to obtain a pass.

Independent PR review and raw-boundary review reported no remaining findings,
including the final MSS calculation for extra fake TCP options. Seven reviewed
Rust file hashes matched the tested tree before commit. No dependency, lockfile,
baseline, locale or generated native artifact changed.

## Evidence limits

- C: the three confirmed findings are fixed and have regression coverage.
- V: host behavior, pure packet serialization and Android compilation were
  observed. Linux/Android privileged raw emission was not executed.
- S: the MSS calculation was checked against Linux v6.12 effective SND_MSS
  handling and RFC 6691 section 2. This does not establish full TCP conformance.
- E: no external-server or physical-device compatibility result is claimed.
- The Linux-owned runtime-platform API snapshot is not run on Darwin by the
  existing gate. No Linux snapshot pass is claimed.
- Oversized or unknown-limit exact ordered batches return Unsupported before
  emission. Automatic segmentation is outside this repair.
- Privileged raw-wire acceptance, root activation, physical-device tests, Miri
  and external network probes were not run. Root/device activation requires
  separate explicit authorization.
- Hosted CI must be inspected after publication. The task remains in review;
  local evidence does not satisfy the remote or device gates.

## Rebased integration evidence

The four commits were rebased without conflicts onto `origin/main`
`4c64d9e8c9`. Upstream DPI-31 and DPI-33 changes were reviewed for their effect
on these repairs. Independent integration review found no new issue.

The same nextest, doctest and Clippy commands above were repeated on clean
commit `5f65d3c1fb340ca9d4a03d8c3a179cf695b400a8`. All returned exit 0:
284 host tests passed, zero skipped; doctest suites contained zero tests;
Clippy passed with `-D warnings`. Commands, exit codes and package counts are
recorded in `/tmp/dpi29-rebased-results.json`; logs are
`/tmp/dpi29-rebased-{nextest,doctests,clippy}.log`.
`just ci-gates` also passed on this rebased tree; its log is
`/tmp/dpi29-rebased-ci-gates.log`.

After all these checks finished, the temporary worktree directory was removed
by an external process. No task command requested its deletion. The commits
and logs survived. The same commit was checked out in the persistent
`RIPDPI-worktrees/dpi29-publication-20261114` worktree for publication.
This evidence update does not change production or test sources.
