---
task_id: PRX-1794074402219640
change: resolve-socks5-connect-hypotheses
commit_sha: b278654715a2401457d5e9ea01efd1cc821ebdfe
local: passed
local_evidence: 445 tests passed on explicit Rust 1.98.1, independent review passed, ci-gates and architecture/locked metadata passed.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is not claimed; required local gates run before main integration.
device: not_applicable
device_evidence: Local protocol and socket-lifecycle proof only; no physical device acceptance claim.
artifact: not_applicable
artifact_evidence: No shipped binary or golden artifact is updated.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-S5-STOP | PRX-1794074650961589 | local late-auth and reply cancellation wire probes | passed |
| REQ-S5-TIMEOUT | PRX-1794074651536325 | independent error-to-wire reply test and mapper test | passed |
| REQ-S5-UPSTREAM-REP | PRX-1794074652008503 | upstream REP01..08 loopback wire matrix | passed |
| REQ-S5-ABSOLUTE-NAME | PRX-1794074652460822 | a. and abc.test. through local DoH and TCP origin | passed |

## Hypothesis investigation

Execution step PRX-1794074653029150 records all four audit hypotheses and exact local limitations. H1 upstream auth/reply sends CONNECT after terminal stop; controlled DNS closes before return, and an unprivileged macOS backlog cannot reproduce pending SYN. H2 chained BND is a contract choice, not a confirmed defect. H3 has false TTL classification and independently observed upstream REP loss. H4 absolute FQDN support is broken at two checks; localhost family fallback is not promised and stays unchanged.

## Exact-source local validation

Source commit: `b278654715a2401457d5e9ea01efd1cc821ebdfe`. The final commands ran with explicit `RUSTUP_TOOLCHAIN=1.98.1` and disposable Cargo outputs. A first root-directory run used system Rust1.97.0 because the project pin is under native/rust; it passed but was repeated on the required pin.

```sh
RUSTUP_TOOLCHAIN=1.98.1 cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-proxy-runtime -p ripdpi-session -p ripdpi-ws-bootstrap --locked --lib
RUSTUP_TOOLCHAIN=1.98.1 cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-proxy-runtime --locked --test network_e2e --test upstream_socks_shutdown -- --include-ignored --test-threads=1
RUSTUP_TOOLCHAIN=1.98.1 just ci-gates
RUSTUP_TOOLCHAIN=1.98.1 python3 scripts/ci/check_architecture_health.py
RUSTUP_TOOLCHAIN=1.98.1 cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1
```

Observed results: 304 runtime, 74 session, 21 ws-bootstrap, 45 network E2E, and one upstream-shutdown integration test passed. Total445, no ignored or failed tests. The shutdown integration test covers auth stall, reply stall, and real drip reply. Independent upstream wire matrix covers13 cases. All source gates and API snapshots passed; architecture health has zero worsened and zero stale baseline entries. All four separate source commits passed normal hooks and independent read-only review.

## Hypothesis outcomes

- H1 confirmed: controlled late auth allowed a CONNECT after terminal runtime return. The fix tracks the upstream socket throughout negotiation and rejects stages after stop. RED remained open after return; GREEN peer EOF occurs before return. A separate controlled DNS stall closed its connection before return and left no observed TCP fd tail.
- H2 not confirmed as a defect: real three-leg BND fixture returned the first-hop BND and delivered exact payload. The reviewed RFC/owned caller contract does not require ultimate upstream BND propagation. No speculative behavior change.
- H3 confirmed: a plain deadline was misreported as TTL expiry; remote failure codes01..08 were all collapsed into05. Separate commits fix these errors. Explicit valid upstream06 still passes through; generic deadlines and invalid frames return01.
- H4 confirmed: absolute names a. and abc.test. failed before DNS. The parser and DNS builder fixes pass independent local DoH/TCP tests. Existing localhost IPv6 preference/family behavior remains; no fallback promise was found.

## Limits and independent Linux evidence

An offline, unprivileged cached Linux container produced a real pending SYN on an owned loopback listener. Python TCP_INFO showed SYN_SENT and SO_ERROR0. An independently compiled Rust std connect_timeout(250ms) returned TimedOut three times at255/258/256ms. This small probe used installed Rust1.97.0, not the project1.98.1 pin. It is kernel/std evidence only, not full RIPDPI Linux wire or stop-during-SYN acceptance. No images or packages were downloaded; owned probe containers were removed. Full Linux/Android runtime cancellation during SYN, physical-device acceptance, external compatibility and hosted CI were not run or claimed. Upstream per-I/O timeout policy was not redesigned into a new total-handshake budget.
