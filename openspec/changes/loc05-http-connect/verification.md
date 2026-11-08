---
task_id: PRX-1794118814905211
change: loc05-http-connect
commit_sha: 493c4f1749cbf78c38a268c9de38465f16fdf4f2
local: passed
local_evidence: 682 affected Rust tests passed; Clippy, fmt, locked metadata and ci-gates passed. Independent review approved all fixes and reconciled source. Thirty-five independent loopback observations passed with Python and curl.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI was not requested as local acceptance and was not observed. A remote push does not prove CI success.
device: not_applicable
device_evidence: No Android interface or service lifecycle change. Host loopback checks cover the changed Rust boundaries; physical acceptance was not authorized or run.
artifact: not_applicable
artifact_evidence: No release package or Android native artifact is published by this change.
deployment: not_applicable
deployment_evidence: No production deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-LOC05-ORDER | PRX-1794118937259707 | Five real socket regressions cover refused upstream, no-seed WS failure, server-first exchange, socket reuse and a wire readiness barrier before 200. Independent delayed TLS 1.3 echo passed. | passed |
| REQ-LOC05-FRAMING | PRX-1794118934771703 | Shared-head and full-parser regressions reject invalid/nonzero/conflicting CL and TE before auth or resolution; wire failures return 400. CL0 control passes. | passed |
| REQ-LOC05-FIELDS | PRX-1794118935386499 | Field name/value, CRLF, control, folding and duplicate authorization regressions pass; independent malformed requests get 400. | passed |
| REQ-LOC05-HTTP10 | PRX-1794118935968965 | Exact target regression plus installed Python 3.11.14 HTTPSConnection default HTTP/1.0 tunnel and verified TLS echo pass. HTTP/1.1 still requires Host. | passed |
| REQ-LOC05-OCTETS | PRX-1794118934207394 | Legal unknown field bytes 80/FF pass with correct Basic; wrong/missing Basic returns 407 and tunnel credentials do not count. Shared Head API is unchanged. | passed |
| REQ-LOC05-PORT | PRX-1794118936564724 | Signed and nondecimal ports reject before conversion. Independent ports 1/443/65535, leading zeros, bounds and bracketed IPv6 regressions pass. | passed |
| REQ-LOC05-LIMIT | PRX-1794118938174073 | Upstream header-limit fix is retained; wire lengths 65535/65536 succeed with early bytes, 65537 returns full 431 then closes without upstream use. | passed |

## Atomic fixes

| Finding | Commit in the integration branch | Outcome |
|---|---|---|
| F01 | 21fcbb9f5 | Establish upstream before HTTP success; reuse current provisional TCP and prepared WS contracts. |
| F02 | 188a9b8a5 | Reject CONNECT content framing. |
| F03 | 6d02c24f5 | Validate complete field grammar and unambiguous Basic fields. |
| F04 | e60f55be6 | Accept HTTP/1.0 without Host. |
| F05 | 45b074e11 | Parse legal field values as octets. |
| F06 | 15e9fdd83 | Require decimal authority ports. |
| F07 | b88184378 | Already merged by the shared-listener repair. Retain its 431 response and FIN handling. |

F07 was first reproduced and fixed independently in the job worktree. The main branch received an equivalent fix before integration. The duplicate private fix was removed during the job rebase; its committed tests and current upstream implementation were verified again on the combined tree. Six new source fixes remain, each in its own commit.

## Combined commands and observed results

All Cargo commands ran with locked dependencies and pinned Rust 1.98.1. Heavy commands used build-gate --lane ripdpi, worktree outputs, sccache and at most four jobs. The source above was clean and unchanged during all final checks.

- `cargo test --locked --jobs 4 -p ripdpi-session -p ripdpi-proxy-runtime-adapter -p ripdpi-proxy-runtime -p ripdpi-ws-transport-port -p ripdpi-ws-tunnel --lib -- --test-threads=1`: 632 passed (runtime344, adapter74, session88, WS port19, WS tunnel107), zero failed; one existing manual throughput benchmark ignored.
- `cargo test --locked --jobs 4 -p ripdpi-proxy-runtime --test network_e2e --test handshake_dns_cancellation --test socks4_half_close -- --test-threads=1`: 50 passed (39 network, 2 DNS cancellation, 9 half-close), zero failed; six existing network tests for the separate relay interoperability lane ignored. Those six are not counted as passed.
- Total Rust result: **682 passed, 0 failed, 7 ignored, 0 filtered**. No new ignores, focused tests, placeholder implementations or baseline extensions were added.
- All-target Clippy for the same five packages, with `-D warnings`, `await_holding_lock`, `await_holding_refcell_ref` and `await_holding_invalid_type`: passed.
- `cargo fmt --all --check`: passed.
- `cargo metadata --locked --format-version 1`: passed; full metadata saved locally. Cargo.lock is unchanged from upstream.
- `just ci-gates`: passed. This includes architecture health with --check, file/hotspot limits, runtime contracts, protocol fixture pins, diagnostics and route boundaries, locale/translation parity and Rust API snapshots. The script explicitly skips the Linux-owned runtime-platform snapshot on Darwin; no Linux snapshot pass is claimed.
- Independent final Rust reviewer approved every original slice and each reconciled parser/runtime diff before acceptance. No new async, unsafe, JNI or published Rust API was introduced by the final delta.
- Source comparison confirmed that all twelve source/document files exactly matched the approved worker commits before the final gates. Later changes only record verification and the observed routing limit; no Cargo input changed.

