---
task_id: DGN-1794732408586377
change: dia04-dns-integrity
commit_sha: 53042087eeb2e09f55a4da98787b1819c8348b57
local: passed
local_evidence: Final source 53042087ee passed nine library suites (1024 passed, zero failed, three pre-existing ignored), formatting, all-target Clippy with -D warnings, workspace check, locked metadata and just ci-gates. The monitor golden also passed without blessing.
remote_ci: required
remote_ci_evidence: Hosted CI acceptance remains required for task closure and is separate from this local receipt. Push status and exact-head runs are reported in the handoff; no hosted success is claimed here.
device: not_applicable
device_evidence: Host callback and loopback tests cover source contracts. Android routing and physical acceptance are not claimed.
artifact: not_applicable
artifact_evidence: No release artifact is published.
deployment: not_applicable
deployment_evidence: No deployment is owned by this repair.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Implementation commit | Observed regression evidence |
|---|---|---|---|
| REQ-DIA04-ORACLE | DGN-1794732638260356 | 834a293f19 | Default canonical identity, provider aliases, and transitive alias bridges in both catalog orders pass. Components are computed before the fallback limit. |
| REQ-DIA04-OVERLAP | DGN-1794732655554818 | f1d7d66e35 | Extra sinkhole addresses cannot hide behind exact or prefix public overlap. Identical private answers and ordinary CDN subsets remain distinct cases. |
| REQ-DIA04-CDN-CONSUMER | DGN-1794732714208860 | 18ddebee08 | Restored configuration B reaches the real TLS configuration consumer. Missing, expired, and poisoned caches use bundled bytes without a fetch. Connected-peer binding from DIA-02 remains intact. |
| REQ-DIA04-PROTECT | DGN-1794732710855083 | bc5f2b9548 | Protected unconnected socket creation runs before connect. Protection failure stops the path; alias and retry hooks remain wired. Deadline and cancellation behavior from DIA-01 remains intact. |
| REQ-DIA04-EVIDENCE | DGN-1794732706316070 | 15b87e1843 | Selected fallback facts, endpoint, and latency share one response owner. Unselected negative observations stay explicit. Typed IP arrays reject error fields and do not revive stale legacy values. |
| REQ-DIA04-COMPRESSION | DGN-1794732717208585 | 05b93b9301 | An independent four-hop wire vector is accepted. Real repeated-pointer cycles and out-of-bounds data remain rejected. |
| REQ-DIA04-EDE | DGN-1794732689288627 | 5c8d773e25 | Real loopback UDP and binary HTTP DoH exchanges request EDNS and retain numeric EDE for NOERROR and NXDOMAIN. No EXTRATEXT is retained. |
| REQ-DIA04-ECH-ADMISSION | DGN-1794732668757031 | e6ef316c8e | Full structural parser and supported-entry selection reject malformed persisted data without replacing a valid entry. Unknown-first plus supported-second lists are accepted. |

Each implementation commit received an independent read-only review. Post-rebase review at source SHA 38e5b6b63470a5a5dd90e571eb649ff4cf137dfe found no merge loss or new finding.

## Combined local commands

All Cargo resolution commands use Rust 1.100.0 and --locked. Heavy commands use `build-gate --lane ripdpi`; the target directory belongs only to this integration worktree. Normal commit hooks are enabled.

The earlier eight-package command selects these packages: ripdpi-diagnostics-contracts, ripdpi-diagnostics-dns, ripdpi-ech-dns, ripdpi-diagnostics-classification, ripdpi-diagnostics-runner, ripdpi-diagnostics-transport, ripdpi-diagnostics-tls, ripdpi-monitor-engine.

- `cargo +1.100.0 fmt -p <each package> -- --check`
- `cargo +1.100.0 test --locked -p <each package> --lib`
- `bash scripts/ci/cargo-guarded.sh cargo clippy --locked -p <each package> --no-deps --all-targets -- -D warnings`
- `cargo +1.100.0 check --locked --workspace`
- `cargo +1.100.0 metadata --locked --format-version 1`
- `just ci-gates`

Previous combined source d917bb96ae52743a9ff3bc64798379ed45077dcc: classification 80 passed; contracts 74 passed; DNS 103 passed; runner 138 passed and 2 existing ignored; TLS 52 passed; transport 112 passed and 1 existing ignored; ECH DNS 48 passed. Monitor engine: 277 passed and one failed report golden. Formatting, all-target Clippy, workspace check, metadata, and CI source gates passed. These counts are historical and are not the rebased-tree result.

Rebased source 38e5b6b63470a5a5dd90e571eb649ff4cf137dfe: classification 81 passed; contracts 75 passed; DNS 103 passed; runner 141 passed and 2 pre-existing ignored; TLS 52 passed; transport 112 passed and 1 pre-existing ignored; ECH DNS 48 passed; monitor engine 285 passed and one failed report golden. Total: 897 passed, one failed, three pre-existing ignored. Formatting, all-target Clippy with -D warnings, workspace check, locked metadata, and just ci-gates passed. The validation runner returned exit 1 because the tests phase returned 101; every other phase returned 0. No failed or ignored test was removed or newly skipped.

