---
title: Replace libc::daemon and fcntl PID lock with daemonize crate in ripdpi-proxy-runtime
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: consolidate-rust-manual-implementations-with-vendored-deps
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Replace libc::daemon and fcntl PID lock with daemonize crate in ripdpi-proxy-runtime #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Summary

`ripdpi-proxy-runtime/src/process.rs` calls `libc::daemon(0, 0)` and uses `fcntl(F_SETLK)` for PID file locking — 3 `unsafe` blocks. The `daemonize = "0.5"` crate covers both: double-fork, PID file creation with exclusive lock, stdout/stderr redirect, working directory.

## Implementation steps

1. Add `daemonize = "0.5"` to `[workspace.dependencies]`.
2. Add it to `ripdpi-proxy-runtime/Cargo.toml`.
3. Replace the daemonize block in `process.rs`:
   ```rust
   Daemonize::new()
       .pid_file(&pid_path)
       .working_directory("/")
       .start()?;
   ```
4. Delete the `libc::daemon`, `fcntl`, and manual `F_SETLK` struct construction.
5. Verify the `--daemonize` CLI path works end-to-end on a Linux host or emulator.

## Acceptance criteria

- [ ] `daemonize` in `[workspace.dependencies]`.
- [ ] `libc::daemon` and `fcntl(F_SETLK)` calls deleted from `process.rs`.
- [ ] 3 `unsafe` blocks in `process.rs` reduced to 0 (signal handler `unsafe` is tracked separately).
- [ ] `cargo nextest run -p ripdpi-proxy-runtime` passes.
- [ ] `cargo clippy -p ripdpi-proxy-runtime` no warnings.
