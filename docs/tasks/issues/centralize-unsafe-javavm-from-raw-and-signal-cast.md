---
title: "Centralize JavaVM::from_raw behind a SharedJvm newtype and fix root-helper signal cast"
type: task
status: todo
area: rust-native
priority: medium
owner: unassigned
parent: epic-june-2026-audit-remediation
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-06-10
source_wiki_pages: []
linked_task: null
---

## Motivation

The 2026-06-10 unsafe-code audit reported 72 unsafe blocks, **0 missing SAFETY comments, 0 HIGH-risk sites** — a healthy baseline. Two MEDIUM items are worth closing structurally:

1. **`JavaVM::from_raw` double-owner pattern (6 sites)** — `ripdpi-android-vpn-protect-adapter/src/lib.rs:38`, `ripdpi-warp-android/src/vpn_protect.rs:111`, `ripdpi-warp-android/src/readiness.rs:64`, `ripdpi-relay-android/src/readiness.rs:62`, `ripdpi-android-proxy-adapter/src/readiness.rs:76`, `ripdpi-tunnel-android/src/flow_attribution.rs:186`. Each wraps a raw `*mut sys::JavaVM` into a second `JavaVM`. Sound today (clones live in Arc-backed callbacks unregistered before teardown; jni 0.22.4 `JavaVM` has no `Drop`), but the no-double-`DestroyJavaVM` invariant is not compiler-enforced — a future refactor storing a clone in a `'static` without an unregister path makes it reachable. The SAFETY comments also vary in depth across sites.

2. **`libc::signal` double-cast** — `ripdpi-root-helper/src/main.rs:88`: `signal_handler as *const () as libc::sighandler_t`. The intermediate `*const ()` hop carries incorrect provenance under strict-provenance / Tree Borrows. Handler body is async-signal-safe (only `AtomicBool::store`), so no UB today, but it is non-portable.

## Proposed change

1. Define a `SharedJvm(Arc<JavaVM>)` newtype with a single constructor taking `&JavaVM` (copies the pointer once, wraps in `Arc`). Migrate all 6 sites to `SharedJvm::new(&vm)`; centralize the SAFETY rationale in one place. Add a compile-fail regression preventing storage of a naked `JavaVM` outside `Arc`.
2. Replace the root-helper cast with `nix::sys::signal::signal(Signal::SIGTERM, SigHandler::Handler(signal_handler))` (handles the ABI cast internally), or at minimum the direct `signal_handler as libc::sighandler_t` form without the `*const ()` hop.
3. (Optional, low) strengthen the `from_raw_parts` SAFETY comment at `icmp_wrapped_udp.rs:51` with a `MaybeUninit<u8>` == `u8` layout-identity assertion.

## Acceptance criteria

- [ ] PR confirms current state at the 6 `from_raw` sites and `root-helper/src/main.rs:88`.
- [ ] All 6 sites route through one `SharedJvm` constructor with a single auditable SAFETY block.
- [ ] Root-helper signal registration no longer uses the `*const ()` double-cast.
- [ ] `unsafe-code-auditor` re-run reports 0 missing SAFETY comments and no new HIGH/MEDIUM.
- [ ] `cargo nextest run --locked` green for affected crates; clippy clean.

## Risks / open questions

- This is a refactor of working code — keep behavior identical; the value is structural enforcement, not a bug fix. Per `llm-rust-prompts.md`, any unsafe-touching diff needs a `pr-reviewer` pass before commit.

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, item 10 / M1, M2, M3).
- `rust-unsafe` skill; `.claude/rules/llm-rust-prompts.md` diff-acceptance gate (unsafe block).