The ignored cases are runner dns_probe_appends_dns_trigger_fuzzing_details and domain_probe_appends_http_trigger_fuzzing_details, and transport direct_route_experiment_uses_diversity_bucket_when_stable_port_is_busy. They are not acceptance evidence.

Logs and the complete invocation script are preserved outside the repository in `/Users/npochaev/GitHub/RIPDPI-worktrees/dia04-validation-rebased/`. The snapshot source SHA predates documentation-only evidence updates; no production or test input changed during this run.

## Compatibility gate

The user explicitly approved the Rust monitor-engine JSON family and its narrow owning test. The isolated golden specialist produced commit e6630f80d26d79a3fab5186e052b540068c1433f. Only native/rust/crates/ripdpi-monitor-engine/tests/golden/final_report.json changed (+74/-26). The specialist and the parent inspected the full diff. A strict expected JSON transformation check also passed.

The intended semantic delta adds tlsReportedAttempt and per-attempt failure stage, certificate-verification, and ClientHello-capture facts; reorders HTTP/TLS details; and removes synthetic tlsEchResolutionDetail: none. Only numeric values of the three duration keys are normalized to 0. No semantic field is masked.

From the isolated native/rust directory, through build-gate --lane ripdpi:

- RIPDPI_BLESS_GOLDENS=1 cargo +1.100.0 test --locked -p ripdpi-monitor-engine --lib tests::monitor_json_contracts_match_goldens -- --exact: 1 passed.
- The same exact command with RIPDPI_BLESS_GOLDENS removed from the environment: 1 passed.

All four fixtures checked by the owning test passed on the no-bless run. The other three fixtures did not change. Normal commit hooks passed. Logs are in /Users/npochaev/GitHub/RIPDPI-worktrees/dia04-golden-evidence/.

Final combined gates passed after integration of the DIA-06 ECH contract changes at origin/main 07626efa72. The original golden commit is rebased as 69039d6689; its patch is unchanged. git range-diff confirmed that all fourteen repair, test and documentation patches survived the rebase without a content change.

## Final combined receipt

Exact source SHA: 53042087eeb2e09f55a4da98787b1819c8348b57. The final isolated worktree was clean. All commands used Rust 1.100.0, the unique golden-worktree Cargo target and build-gate --lane ripdpi. RIPDPI_BLESS_GOLDENS was absent from every final command environment.

The final selection adds ripdpi-diagnostics-probes to the eight DIA-04 packages because incoming DIA-06 changed that crate and its public enum. Library suites passed: classification 82; contracts 75; DNS 103; probes 114; runner 143 plus two pre-existing ignored; TLS 52; transport 120 plus one pre-existing ignored; ECH DNS 48; monitor engine 287. Total: 1024 passed, zero failed, three pre-existing ignored. The monitor suite includes the golden test without blessing.

All six phases returned zero: nine-package formatting; cargo test --locked --lib; nine-package all-target Clippy with -D warnings; cargo check --locked --workspace; cargo metadata --locked --format-version 1; just ci-gates. Exact expanded commands, working directories, environment declaration, timings and log paths are recorded in /Users/npochaev/GitHub/RIPDPI-worktrees/dia04-golden-evidence/final-results.json and final_checks.py. All final per-phase logs are in that directory.

Documentation-only completion updates follow the tested source SHA. They do not change production or test inputs. Normal commit hooks validate the final task and specification artifacts. Hosted Linux/Android CI and physical or external acceptance are not inferred from this host run.

## Large UDP input check

A temporary standalone harness includes the actual compression.rs and wire_header.rs sources. dnspython 2.8.0 independently accepted a standard 65050-byte DNS response, including one A answer and an unknown EDNS option, and rejected an out-of-bounds pointer. The actual validator accepted the valid packet and rejected the malformed packet. The single valid validation took 2 microseconds in this observed host run; this is not a benchmark or a general complexity bound. The harness and its test-only Python dependency are outside the checkout at /Users/npochaev/GitHub/RIPDPI-worktrees/dia04-large-wire-check/.

A worst-case complexity estimate for all shared compression chains remains an unmeasured hypothesis, not a confirmed defect. No algorithm change is justified by this ordinary large-input smoke alone.

## Evidence limits

C: implemented source and callers were reviewed. V-local: owning host and loopback regressions were observed. S-fragment from the DIA-04 audit remains limited to the cited requirements; these repairs do not establish full conformance. E: public resolver interoperability and external ECH acceptance were not run. Consensus remains a consistency signal, not DNSSEC validation. Live AAAA, generic SVCB endpoint expansion, negative quorum and the audit hypotheses remain outside these confirmed repairs.
