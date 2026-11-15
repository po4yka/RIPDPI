---
task_id: DGN-1794732408586377
change: dia04-dns-integrity
commit_sha: 38e5b6b63470a5a5dd90e571eb649ff4cf137dfe
local: required
local_evidence: Rebased source 38e5b6b634: 897 passed, 1 monitor report golden failed, 3 pre-existing ignored. Formatting, all-target Clippy, workspace check, locked metadata and just ci-gates passed. The user approved the monitor golden update; narrow bless and no-bless checks passed. Final combined verification after the latest ECH-contract rebase remains required.
remote_ci: required
remote_ci_evidence: No repair commit has been pushed. Main integration waits for the local compatibility gate.
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
| REQ-DIA04-ORACLE | DGN-1794732638260356 | 8e8aa77eef | Default canonical identity, provider aliases, and transitive alias bridges in both catalog orders pass. Components are computed before the fallback limit. |
| REQ-DIA04-OVERLAP | DGN-1794732655554818 | 8085991226 | Extra sinkhole addresses cannot hide behind exact or prefix public overlap. Identical private answers and ordinary CDN subsets remain distinct cases. |
| REQ-DIA04-CDN-CONSUMER | DGN-1794732714208860 | d54f11cea3 | Restored configuration B reaches the real TLS configuration consumer. Missing, expired, and poisoned caches use bundled bytes without a fetch. Connected-peer binding from DIA-02 remains intact. |
| REQ-DIA04-PROTECT | DGN-1794732710855083 | 85ecf380c4 | Protected unconnected socket creation runs before connect. Protection failure stops the path; alias and retry hooks remain wired. Deadline and cancellation behavior from DIA-01 remains intact. |
| REQ-DIA04-EVIDENCE | DGN-1794732706316070 | ee3015be08 | Selected fallback facts, endpoint, and latency share one response owner. Unselected negative observations stay explicit. Typed IP arrays reject error fields and do not revive stale legacy values. |
| REQ-DIA04-COMPRESSION | DGN-1794732717208585 | b918884351 | An independent four-hop wire vector is accepted. Real repeated-pointer cycles and out-of-bounds data remain rejected. |
| REQ-DIA04-EDE | DGN-1794732689288627 | 82f0cbd2d6 | Real loopback UDP and binary HTTP DoH exchanges request EDNS and retain numeric EDE for NOERROR and NXDOMAIN. No EXTRATEXT is retained. |
| REQ-DIA04-ECH-ADMISSION | DGN-1794732668757031 | 3207f35d48 | Full structural parser and supported-entry selection reject malformed persisted data without replacing a valid entry. Unknown-first plus supported-second lists are accepted. |

Each implementation commit received an independent read-only review. Post-rebase review at source SHA 38e5b6b63470a5a5dd90e571eb649ff4cf137dfe found no merge loss or new finding.

## Combined local commands

All Cargo resolution commands use Rust 1.100.0 and --locked. Heavy commands use `build-gate --lane ripdpi`; the target directory belongs only to this integration worktree. Normal commit hooks are enabled.

The combined command selects these packages: ripdpi-diagnostics-contracts, ripdpi-diagnostics-dns, ripdpi-ech-dns, ripdpi-diagnostics-classification, ripdpi-diagnostics-runner, ripdpi-diagnostics-transport, ripdpi-diagnostics-tls, ripdpi-monitor-engine.

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

Final combined gates remain required after integration of the newly fetched DIA-06 ECH contract changes at origin/main 07626efa72. No final green result is claimed before that run.

## Large UDP input check

A temporary standalone harness includes the actual compression.rs and wire_header.rs sources. dnspython 2.8.0 independently accepted a standard 65050-byte DNS response, including one A answer and an unknown EDNS option, and rejected an out-of-bounds pointer. The actual validator accepted the valid packet and rejected the malformed packet. The single valid validation took 2 microseconds in this observed host run; this is not a benchmark or a general complexity bound. The harness and its test-only Python dependency are outside the checkout at /Users/npochaev/GitHub/RIPDPI-worktrees/dia04-large-wire-check/.

A worst-case complexity estimate for all shared compression chains remains an unmeasured hypothesis, not a confirmed defect. No algorithm change is justified by this ordinary large-input smoke alone.

## Evidence limits

C: implemented source and callers were reviewed. V-local: owning host and loopback regressions were observed. S-fragment from the DIA-04 audit remains limited to the cited requirements; these repairs do not establish full conformance. E: public resolver interoperability and external ECH acceptance were not run. Consensus remains a consistency signal, not DNSSEC validation. Live AAAA, generic SVCB endpoint expansion, negative quorum and the audit hypotheses remain outside these confirmed repairs.
