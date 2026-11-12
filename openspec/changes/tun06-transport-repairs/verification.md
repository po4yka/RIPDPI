---
task_id: TRN-1794494451740063
change: tun06-transport-repairs
commit_sha: 6af0911c786592aec9a08ed451db7c163e9813eb
local: passed
local_evidence: All eight repairs passed independent review. Combined Rust tests passed 711 cases with 14 existing ignored; Kotlin runtime-state passed 418 cases, ktlint and detekt; Node 5 and real workerd 6 tests passed; just ci-gates and locked metadata passed.
remote_ci: not_applicable
remote_ci_evidence: This repair requires local gates before the authorized main push; no hosted run is claimed.
device: not_applicable
device_evidence: No device or external server acceptance is authorized; local contract tests cover this repair.
artifact: not_applicable
artifact_evidence: No APK or deployed Worker artifact is delivered by this source repair.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TRN-1794494451740063-F1 | TRN-1794494656917882 | Real workerd binary roundtrip red 1003 before repair; 6/6 current runtime tests and Node 5/5; independent review passed | passed |
| REQ-TRN-1794494451740063-F2 | TRN-1794494657639003 | OpenSSL-generated DC collision vectors; runtime ws_tunnel suite passed; independent review passed | passed |
| REQ-TRN-1794494451740063-F3 | TRN-1794494658746565 | Real workerd canonical production/test allowlist red before repair; 6/6 current runtime tests; independent review passed | passed |
| REQ-TRN-1794494451740063-F4 | TRN-1794494659472704 | Preserved partial seed regression red before repair; runtime ws_tunnel 20/20; independent review and file LoC gate passed | passed |
| REQ-TRN-1794494451740063-F5 | TRN-1794494660270476 | Real SOCKS5 DNS/TLS stop red before repair; four-crate suite 711 passed with 14 existing ignored; final prepared-stop hunk 22 affected tests passed; independent review passed | passed |
| REQ-TRN-1794494451740063-F6 | TRN-1794494660986335 | Four cold-read/cancellation regressions red before repair; runtime-state 418/418, ktlint and detekt passed; independent review passed | passed |
| REQ-TRN-1794494451740063-F7 | TRN-1794494661730257 | Independent OpenSSL vector red before repair; ws-tunnel 131 unit, 10 handshake-contract, 1 logging passed; independent review passed | passed |
| REQ-TRN-1794494451740063-F8 | TRN-1794495319927290 | Real workerd empty Close red with InvalidAccessError 1005 before repair; bidirectional empty/normal Close passed in 6/6 runtime tests; independent review passed | passed |


## Validated revision and scope

The combined source was rebased onto `922168f5042d2466e151b10236c8c6b0d52eb9ff`.
The exact validated commit is recorded above. Later evidence-only commits do not
change the tested application, Worker, or test source. The user authorized one
fix commit per finding, integration into main, and push. No Worker deployment,
physical-device probe, root activation, or real credential was used.

| Finding | Fix commit on the integration branch |
|---|---|
| F1: current Worker binary delivery | `80b8e8ed59` |
| F2: exact direct destination DC | `4ad8c9d64a` |
| F3: canonical Worker test-DC paths | `f9eaea2fe7` |
| F4: complete preserved partial init | `2d624f0a1a` |
| F5: cancel every runtime bootstrap | `f541c96716` |
| F6: durable credential publication | `dc0dd513cb` |
| F7: reject the zero second word | `44391ead7e` |
| F8: preserve valid Worker close frames | `7b3349c77c` |

F8 was confirmed during repair validation of the audit's lifetime hypothesis.
Before the fix, a real peer Close without status caused `InvalidAccessError`
for reserved observation code 1005; the other peer observed 1011. The repaired
relay preserves an empty Close in both directions. Code 1006 maps to 1011 with
a fixed reason; ordinary status and reason remain unchanged.

## Commands and observed results

All commands ran locally. Cargo tests ran from `native/rust`.

