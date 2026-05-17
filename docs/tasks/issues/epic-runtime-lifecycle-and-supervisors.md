---
title: Epic - Runtime lifecycle and supervisors
type: epic
status: todo
area: epic
priority: critical
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-23
---

- [ ] #task Epic - Runtime lifecycle and supervisors #repo/RIPDPI #area/epic #status/todo 🔺

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `epic-runtime-lifecycle-and-supervisors`
- **Verify:** `all child rows in GOAL_LEDGER.md are DONE or BLOCKED`
- **Scope (only modify these + this file + the ledger):** _epic — coordination only; child tasks carry the file scope_
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Goal

Make runtime lifecycle explicit and deterministic. Replace poll-and-guess startup with native readiness events, move exit-cause semantics into the supervisors themselves (sealed type, caller-independent), and decouple the JNI wrappers' handle-lifetime locking from their ordinary telemetry path.

## Why now

Three supervisors today fire `onUnexpectedExit` on every completion, with correctness resting on a caller-owned `stopping` flag. Telemetry errors are collapsed via `runCatching { ... }.getOrNull()`. JNI wrappers poll every 50 ms to detect readiness, holding a coarse mutex while they do. Every future runtime change has to navigate this. Fixing it now is the last chance to do it cheaply.

## Key decisions

- **Sealed `ExitCause`** replacing the flag-based approach: `ExpectedStop`, `Crash(code)`, `StartupFailure(throwable)`, `Cancellation`. Each supervisor produces exactly one per run.
- **Two locks in JNI wrappers**: one for handle lifetime (create/destroy serialization), one for ordinary telemetry/config against a live handle. Telemetry no longer head-of-line-blocks lifecycle.
- **Native event channel** for readiness. Design spike decides JNI callback vs eventfd/pipe surfaced through JNI.
- **Typed telemetry results** — no more `getOrNull()`-into-void. Engine errors surface, "no data yet" stays distinct from "engine failed."

## Scope

- **In scope:** `AppStartupInitializer`, three runtime supervisors (proxy / upstream relay / warp), JNI wrappers (`RipDpiProxy`, `RipDpiRelay`), native readiness events.
- **Out of scope:** runtime feature work, protocol changes, UI reporting beyond what the new types make possible.

## Ship definition

- [ ] Expected vs unexpected exit is observable from the supervisor's output alone — callers no longer maintain a `stopping` flag.
- [ ] `pollTelemetry()` call sites produce typed results; no `getOrNull()` remains on those paths.
- [ ] Startup failure in one subsystem does not mask the others; the startup report is structured per-subsystem.
- [ ] Native readiness latency measured before/after; 50 ms polling loop removed.
- [ ] No behavior regression in existing supervisor/lifecycle tests.

## Child tasks

**Startup**
- [[Split AppStartupInitializer failure domains]]

**Supervisor exit semantics**
- [[Add explicit supervisor exit cause types]]
- [[Type-safe pollTelemetry results]]

**JNI wrappers**
- [[Decouple JNI handle-lifetime and telemetry locking]]
- [[Add native readiness events to RipDpi wrappers]]

Child tasks roll up via the TaskNotes relationships view on this note.

## Dependencies

- Unblocks: [[Add repeated startup-shutdown supervisor test]] under [[Epic - Orchestration test posture]] (needs scripted exit causes).

## Risks / open questions

- JNI callback model vs pollable fd: spike before implementation. Thread-ownership concerns differ materially.
- Avoid a lock-hierarchy regression when splitting handle-lifetime from telemetry locks — document the acquisition order.

## Links

- [[ripdpi-android]]
- [[ripdpi-android-audit-2026-04-20]] §4, §5, §6, Highest-ROI #2
- Child issues: 4
