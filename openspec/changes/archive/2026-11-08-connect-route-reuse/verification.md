---
task_id: PRX-1794124642980782
change: connect-route-reuse
commit_sha: 8f73cfac55033e844986caebc4bf1f6028e00040
local: passed
local_evidence: 397 Rust tests passed, zero failed; three independent single-accept verified TLS cases passed. Clippy, fmt, locked metadata, ci-gates and independent review passed.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is not a local acceptance gate; push does not prove CI success.
device: not_applicable
device_evidence: No Android interface or lifecycle changes. Host loopback acceptance is required; physical devices are outside this task.
artifact: not_applicable
artifact_evidence: No release package is owned by this change.
deployment: not_applicable
deployment_evidence: No production deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-LOC05-REUSE | PRX-1794124707229962 | Two new required regressions failed before the fix; the incompatible-policy control passed. All three pass after the fix, including selected group metadata and normalized SNI guard accounting at cap 1. Existing upstream/SYN-data replacement and block tests also pass. | passed |
| REQ-LOC05-REUSE | PRX-1794124707782800 | Verified TLS 1.3 on one accepted TCP connection in rustls and independent Python/curl clients; exact payloads, complete affected suites, Clippy, fmt, locked metadata and ci-gates passed. | passed |
| REQ-LOC05-REUSE | PRX-1794124708337541 | Independent read-only reviewer approved the final four-file source diff and red/green evidence; current native documentation describes compatible reuse and mandatory replacement. | passed |

## Independent baseline

The frozen temporary `single_accept_tls.py` fixture failed against the original
runtime in all three cases: Python hostname CONNECT, Python IPv4 CONNECT with
SNI localhost, and curl HTTPS through the HTTP proxy. Each received CONNECT 200,
then a TLS EOF; each origin accepted exactly one connection, closed its listener
before TLS, and received no application bytes. Python required TLS 1.3,
certificate trust and hostname verification. curl used its normal TLS version
negotiation with certificate verification; its installed SecureTransport backend
does not support forcing TLS 1.3.

The baseline binary was built at `493c4f1749cbf78c38a268c9de38465f16fdf4f2`.
`git diff --exit-code` for `native/rust` against the starting main SHA
`993d6dc5dc062d1e21500c29b23c17c54eddb798` returned 0. Temporary source and
binary provenance, frozen fixture hash and baseline results are in
`/tmp/ripdpi-connect-route-reuse-acceptance`.

## Implementation and review

Source commit: `8f73cfac55033e844986caebc4bf1f6028e00040`
(`fix(proxy): retain compatible upstream after payload routing`).

The shared delayed path compares valid effective connection policies instead of
group indices and exact host text. It retains egress, preferred-target and
direct SYN-data gates. Reuse updates the selected route and payload. A changed
host releases its old same-SNI guard before acquiring the new one, so normalized
names at cap 1 remain counted. Block and owned-stack checks still run before
application bytes are sent.

The permanent `tcp_reuse_tests` module covers compatible group and discovered or
changed host transitions, selected group 0 versus provisional group 1, exact
socket identity, guard accounting, verified TLS 1.3 with one accept, and required
replacement for changed pre-connect receive buffer. The TLS fixture exchanges
close_notify and closes TCP in order; its final runtime result must be Ok.

The independent reviewer approved the final diff with no remaining findings.
No new async, unsafe, public API, dependency or baseline change was introduced.
The implementation worker was isolated from the primary documentation/lifecycle
worktree. The final job source is byte-identical to the reviewed source SHA.

## Exact commands and observed results

Cargo used locked dependencies and pinned Rust 1.98.1. Heavy authored commands
used `build-gate --lane ripdpi` with at most four jobs.

```sh
cargo test --locked --jobs 4 -p ripdpi-proxy-runtime \
  --lib --test network_e2e --test handshake_dns_cancellation \
  --test socks4_half_close --message-format=json -- --test-threads=1
cargo clippy --locked --jobs 4 -p ripdpi-proxy-runtime \
  --all-targets --no-deps -- -D warnings \
  -D clippy::await_holding_lock -D clippy::await_holding_refcell_ref \
  -D clippy::await_holding_invalid_type
cargo fmt --all --check
cargo metadata --locked --format-version 1
just ci-gates
```

