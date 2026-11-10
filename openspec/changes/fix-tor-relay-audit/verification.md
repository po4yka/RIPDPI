---
task_id: RLY-1794300120922878
change: fix-tor-relay-audit
commit_sha: null
local: required
local_evidence: null
remote_ci: not_applicable
remote_ci_evidence: Local gates are required; hosted CI is not an acceptance gate for this repair.
device: not_applicable
device_evidence: No physical device or external Tor endpoint is authorized. Local process and loopback regressions cover this repair.
artifact: not_applicable
artifact_evidence: No release artifact is produced.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TOR-DNS | RLY-1794300204878737 | Targeted regression and independent review completed; combined gates pending | local passed |
| REQ-TOR-BUDGET | RLY-1794300205521753 | Targeted regression and independent review completed; combined gates pending | local passed |
| REQ-TOR-RETRY | RLY-1794300206174299 | Targeted regression and independent review completed; combined gates pending | local passed |
| REQ-TOR-SHUTDOWN | RLY-1794300206828819 | Targeted regression and independent review completed; combined gates pending | local passed |
| REQ-TOR-STATE | RLY-1794300207611622 | Targeted regression and independent review completed; combined gates pending | local passed |
| REQ-TOR-VALIDATION | RLY-1794300208607627 | Targeted regression and independent review completed; combined gates pending | local passed |

## Observed local behavior

- Rust proxy runtime: 383 unit tests passed. `tor_remote_dns`: two real loopback regressions passed, including SOCKS4a, SOCKS5, HTTP, plaintext address input in Mixed/BytePrefixed mode, authentication, active Stop, upstream REP5, policy REP2 and unsupported-command REP7. Pinned Rust 1.98.1 logs: `/tmp/rly13-telemetry-tests.log`, `/tmp/rly13-telemetry-clippy.log`. The loopback cases also prove production tx/rx counters and upstream forwarding evidence for the four-byte exchange.
- Proxy configuration: 154 tests passed; session: 88 tests passed. Logs: `/tmp/rly13-config-final.log`, `/tmp/rly13-session-final.log`.
- Tor baseline: ten local tests passed. Bootstrap retry regression passed. Three PT regressions passed: quiet/stubborn process shutdown, injected reader spawn failure, and missing executable error classification. Logs: `/tmp/rly13-lifecycle-parent.log`, `/tmp/rly13-lifecycle-final-verified.log`, `/tmp/rly13-pt-final-tests.log`. Chutney early-return tests are excluded from interoperability evidence.
- Kotlin: 51 selected tests passed: service paths (2), settings bridge validation (4), app draft validation (1), Xray import (42), and Trojan rejection (2). This includes distinct unpaired UTF-16 code units in state keys. Final targeted log: `/tmp/rly13-kotlin-targeted-complete.log`.
- Independent PR reviews covered all six repairs. Findings on bridge identities, Snowflake absent address, URL validation, SOCKS reply codes, thread startup ownership and PT startup classification were fixed before final review. Async review found no semantic cancellation defect; its documentation findings were fixed.

## Integration gate work

- Initial static analysis found dispatcher complexity introduced by the Tor branch and a pre-existing private test constant naming violation. The dispatcher now owns its Tor error mapping in a separate function. The constant rename is a separate mechanical prerequisite commit; no fixture value changed.
- The broad static-analysis run was interrupted before completion so final checks can use the latest upstream base. Its partial output is not a passing gate. Final `just ci-gates`, `staticAnalysis`, locked Cargo metadata and architecture health remain pending. No baseline or golden fixture is blessed or extended.
- Local behavior runs used the owning worktrees. Final combined-source evidence will name the integrated source commit. No public Tor circuit, Android runtime, release artifact or deployment is claimed.

- Final Tor domain-path review restored application-byte counters and forwarding evidence. The loopback regression uses the production telemetry observer and checks only bytes committed by successful writes. Pinned Rust 1.98.1 and a worktree-local target directory are used for final Rust validation.
