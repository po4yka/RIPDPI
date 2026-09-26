---
task_id: RST-1790431269626912
change: preserve-lua-active-flow-state
commit_sha: a30aabb71270e821499862c9d281a066d27ce505
local: required
local_evidence: Four affected Rust crates passed their complete Cargo test suites with lua-strategies enabled; cargo fmt, API snapshot, architecture health, native contracts, and cargo metadata passed.
remote_ci: blocked
remote_ci_evidence: No push or hosted CI run is authorized for this local change.
device: blocked
device_evidence: Android device packet-path verification has not run.
artifact: required
artifact_evidence: The updated ripdpi-strategy-trait API snapshot passed check_rust_api_snapshots.py.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-LUA-FLOW-CAPACITY | RST-1790431482078217 | `lua_keeps_active_state_at_capacity_and_reuses_closed_flow_slot` and `lua_strategy_rejects_overflow_without_evicting_active_flow` passed | pass |
| REQ-LUA-FLOW-CLOSE | RST-1790431482078217 | `lua_consumed_tcp_fin_releases_state`, `lua_udp_source_close_releases_all_destinations_and_idle_orphans`, and tunnel session and association close tests passed | pass |
| REQ-LUA-FLOW-FAILURE | RST-1790431482078217 | `lua_tun_failure_uses_configured_drop_policy` passed | pass |

Strict Clippy remains blocked by existing `collapsible_if` and `needless_late_init` warnings in unchanged code. The affected crates pass Clippy when only those two rules are excluded for diagnosis.
