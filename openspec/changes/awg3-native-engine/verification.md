---
task_id: TRN-1792949035079937
change: awg3-native-engine
commit_sha: null
local: passed
local_evidence: Combined gate on fix/import-review: :app:testGithubFullDebugUnitTest, :app:lintGithubFullDebug, :core:service:lintDebug, :app:detekt, :app:ktlintCheck and the core data, runtime-state, engine-api, engine and service JVM suites plus their detekt/ktlint exited 0; cargo test, clippy -D warnings and fmt --check for ripdpi-warp-core exited 0; check_architecture_health --check and check_native_hotspot_budgets exited 0 with no new indicators.
remote_ci: required
remote_ci_evidence: Hosted gates on the pushed commit are required.
device: required
device_evidence: A physical arm64 run is required before the S3/S4 floor can change; header protection cannot start in the app until then.
artifact: not_applicable
artifact_evidence: No release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG3-WIRE | TRN-1792949081837648 | Live run against amneziawg-go v3.1.20260828 (MTU 1280 and 1420, two sessions each; wrong-key negative control failed every session) and Go-derived vectors in awg_v3_tests.rs | Passed locally |
| REQ-AWG3-HOSTILE | TRN-1792949082316022 | unauthenticated_transport_does_not_grow_the_udp_window_and_growth_is_capped; short and cookie cases in only_boringtun_authenticated_transport_may_teach_the_udp_window; live rerun 4/4 sessions against amneziawg-go v3.1 | Passed locally |
| REQ-AWG3-CONTRACT | TRN-1792949082316022 | core:data, engine-api, engine and service JVM suites; ignored-key tests | Passed locally |
| REQ-AWG3-FLOOR | TRN-1792949082866472 | Floor tests pass locally; device revalidation not done | Required |
