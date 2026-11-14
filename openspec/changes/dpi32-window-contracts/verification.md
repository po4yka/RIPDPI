---
task_id: PRX-1794677617943037
change: dpi32-window-contracts
commit_sha: edcfed3c1793fe7d69890bd525e86de127c907a0
local: passed
local_evidence: Rebased HEAD edcfed3c1793fe7d69890bd525e86de127c907a0 passed 31 selected Rust tests, 9 wire helper tests, 8 Kotlin writer tests, wire compile, workspace Clippy, just ci-gates, architecture health and locked metadata. Initial Linux passed 22 tests and wire compilation. Independent review passed.
remote_ci: required
remote_ci_evidence: Exact pushed commit CI is pending.
device: not_applicable
device_evidence: This change repairs socket contracts and admission with local tests. Physical and privileged acceptance is not authorized; no device compatibility claim is made.
artifact: not_applicable
artifact_evidence: No APK, release binary or golden artifact is requested.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-WINDOW-HINT | PRX-1794677889882075 | Model/proto comments and `tcp_route_connect_settings_keep_buffer_hint_and_clamp_distinct` | C and V passed |
| REQ-WINDOW-BOUNDS | PRX-1794677895341059 | CLI, native JSON, programmatic admission and Linux unchanged-socket-state regressions | C and V passed |
| REQ-WINDOW-OUTCOME | PRX-1794677902771029 | Real socket readback, INFO event capture, ENOPROTOOPT classification, failed action receipt, and payload delivery | C and V passed |
| REQ-WINDOW-ADMISSION | PRX-1794677906705947 | `socket_load_rejects_window_steps_without_an_executor` and `production_constructor_rejects_window_steps_without_socket_ownership` | C and V passed |
| REQ-WINDOW-WIRE | PRX-1794677909411409 | Nine explicit-byte analyzer tests; host and Linux wire target compile | C and V-analyzer passed; V-capture unrun |
| REQ-WINDOW-AUTO | PRX-1794677889882075 | Kotlin protobuf settings round trip for absent, -1, 0 and 14 | C and V passed |

## Rebased validation

HEAD `edcfed3c1793fe7d69890bd525e86de127c907a0` passed 31 selected Rust library tests (1187 excluded by selection), including both adapter projections and both tests involved in the rebase conflict. Nine wire helpers passed, the wire target compiled, workspace Clippy passed, and all eight Kotlin writer tests passed. `just ci-gates`, architecture health `--check`, and locked metadata passed. Architecture health reported no new or worsened indicators across 112 crates and 354 internal edges.

The first rebased Kotlin invocation used the wrong local property `skipNativeBuild`, so the native build correctly rejected ambient Cargo overrides. The command was corrected to `ripdpi.skipNativeBuild`, with Cargo job/incremental overrides removed from the Gradle environment. Only the remaining stages were resumed. No application source changed and no check was disabled. The successful terminal result and test XML counts were observed during the run.

## Initial combined validation

- Host: 29 selected Rust library tests passed; 1728 tests were excluded by selection. Nine wire helper tests passed. The wire target compiled. This was not a full workspace test run.
- Kotlin settings writer: 8 tests passed, no failures, errors or skipped tests. The unit test used `-Pripdpi.skipNativeBuild=true`.
- Workspace Clippy with all targets and `-D warnings`, `just ci-gates`, architecture health `--check`, and locked Cargo metadata passed. Existing Cargo manifest warnings are separate from Clippy findings.
- Linux: 22 tests passed, no failures or ignored tests: four privileged option tests, four platform outcome tests, four runtime receipt tests, one production proxy loopback test, and nine wire helper tests. The Linux wire target compiled without capture execution.
- Linux used Rust 1.100.0, UID 65534, `CapEff=0`, `--cap-drop ALL`, and `--network none`. All Linux Cargo commands used `--locked --offline`. The tested source hashes matched the combined repair tree before rebase.

