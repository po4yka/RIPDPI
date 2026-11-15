---
task_id: DGN-1794732375813278
change: correct-dia05-dns-json-contracts
commit_sha: 6aa83b8ce26ac400e01c9bffade80bc7539f0e5f
local: passed
local_evidence: "124 targeted tests passed on the combined tree with Rust 1.100.0, locked offline Cargo, private target, build-gate ripdpi and four jobs. Clippy all-targets for four affected crates, format, just ci-gates, independent review and both independent oracles passed. See commands and limits below."
remote_ci: not_applicable
remote_ci_evidence: "No remote CI run is required for this host-side repair. A push is not remote CI acceptance; no remote CI success is claimed."
device: not_applicable
device_evidence: Host-side parser and evidence repair; no device or external probe is required or authorized.
artifact: passed
artifact_evidence: "The owning generator regenerated the diagnostics-contracts public API snapshot; just ci-gates checked it and every required Rust API snapshot without drift."
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DGN-1794732375813278-001 | DGN-1794732594607662 | F1 parser tests require matching Question, explicit RR type/name and IP family; valid CNAME, malformed labels, cycles and truncation cases passed. Scheduled and both adapter tests passed. | passed |
| REQ-DGN-1794732375813278-002 | DGN-1794732621573690 | F2 parser and all callers retain Answer, NODATA, NXDOMAIN, DNS RCODE and malformed-response distinctions; negative RCODE records are validated before dispatch. | passed |
| REQ-DGN-1794732375813278-003 | DGN-1794732644517636 | A real stalled loopback HTTP peer retained ResponseRead timeout kind; cancellation and slow-drip deadline tests passed. Legacy setup timeout mapping tests passed. | passed |
| REQ-DGN-1794732375813278-004 | DGN-1794732658759810 | Five analytics tests passed, including mixed healthy/unknown, empty report, contextual DNS and failure/attention precedence. Upstream connectivity_inconclusive naming is retained. | passed |
| REQ-DGN-1794732375813278-005 | DGN-1794732674757937 | Library HTTP 403/429/500/503, timeout, network, malformed and DNS-negative cases stay inconclusive without independent blocking evidence. Any-provider positive policy is retained. | passed |
| REQ-DGN-1794732375813278-006 | DGN-1794732697109380 | Both runners sent the correct real loopback GET. Python urllib.parse independently decoded captured query values. Invalid query/path endpoints caused no TCP connection; malformed ports and escapes passed rejection tests. | passed |
| Combined source gates | DGN-1794732708545147 | 124 targeted tests, clippy, format, just ci-gates, architecture health, locked Cargo metadata, independent review and oracles | passed |

Final validation base: `0218a6b688d94ddad256f2e2f47e9e3afc30d1bd`. The original F3/F6 history fold kept its checked source unchanged. Later rebases retained upstream API changes, including secure/key-log arguments, and passed all affected checks again.

## Observed local checks

All Cargo commands used `--locked`, Rust 1.100.0 and offline resolution. Build and test commands used `build-gate --lane ripdpi`, the worktree's own target directory, `CARGO_INCREMENTAL=0`, `CARGO_BUILD_JOBS=4` and `CMAKE_BUILD_PARALLEL_LEVEL=4`.

| Command from native/rust unless stated otherwise | Observed result |
|---|---|
| `cargo test -p ripdpi-diagnostics-contracts -p ripdpi-diagnostics-probes -p ripdpi-monitor-engine --locked --offline --jobs 4 --lib doh_` | 9 + 27 + 11 = 47 passed |
| `cargo test -p ripdpi-monitor-engine --locked --offline --jobs 4 --lib connectivity_analytics_summary` | 5 passed |
| `cargo test -p ripdpi-diagnostics-http --locked --offline --jobs 4 --lib http::` | 59 passed |
| `cargo test -p ripdpi-diagnostics-probes --locked --offline --jobs 4 --test doh_survey_requests --test dns_measurement_consent -- --nocapture` | 2 wire + 3 consent tests passed |
| `cargo test -p ripdpi-dns-resolver --locked --offline --jobs 4 --test doh_response_contract` | 8 passed |
| `cargo clippy -p ripdpi-diagnostics-contracts -p ripdpi-diagnostics-http -p ripdpi-diagnostics-probes -p ripdpi-monitor-engine --locked --offline --jobs 4 --no-deps --all-targets -- -D warnings` | Passed |
| `cargo fmt --all -- --check` | Passed |
| `just ci-gates` from repository root | Passed, including architecture and required API snapshots |
| `cargo metadata --locked --offline --format-version 1` | Passed; 113 workspace members, 936 packages |
| `taskctl validate` and strict OpenSpec validation | Passed; rechecked with final evidence |

Logs are local in `/Users/npochaev/.cache/ripdpi-dia05-repair-patches/final-*.log`. The script stopped on failures. Two compile errors introduced by upstream API changes during rebase were corrected before the final green run; their failed outputs are retained separately. They are not passing evidence.

## Independent review and oracles

- Read-only pr-reviewer covered the six findings and repeated review for fixes found during review. It also checked both rebase resolutions, the fail-closed watchdog, generic HTTP wrapper conversion, F4 precedence and F6 validation.
- Async review found two conditionally cancel-safe adapter functions, no bug and no missing cancel-safety block. Client GET futures must release local resources on drop; a resolver may already have received a request.
- Python standard-library urllib.parse independently decoded two captured HTTP request targets as trace=kept, ct=application/dns-json, name=a&b.example and type=A. The TCP peer and exact request bytes establish local wire behavior.
- dnspython 2.8.0 independently decoded the unchanged binary fixtures: good answer has ID 48879, example.test IN A and 192.0.2.7; negative fixtures contain another ID or Question. The oracle did not send DNS queries.

## Evidence limits

C is implemented and traced source. V is the observed local tests, loopback traffic and offline classifiers above. The original audit's S evidence remains its checked standards and vendor contracts. No external-provider E, Android device run, root activation or real-secret test was performed. These local results do not establish those gates.

The survey remains vendor JSON A GET with the existing any-provider positive aggregate. It does not add scheduled binary RFC 8484 support, AAAA survey, DNSSEC validation, HTTP/2 or HTTP/3 coverage. Connection/TLS setup still has a legacy String cause boundary; this repair preserves its prior timeout-first mapping. Established request writes and response reads retain typed I/O causes. Complete transport/TLS cause typing is outside these six regression fixes.