| Command | Observed result |
|---|---|
| `just checkout-doctor` in each owned worktree | Ready after normal setup; unrelated unavailable worktree was preserved |
| `cargo test -p ripdpi-ws-tunnel -p ripdpi-ws-bootstrap -p ripdpi-ws-transport-port -p ripdpi-proxy-runtime --locked` | Final combined run: 711 passed, 0 failed, 14 existing ignored across 32 suites, including doctests |
| `cargo test -p ripdpi-proxy-runtime --locked --lib ws_tunnel` | 22 passed after the final prepared-carrier shutdown guard; also covered by the final combined run |
| `cargo clippy --locked -p <affected-crate> --no-deps --all-targets -- -D warnings` | Passed in code-commit hooks |
| `./gradlew :core:data:runtime-state:testDebugUnitTest :core:data:runtime-state:ktlintCheck :core:data:runtime-state:detekt --max-workers=4 -Pripdpi.nativeCpuBudget=4` | 418 tests passed, no failures/errors/skips; ktlint and detekt passed |
| `node --test docs/native/cloudflare-workers/relay.test.mjs` | 5 passed |
| `npx --yes workerd@1.20260515.1 test docs/native/cloudflare-workers/relay.workerd.capnp` | 6 passed, real runtime with compatibility date 2026-05-15 and local upstream services only |
| `node --check docs/native/cloudflare-workers/relay.js` | Passed |
| `actionlint .github/workflows/telegram-worker.yml` | Passed; the workflow runs both Worker suites |
| `just ci-gates` | Passed: architecture, file/hotspot budgets, cross-language and fixture contracts, diagnostics boundary, route/locale/translation checks, and Rust API snapshots |
| `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1 --no-deps` | Passed |
| `git diff --check` and `./taskctl validate` | Passed |

The final heavy commands used the repository `build-gate --lane ripdpi` queue.
The source-gate invocation used a temporary PATH adapter to add `--locked` to
Cargo metadata/build commands issued by helper scripts. The public-api plugin
has no locked option. No dependency or lockfile changed. The Linux-owned runtime
platform snapshot was skipped by the existing Darwin gate; other snapshots
matched without regeneration. Architecture reported zero new/worsened entries;
file budgets reported zero violations, exemptions, or baseline growth.

The first combined Rust run failed in the unchanged
`failed_first_response_replacement_releases_both_ip_permits` fixture before
its behavior assertions: the IPv4 bind reused an IPv6-selected port and returned
`AddrInUse`. A full retry with identical parameters passed. No test was disabled,
serialized, or weakened. The first result remains part of the evidence.

Local logs: `/tmp/tun06-ci-gates.log`, `/tmp/tun06-combined-rust.log`,
`/tmp/tun06-combined-rust-retry.log`, `/tmp/tun06-f6-red.log`,
`/tmp/tun06-f6-green.log`, `/tmp/tun06-worker-f8-red.log`,
`/tmp/tun06-worker-f8-green.log`, `/tmp/tun06-f5-red.log`, and
`/tmp/tun06-f5-commit.log`. These are local observations, not hosted CI artifacts.

## Evidence boundaries and remaining checks

- C: all eight repair paths and their actual callers were reviewed. Separate
  read-only reviewers approved Worker, Rust, and credential changes. No open
  actionable finding remains within these eight repairs.
- V: binary Worker exchange and Close events ran in real local workerd.
  OpenSSL-generated fixed init vectors provide an independent crypto input.
  SOCKS5 DNS/TLS shutdown tests use the production transport and real loopback
  sockets. Existing connector tests cover cancellation at HTTP Upgrade.
- Preserved/reopen cancellation is covered by the shared production path and
  targeted controls. Separate end-to-end TLS fixtures for each caller were not
  run. No claim of full lifetime/error-race coverage is made.
- F8 code 1006 lacks an isolated deterministic abrupt-disconnect runtime test.
  Empty status 1005 and ordinary Close are covered in both directions. A local
  raw-socket abrupt peer is the remaining optional acceptance check.
- Credential tests use fsynced temporary storage and the real credential IO
  adapter. They do not prove AndroidKeyStore or actual LMK behavior. A process
  kill can leave an inactive encrypted entry; the active settings/secret pair
  remains coherent by publication order. Device acceptance should restart after
  each durable stage and resolve the active reference.
- S: the audit's checked specification clauses remain the source for protocol
  requirements. Local green tests do not upgrade specification coverage.
- E: external Telegram/Cloudflare interoperability, test-DC 4/5 availability,
  deployed Worker compatibility, and Android VPN stop remain unverified. They
  require separately authorized acceptance; none was inferred from local tests.
- The audit's other hypotheses (all Worker error races, late downlink after TCP
  half-close, gateway packet-boundary behavior, and wider IP coverage) remain
  separate from confirmed defects. No additional transport mode was added.
