---
title: "Stop ExitIpSessionGuard::drop panicking on a poisoned mutex"
type: task
status: todo
area: proxy
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

The 2026-06-10 Rust API audit flagged a panic-in-Drop. `ripdpi-proxy-runtime/src/exit_ip_cap.rs:117` — `ExitIpSessionGuard::drop()` calls `self.counts.lock().expect("exit-ip session counts mutex poisoned")`. A panic raised from `Drop` during unwinding is a process `abort` on stable Rust. The module's own doc comment states these caps are "advisory throughput shaping, not a security boundary", so a poisoned counter on drop must never be allowed to take down the process. Related sites: lines 91 and 104 use the same `.expect()` on the counts mutex.

## Proposed change

1. In `Drop`, replace `.expect(...)` with a non-panicking form: `if let Ok(mut counts) = self.counts.lock() { /* decrement */ }`. A poisoned advisory counter on drop is silently ignored.
2. Review lines 91 and 104 (`try_acquire` / release paths): decide per-site whether poison should propagate as an error or be recovered with `lock().unwrap_or_else(|e| e.into_inner())`. The decrement/cap accounting is advisory, so recovery is appropriate.
3. Confirm no other `Drop` in `ripdpi-proxy-runtime` holds a `.expect()`/`.unwrap()` on a lock (sweep for the sentinel in `llm-rust-prompts.md`).

## Acceptance criteria

- [ ] PR confirms current state at `exit_ip_cap.rs:117` (and 91, 104).
- [ ] `ExitIpSessionGuard::drop` cannot panic — poisoned-lock path is recovered or ignored.
- [ ] Test: a guard whose mutex was poisoned by a panicking holder drops cleanly without abort (simulate poison, assert no panic).
- [ ] `cargo nextest run -p ripdpi-proxy-runtime --locked` green; clippy clean.

## Risks / open questions

- Poison recovery via `into_inner()` keeps using a possibly-inconsistent counter — acceptable since the cap is advisory; document the choice in a comment.

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, item 3 / N8).
- `.claude/rules/llm-rust-prompts.md` — `impl Drop` + `.unwrap()`/`.expect()` sentinel.
- Sibling task `enforce-per-exit-ip-concurrent-tls-cap` (introduced this module).
