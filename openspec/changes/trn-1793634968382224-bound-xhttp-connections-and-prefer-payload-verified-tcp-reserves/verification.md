---
task_id: TRN-1793634968382224
change: trn-1793634968382224-bound-xhttp-connections-and-prefer-payload-verified-tcp-reserves
commit_sha: 2260e0b9e54eabe0c7ad7ae0a86086b5f76ef173
local: passed
local_evidence: Native crate tests, strict async Clippy, Miri registry test, 123 targeted Kotlin tests, staticAnalysis, architecture health and independent review passed on the source inputs.
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: No physical device acceptance is owned by this local implementation PR.
artifact: not_applicable
artifact_evidence: No release artifact is published by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-XHTTP-POOL | TRN-1793635054587188 | Import/storage/DTO tests; full ripdpi-xhttp and ripdpi-relay-core tests; real carrier-count, cancellation, EOF and recovery regressions | passed |
| REQ-TCP-RESERVE | TRN-1793635055012253 | Selector payload recovery, transition, all-failure and existing HTTP verifier tests | passed |

## Observed local checks

- Targeted Kotlin owner classes: 75 passed. Existing HTTP verifier and engine wire/snapshot classes: 48 passed. The moved service case retains all 14 owner tests.
- `cargo test --locked -p ripdpi-xhttp -p ripdpi-relay-core --jobs 4`: 285 passed. Three existing independent-peer tests remained ignored; no test was newly ignored.
- `cargo clippy --locked -p ripdpi-xhttp -p ripdpi-relay-core --all-targets --jobs 4 -- -W clippy::await_holding_lock -W clippy::await_holding_refcell_ref -W clippy::await_holding_invalid_type -D warnings`: passed.
- `cargo +nightly miri test --locked -p ripdpi-xhttp completed_request_tasks_do_not_accumulate_and_shutdown_drops_live_tasks --jobs 4` with strict provenance: 1 passed. This checks the task registry, not network or device acceptance.
- `python3 scripts/ci/check_architecture_health.py`: passed, 19 current and baseline indicators, zero new, worsened or stale entries.
- Physical carrier ownership regression: passed for mask-off and non-noop header masking; unpublished cancellation retains capacity until real socket close.
- Paired producer fixture comparison: identical bytes after formatting.
- Independent source review: approved after carrier lifetime, task retention and TCP priority transition regressions passed.
- Full `staticAnalysis` plus final owner/boundary test classes: passed in 4m 46s; 123 Kotlin tests passed with zero failures or skips. No baseline was changed.

## Limits

Tuned profiles require JSON bundle sharing. URI export refuses because its format cannot retain the pool limit. No physical device, emulator, external network, release artifact or deployment acceptance is claimed.