## Independent wire acceptance

The temporary harness links the exact stable Cargo artifacts built from the source commit above. It calls the real public runtime, uses local sockets and synthetic credentials, and never replaces application parsing or routing. Exact artifact paths and rustc command are retained locally.

`env -u AUDIT_TOKEN -u AUDIT_DELAY python3 /tmp/ripdpi-loc05-fixed-acceptance/acceptance.py` completed with **35 passed observations**. Expected statuses and payloads are independent literal bytes. Checks include malformed framing and fields, Basic, HTTP/1.0 and 1.1, Mixed ingress, bracketed IPv6, header limits, fragmented delimiter and early binary tunnel bytes.

Installed Python 3.11.14 established its default HTTP/1.0 CONNECT tunnel and verified a local TLS certificate/name before exact echo. Installed curl/libcurl 8.7.1 completed Basic plus verified TLS and returned the exact body LOC05-OK-TLS; the origin received no Proxy-Authorization header or token. These are concrete local client compatibility results, not external server or Android proof.

The first delayed TLS acceptance attempt used a one-accept echo fixture and failed after 200. Diagnosis confirmed a real group change: --proto t starts a default provisional group before payload, then selects the TLS group after ClientHello and reconnects. The one-accept fixture closed its listener after the first EOF. A real TLS server with an accept loop then completed TLS 1.3 and exact binary echo on the second connection. This corrected the fixture assumption without changing application code or weakening payload/TLS assertions. Both initial failure and final evidence are retained.

## Reconciliation and preserved contracts

While implementation was running, independent shared-listener and SOCKS4 work reached main. The job and both workers rebased onto commit 88440b72344cfa19970e76258f11ccad6b4d99df. The final parser reuses HttpConnectHead and validates fields/framing before auth and DNS; its public API remains intact. HTTP reuses the existing PreparedWsTunnel/open, cancellation budget, seed routing and socks_delay. Duplicate WS interfaces, backend code and API snapshot changes were discarded. All upstream tests and half-close/cancellation behavior remain.

HTTP WS carrier failures now return 502, or 504 for a timeout, before any 200 or client seed. Only SOCKS4 retains its existing carrier-failure classification path. The HTTP change adds no speculative direct connection for classification.

## Limits and remaining coverage

- Payload routing can replace a provisional TCP group after CONNECT success. This opens a second connection; origins that accept only one connection can fail with that configuration. This is the preserved shared routing contract, now used by HTTP. Ordinary accept-loop TLS servers passed the independent probe.
- The runtime WS readiness test uses real local sockets and a held wire handshake. Production verified TLS/HTTP101 is tested in the transport crate. A successful whole HTTP-to-production-WSS path is not claimed from those separate tests.
- The seven ignored Rust tests remain unrun by this command set: one manual benchmark and six relay interoperability tests. New LOC-05 regressions and independent acceptance did run.
- No physical Android/VPN/LAN, external server, release package or deployment probe ran. Root activation and real secrets were not used.
- The old DNS cancellation hypothesis is covered by newly upstreamed ownership/deadline changes and the two current cancellation tests. The separate IPv6 flag policy question remains a contract question; this change does not redefine it.
- C is current source evidence; V is the named host runs; S is limited to the selected HTTP/Basic requirements from the audit; E is only the named Python/curl loopback compatibility. No blanket RFC or external-network conformance is claimed.

## Local evidence

Checked-in regressions and this source commit retain the behavior. Supplementary logs are private and temporary:

- /tmp/ripdpi-loc05-fix-logs: combined-tests.log, combined-clippy.log, combined-fmt.log, combined-source-state.log, metadata.json, ci-gates.log, independent-acceptance.log, independent-acceptance-final.log and delayed-tls-probe.log.
- /tmp/ripdpi-loc05-fixed-acceptance: exact artifact build JSON, rustc command, real runtime harness, independent client scripts and results.json. Certificates and credentials are synthetic.
- /tmp/ripdpi-loc05-parsing-logs: red/green, per-fix hooks, and reconciled parser verification.
- /tmp/ripdpi-loc05-f01-logs: original and reconciled readiness regressions, full checks, reviews and hook logs.

## Integration

The user authorized main integration and a remote push. This record verifies local source before integration. The final handoff records the exact pushed SHA and remote result; no hosted CI result is inferred from push success.
