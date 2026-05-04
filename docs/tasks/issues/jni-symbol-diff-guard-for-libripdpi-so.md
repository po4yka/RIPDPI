---
title: JNI symbol diff guard for libripdpi.so
type: task
status: doing
area: rust-native
priority: high
owner: Senior Rust Native Engineer
parent: null
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-04
---

- [ ] #task JNI symbol diff guard for libripdpi.so #repo/RIPDPI #area/rust-native #status/doing ⏫

Owner: Senior Rust Native Engineer (with Senior Android Engineer review).

Context
ripdpi-android Cargo.toml dropped direct deps on ripdpi-desync, ripdpi-packets, and ripdpi-session. If any JNI export referenced those crates through cfg-gated paths, a release build could silently lose a symbol that Kotlin loads via System.loadLibrary, producing UnsatisfiedLinkError at runtime.

Acceptance criteria
- Checked-in expected JNI export list for libripdpi.so (release, per ABI).
- CI step diffs actual symbol list (nm/llvm-nm/objdump) against expected list and fails on any drop or unintended addition.
- Regen procedure documented in build/CI docs.
- Read-only inspection of release artifact; no signing-config changes.

Definition of done
PR merged; symbol-diff job green; Senior Android Engineer signs off that all Kotlin-loaded symbols are present.
