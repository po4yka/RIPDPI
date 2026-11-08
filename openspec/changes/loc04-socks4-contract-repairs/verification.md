---
task_id: PRX-1794116241356560
change: loc04-socks4-contract-repairs
commit_sha: 8a03d855834009059d2f61d0fe18d038c8ab604f
local: passed
local_evidence: 656 tests passed with zero failures; 14 pre-existing tests remain ignored. Workspace all-targets clippy, format, ci-gates, architecture health, locked metadata, and independent general/API reviews passed.
remote_ci: not_applicable
remote_ci_evidence: No hosted CI success is claimed or required for this local repair. Main integration and push are recorded separately below.
device: not_applicable
device_evidence: No JNI, Android service, or protect IPC contract was changed. The required evidence is from host loopback tests; Android execution is unverified.
artifact: not_applicable
artifact_evidence: No signed or packaged binary delivery was requested.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

The exact source tree is commit `8a03d855834009059d2f61d0fe18d038c8ab604f`. Later lifecycle commits change documentation only.

## Requirement evidence

| Requirement | Execution step | Observed evidence | Result |
|---|---|---|---|
| REQ-S4-CONNECT | PRX-1794116315304352 | Delayed SOCKS4/4a refusal, socket reuse, prepared WS and normal-route fallback tests; commands below | Passed |
| REQ-S4-FIN | PRX-1794116317212493 | socks4_half_close, delayed empty FIN, and WS normal-fallback origin FIN tests; commands below | Passed |
| REQ-S4-FAMILY | PRX-1794116318838623 | Session final-family matrix and real IPv6-origin rejection test; commands below | Passed |
| REQ-S4-CANCEL | PRX-1794116320267158 | handshake_dns_cancellation, existing UDP cancellation, WS DNS cancellation, and stalled TLS/upgrade tests; commands below | Passed |
| REQ-S4-PARSE | PRX-1794116322076440 | Session version/NUL/sentinel matrix and socks4_boundary reader test; commands below | Passed |
| REQ-S4-LIMIT | PRX-1794116324708518 | socks4_limits with real origin, literal failure replies, combined domain budget, EOF and deadline; commands below | Passed |
| REQ-S4-COMPAT | PRX-1794116326351813 | Full affected Rust suites, workspace all-targets clippy, source gates and independent reviews; commands below | Passed |

## Commands and results

Commands ran with Rust 1.98.1 from `native/rust` unless a repository script is named.
Compiler-backed commands used the reserved RIPDPI build lane, four Cargo jobs,
and `CMAKE_BUILD_PARALLEL_LEVEL=4`.

| Command | Result |
|---|---|
| `cargo test --locked --jobs 4 -p ripdpi-session -p ripdpi-proxy-runtime -p ripdpi-ws-tunnel -p ripdpi-ws-transport-port` | 656 passed, 0 failed, 14 pre-existing ignored; 23 suite reports |
| `cargo clippy --workspace --all-targets --no-deps --jobs 4 --locked -- -D warnings` | Passed |
| `cargo fmt --all -- --check` | Passed |
| `python3 scripts/ci/check_rust_api_snapshots.py --update` | Only the three new prepared-WS API lines changed |
| `just ci-gates` | Passed all source gates, including API snapshots |
| `python3 scripts/ci/check_architecture_health.py` | Passed |
| `cargo metadata --locked --format-version 1` | Passed; lockfile unchanged |

Every fix has observed failing behavior before the root-cause change. The F1/F2
combined origin-FIN case also failed before its shared-reader correction.
General review approved each fix before commit. A separate Rust API audit approved
prepared-session ownership, the borrowed cancellation callback, and cleanup.
All required pre-commit hooks passed without skips or bypass flags.

The final checks were repeated after rebasing onto origin/main `fd4d0338542957fd2df7c510be41e82396cdb804`.
Parallel token-authentication and shared-listener repairs are preserved. F4 was
already integrated by the shared-listener task, so rebase removed the duplicate patch.

## Fix commits

| Finding | Commit |
|---|---|
| F1 | `8a03d855834009059d2f61d0fe18d038c8ab604f` |
| F2 | `bd99f2d523236cb05605e6e2492b08f304b96337` |
| F3 | `3c5673f4d3ab2921063fdac4846bad7aa4cbe20d` |
| F4 | `9af11c6bbdccd3ee9ef4e86a579a7193153d01be` |
| F5 | `f802c3610d400ff9e0b577ca9e8ba2bf1da3447a` |
| F6 | `62ee445f770ae5b153a5cfefd7ab4d04d2a741ae` |

## Scope and residual evidence

C: the repaired code and callers were checked. V: the named host parser, handler,
and full-listener tests ran. S: the tested SOCKS4/4a framing, response ordering,
and directional TCP close requirements passed; this is not full protocol certification.
E: no external-provider or device interoperability is claimed for this tree.

CONNECT-only behavior, token rejection, domain limits, and request storage bounds remain.
These LOC-04 commits do not change dependencies, baselines, locale resources,
schemas, or the network registry. Parallel main changes remain intact.
The snapshot check intentionally leaves the Linux-owned runtime-platform snapshot to Linux.

The existing Android protect IPC has a Unix connect without an explicit deadline,
then one-second read/write timeouts. The new bootstrap checks cancellation after
that IPC returns and before outbound TCP connect. This change does not establish a
strict IPC wall-clock bound or Android device acceptance.

## Integration

Main integration and push are pending. Do not close the portfolio task until the
requested repair commits are observed in origin/main.
