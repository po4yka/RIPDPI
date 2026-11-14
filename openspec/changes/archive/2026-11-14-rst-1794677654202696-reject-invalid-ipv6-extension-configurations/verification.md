---
task_id: RST-1794677654202696
change: rst-1794677654202696-reject-invalid-ipv6-extension-configurations
commit_sha: fc50dd301a61861978f70af83bcb26c0fafa665e
local: passed
local_evidence: Pinned Rust 1.100 suites passed 298 tests; just ci-gates, architecture health and locked cargo metadata passed.
remote_ci: not_applicable
remote_ci_evidence: Local repair gates; hosted CI is not an acceptance claim.
device: not_applicable
device_evidence: Input validation and refusal only; no raw device execution is changed or claimed.
artifact: passed
artifact_evidence: Independent pr-reviewer found no actionable findings in all three repairs; source gates passed.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-IPV6-ROUTING | RST-1794677811832188 | cargo test --locked -p ripdpi-strategy-ipv6 -p ripdpi-strategy-config | Passed |
| REQ-IPV6-TYPE | RST-1794677812640554 | cargo test --locked -p ripdpi-strategy-config -p ripdpi-tunnel-intercept | Passed |
| REQ-IPV6-UDP | RST-1794677813465324 | cargo test --locked -p ripdpi-proxy-config; just ci-gates | Passed |

## Observed evidence

Implementation SHA: `fc50dd301a61861978f70af83bcb26c0fafa665e`. The three fixes are `63b59b1968` (UDP applicability), `3c530fc065` (routing refusal) and `fc50dd301a` (explicit extension types).

- Pinned Rust 1.100, native/rust: `CARGO_TARGET_DIR=/private/tmp/ripdpi-dpi33-pinned-target cargo test --locked -p ripdpi-strategy-ipv6 -p ripdpi-strategy-config -p ripdpi-tunnel-intercept` passed 100 tests: config 26, IPv6 strategy 12 and interceptor 62. Log: `/private/tmp/dpi33-f1-f2-final-tests.log`.
- Pinned `cargo test --locked -p ripdpi-proxy-config` passed 188 unit plus 10 integration tests. No ignored or filtered tests in either command. Log: `/private/tmp/dpi33-udp-pinned-green.log`. Later F1/F2 edits did not change proxy-config inputs.
- `just ci-gates` exited 0 on the combined tree. API snapshots match under the standard Darwin exclusion: the Linux-owned runtime-platform canonical snapshot is intentionally skipped. Log: `/private/tmp/dpi33-ci-gates.log`.
- `python3 scripts/ci/check_architecture_health.py` passed in default check mode. `cargo metadata --locked --format-version 1` from native/rust exited 0; artifact `/private/tmp/dpi33-metadata.json`.
- Independent read-only pr-reviewer found no actionable findings in all three fixes.
- Fetch/rebase onto origin/main was up to date and introduced no new code inputs.

Temporary logs can expire. This committed record preserves commands, scope, counts and implementation SHA. Hosted CI, Linux-only API snapshot verification, Android/VPN raw sender, TCP_REPAIR and external compatibility are not claimed.

## Scope boundaries

No new packet-generation capability, network activation, dependency, schema, golden fixture, baseline or API snapshot is introduced. Routing is unsupported. Existing hopByHop2 and prefragment Destination Options semantics remain. The raw on_fail ambiguity and direct-path privacy/device questions remain separate acceptance gaps.
