## Context

Task ID: `CIC-1792987990860435`. The contract mirror changes one test resource, but full client CI also builds WebTunnel and runs Simple Home tests. Existing library code requires `tokio::select!`; the normal Tokio dependency omits `macros`. UI tests use an old double-click confirmation and old label case.

## Goals / Non-Goals

- Goal: compile the existing library and exercise current UI actions without weakening assertions.
- Non-goal: change runtime UI, transport code, versions, CI routing, golden images, or the contract mirror.

## Decisions

- Enable `macros` on the existing normal Tokio dependency. A non-test build must not depend on dev-feature unification.
- Press the actuator once, assert the confirmation control, then click that control. Keep the callback-count assertion.
- Select report sharing through its existing typed test tag. These tests own enabled state and dispatch; resource text and snapshots own copy.

## Contracts and ownership

- This worktree owns only the WebTunnel Cargo feature line, Simple Home test helpers/selectors, and this task's records.
- No wire, JNI, protobuf, persistence, locale, or serialized shared-file contract changes. Keep `Cargo.lock` unchanged.

## Risks / Trade-offs

- Test builds can conceal a missing normal feature. Check the library without test targets and require hosted packaging.
- A selector can conceal an interaction regression. Retain dialog visibility, enabled/disabled states, and exact callback assertions.

## Migration Plan

Run locked non-test WebTunnel checking, the complete Simple Home test class, task validation, and architecture checks. Review and publish a separate PR. Merge only after hosted checks pass. Revert the focused commit to roll back; no data migration or compatibility break applies.