The disposable Linux image initially lacked `make`. The native-only source snapshot also lacked the unchanged `geosite.proto` build input. Both environment issues were corrected. Only remaining stages were resumed; these failed setup attempts were not counted as passing tests.

## Independent review and integration

The read-only `pr-reviewer` pass covered admission, numeric conversions, settings, outcomes, privacy, runtime completion, YAML rejection and the wire analyzer. Applied outcomes were moved to INFO. The wire assertion now checks every measured positive window. ENOPROTOOPT is unsupported while its original error is preserved. The final module extraction keeps the public `WsizeConfig` path. No blocking review findings remain.

Rebase onto `5fb68930e8` had one source conflict: two tests appended to `egress/tests.rs`. Both the incoming IPv6 validation test and the window admission test were retained. The reviewer checked the resolution and observed a clean `git diff --check`.

Every repair commit passed its hooks. The first documentation attempt exposed an inconsistent partial-staging tree. Subsequent commits isolated all own unstaged changes with recorded stashes. No hooks, gates or baselines were disabled.

## Commands and local evidence

The final host batch ran through `build-gate --lane ripdpi`, with Rust 1.100.0, four build workers and a worktree-specific Cargo target. It ran:

```bash
cargo nextest run --locked --manifest-path native/rust/Cargo.toml --lib \
  -p ripdpi-config -p ripdpi-proxy-config -p ripdpi-proxy-runtime-adapter \
  -p ripdpi-proxy-runtime -p ripdpi-runtime-platform -p ripdpi-desync-runtime \
  -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-strategy-config \
  -p ripdpi-tunnel-intercept --test-threads 4 \
  -E 'test(window) | test(wsize) | test(tcp_route_connect_settings_keep_buffer_hint) | test(tcp_route_connect_settings_do_not_wrap) | test(invalid_ipv6_extension_type_does_not_create_a_direct_action)'
cargo test --locked --manifest-path native/rust/Cargo.toml \
  -p ripdpi-proxy-runtime --test wire_e2e support::wire::tests
cargo test --locked --manifest-path native/rust/Cargo.toml \
  -p ripdpi-proxy-runtime --test wire_e2e --no-run
cargo clippy --locked --manifest-path native/rust/Cargo.toml \
  --workspace --all-targets -- -D warnings
env -u CARGO_BUILD_JOBS -u CARGO_INCREMENTAL ./gradlew :core:engine:testDebugUnitTest \
  --tests com.poyka.ripdpi.core.RipDpiProxyUIPreferencesSettingsWriterTest \
  -Pripdpi.skipNativeBuild=true --max-workers=4 --console=plain
just ci-gates
python3 scripts/ci/check_architecture_health.py --check
cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1 --no-deps
```

The script used the repository `cargo-guarded.sh` for Cargo commands. Linux exercised these filters: `tcp_window_clamp`, `rcvbuf`, `window_options_reject_signed_wrap_without_changing_the_socket`, `socket_options`, `actions_window_clamp`, `failed_window_option_is_not_a_completed_action`, and `window_clamp_with_split_desync_delivers_payload`.

The temporary raw logs, Linux source receipt and test XML were present when their results were inspected. Before integration, these temporary outputs were no longer available. The cause was not established. This file preserves the commands and observed results; it is not a reconstructed raw log bundle. Source commits and the independent review remain available. Pushed CI is a separate evidence gate.

## Evidence limits

| Level | Established scope |
|---|---|
| C | Reviewed source and all six repair requirements |
| V | Selected host tests, real Linux socket readback, production loopback byte delivery, settings round trips, analyzer tests and target compilation |
| S | The earlier DPI-32 audit checked the relevant RFC/socket distinction; these repair tests add no full TCP conformance claim |
| E | No Android/vendor or external-network compatibility was established |

Raw packet capture, root activation, physical-device probes and external-server probes were not run. Socket readback is not wire capture. Analyzer tests are not live packet evidence. Exact Window Scale control, root packet rewriting parity and a per-round window controller remain outside this repair scope. Sustained external throughput and vendor-specific limits remain acceptance checks.
