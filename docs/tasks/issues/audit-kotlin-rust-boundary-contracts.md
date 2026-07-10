---
title: Audit Kotlin-Rust boundary contracts
type: task
status: doing
area: engine
priority: critical
owner: Boundary audit coordinator
parent: null
blocks: []
blocked_by: []
created: 2026-07-10
updated: 2026-07-10
---

## Goal

Audit and harden the Kotlin-Rust boundary across JNI exports, native config JSON, AppSettings protobuf/DataStore persistence, relay and diagnostics schema versions, telemetry identifiers, and remembered-policy replay without renaming stable wire identifiers or changing existing-user defaults.

## Scope

- `core/engine`, `core/data/model`, `core/data/settings`, and boundary-consuming paths in `core/service`.
- `ripdpi-android*`, `ripdpi-proxy-config`, `ripdpi-config`, `ripdpi-relay-core`, `ripdpi-diagnostics-contracts`, and `ripdpi-telemetry`.
- Existing contract fixtures, golden harnesses, and regression tests. Golden fixtures and hook-protected baselines remain read-only unless the user separately authorizes a bless/update.

## Audit lane ownership

All specialist lanes are read-only. The boundary audit coordinator is the sole writer and owns every serialized high-risk file (`*.proto`, diagnostics `wire.rs`/`EngineContract.kt`, relay schema constants and registries, golden fixtures, baselines, and dependency locks).

| Lane | Owner | Read-only scope |
|---|---|---|
| JNI symbol/export audit | JNI symbol/export auditor | Kotlin `external fun` declarations and Rust `Java_*` exports |
| Native config JSON audit | Native config JSON auditor | Kotlin codecs and Rust proxy/config serde models |
| Protobuf/DataStore audit | Protobuf/DataStore compatibility auditor | `app_settings.proto`, serializers, repositories, migrations |
| Relay schema audit | Relay native schema auditor | Kotlin relay DTO/schema constants and `ripdpi-relay-core` |
| Diagnostics schema audit | Diagnostics wire schema auditor | Kotlin engine contract, Rust diagnostics wire, governance fixtures |
| Telemetry contract audit | Telemetry contract auditor | native event domains/kinds, projections, payload goldens |
| Remembered-policy replay audit | Remembered-policy replay auditor | persistence, matching, rewrite/replay, service consumers |
| Golden coverage audit | Golden-test author | existing fixture coverage and deterministic regression-test gaps |

## Ship definition

- Every Kotlin `external fun` in scope has exactly one matching Rust export and meaningful orphan exports are either removed safely or explicitly justified.
- Proxy, tunnel, relay, diagnostics, and telemetry contracts have deterministic tests for missing fields, unknown fields, supported legacy versions, and unsupported-version rejection where applicable.
- Remembered `proxyConfigJson` replay is regression-tested to preserve strategy semantics while refreshing volatile runtime context.
- Confirmed drift is fixed with backward-compatible defaults and no stable wire-key rename.
- The requested Python, Gradle, and focused Cargo gates pass with Cargo invoked using `--locked`; broader practical contract gates are recorded.
- A boundary contract report states migration compatibility, intentionally unfixed findings, residual risks, and exact files/commands.

## Work log

- 2026-07-10: Created the isolated audit worktree and recorded serialized-file ownership before specialist fan-out.
