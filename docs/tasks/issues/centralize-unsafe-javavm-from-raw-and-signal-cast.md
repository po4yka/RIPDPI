---
title: "Centralize JavaVM::from_raw behind a SharedJvm newtype and fix root-helper signal cast"
type: task
status: done
area: rust-native
priority: medium
owner: unassigned
parent: epic-june-2026-audit-remediation
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-06-14
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

- [x] PR confirms current state at the 6 `from_raw` sites and `root-helper/src/main.rs:88` (line numbers re-confirmed at HEAD `e1dabb3a8`).
- [x] All 6 sites route through one `SharedJvm` constructor with a single auditable SAFETY block.
- [x] Root-helper signal registration no longer uses the `*const ()` double-cast.
- [x] `unsafe-code-auditor` re-run reports 0 missing SAFETY comments and no new HIGH/MEDIUM.
- [x] `cargo nextest run --locked` green for affected crates (166 passed, 1 skipped); clippy clean (`-D warnings`).

## Resolution (2026-06-14, branch `worktree-unsafe-centralize`)

- `SharedJvm(Arc<JavaVM>)` lives in `android-support` (`src/shared_jvm.rs`) — the crate that
  already owns the L8 JNI helper surface and is depended on by the adapter crates.
  `ripdpi-android-vpn-protect-adapter` gained the one missing `android-support` edge
  (in the `ANDROID_JNI_DEPENDENCY_NAMES` allowlist; arch-health reports New indicators: 0).
- Scope grew from 6 to **8 sites across 6 crates**: the adversarial `pr-reviewer` /
  `unsafe-code-auditor` pass found `ripdpi-amneziawg-android` (`vpn_protect.rs`,
  `readiness.rs`) carrying the same inline `from_raw` shape — out of the original 6-site
  list but in scope (AmneziaWG runs through the WARP engine). Folded both in so
  `SharedJvm::new` is genuinely the single workspace `from_raw` call site
  (`rg 'JavaVM::from_raw' crates` → only `shared_jvm.rs`). amneziawg already depended on
  `android-support`, so no extra Cargo.lock churn.
- The single `unsafe { JavaVM::from_raw(..) }` site is `SharedJvm::new`. Worth recording: in
  jni 0.22.4 `from_raw` is already singleton-backed (`JAVA_VM_SINGLETON.get_or_init(..).clone()`)
  and `jni::JavaVM` has **no `Drop`**, so no `DestroyJavaVM` is reachable through any handle —
  the centralization is structural hardening, not a live-bug fix.
- Compile-fail regression: `const _: fn(SharedJvm) -> Arc<JavaVM> = |shared| shared.0;` plus a
  `Send + Sync + Clone` static assertion. Verified the guard fires on a naked field
  (`error[E0308]: ... expected Arc<JavaVM>, found JavaVM` at the guard line).
- Root-helper now uses `nix::sys::signal::signal(Signal::SIGTERM, SigHandler::Handler(signal_handler))`
  (typed function pointer, no `*const ()` provenance hop), with error logged rather than discarded.
- `icmp_wrapped_udp.rs`: added a `MaybeUninit<u8>` vs `u8` size/align `const` assertion and a
  strengthened SAFETY comment.

## Risks / open questions

- This is a refactor of working code — behavior identical; the value is structural enforcement,
  not a bug fix. Per `llm-rust-prompts.md`, the unsafe-touching diff went through an
  `unsafe-code-auditor` + `pr-reviewer` adversarial pass before commit.

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, item 10 / M1, M2, M3).
- `rust-unsafe` skill; `.claude/rules/llm-rust-prompts.md` diff-acceptance gate (unsafe block).