- Unfiltered tests: runtime library 347, DNS cancellation 2, network E2E 39,
  SOCKS4 half-close 9; total **397 passed, 0 failed, 6 ignored, 0 filtered**.
- The six ignored tests are existing relay interoperability lane cases. They
  are not counted as passed. No new ignore or weakened assertion was added.
- All-target Clippy, formatting and locked metadata passed. Enabled commit
  hooks passed. Cargo manifests and lockfile are unchanged.
- `just ci-gates` passed architecture health, file/hotspot budgets, contracts,
  fixture pins, routing boundaries, locale/translation parity and API snapshots.
  The Linux-owned runtime-platform snapshot is explicitly skipped on Darwin.

The clean source SHA above was frozen for final tests and acceptance. Later
commits only add documentation and task lifecycle receipts.

## Independent fixed TLS acceptance

```sh
python3 /tmp/ripdpi-connect-route-reuse-acceptance/single_accept_tls.py --phase baseline --expect fail
python3 /tmp/ripdpi-connect-route-reuse-acceptance/build_harness.py --build-json /tmp/ripdpi-connect-route-reuse-runtime-logs/full-tests.log --deps-dir /Users/npochaev/.cache/cargo-target/debug/deps --checkout /Users/npochaev/GitHub/RIPDPI-worktrees/connect-route-reuse-runtime --source-sha 8f73cfac55033e844986caebc4bf1f6028e00040
python3 /tmp/ripdpi-connect-route-reuse-acceptance/single_accept_tls.py --phase fixed --expect pass
```

All commands returned 0, matching their explicit RED/GREEN expectations. The
fixture SHA-256 stayed
`86462297edf6de3a08d87a0a1aa58434af633e52f22111632049f7e253e2bb0f`.
The harness links exact Cargo JSON artifacts from the source SHA above and calls
the real public runtime; it does not replace parsing, routing or relay code.

- Python 3.11.14 hostname CONNECT plus SNI localhost: verified TLS 1.3 and exact
  binary echo `LOC05-ONE-CONNECTION\x00\xff\r\n`.
- Python IPv4 authority 127.0.0.1 plus SNI localhost: the same verified TLS 1.3
  and exact binary echo, covering a newly discovered host.
- curl 8.7.1 HTTPS through the HTTP proxy: certificate verification enabled,
  exact response `LOC05-ONE-ACCEPT-TLS-OK`; the server recorded TLS 1.3.
- Every origin called accept exactly once, closed its listener before TLS and
  served the first accepted socket. No retries or external endpoints were used.

## Evidence boundary and limits

C is the reviewed source SHA. V is the named host test and loopback runs. E is
the named installed Python/curl client compatibility on loopback. No additional
wire-specification or external-network conformance claim is made from this fix.
No Android/VPN, physical device, Linux socket-filter acceptance, external server,
production WSS end-to-end, release artifact, hosted CI or deployment was tested.

Different upstreams, destination lists or effective connect policies still
require a new connection. This is required route setup, not the compatible
group-only reconnection defect. The six existing ignored interoperability tests
remain outside the observed suite.

## Supplementary local evidence

- `/tmp/ripdpi-connect-route-reuse-runtime-logs`: red.log,
  green-final.log, commit.log, full-tests.log, clippy.log and fmt.log.
- `/tmp/ripdpi-connect-route-reuse-acceptance`: frozen client fixture,
  baseline-results.json, fixed-results.json, baseline-source.json,
  fixed-source.json and fixed-rustc-command.txt. Certificates are synthetic.
- `/tmp/ripdpi-connect-route-reuse-logs`: locked metadata and ci-gates.log.

The original LOC-05 audit and its historical verification remain unchanged.
The user authorized commit, main integration and push; the final handoff records
the observed remote SHA. Push success does not establish hosted CI success.
