---
title: Replace manual SCM_RIGHTS fd passing with nix ControlMessageOwned::ScmRights
type: task
status: backlog
area: rust-native
priority: high
owner: unassigned
parent: consolidate-rust-manual-implementations-with-vendored-deps
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Replace manual SCM_RIGHTS fd passing with nix ControlMessageOwned::ScmRights #repo/RIPDPI #area/rust-native #status/backlog ⏫

## Summary

`ripdpi-root-helper-protocol/src/scm_rights.rs` contains 17 `unsafe {}` blocks that manually assemble `msghdr`, traverse `CMSG_FIRSTHDR`/`CMSG_NXTHDR`, and call `ptr::read_unaligned` to extract passed file descriptors. `nix` (already a workspace dep) provides `nix::sys::socket::recvmsg` and `ControlMessageOwned::ScmRights` that eliminate all of this safely.

## Implementation steps

1. In `ripdpi-root-helper-protocol/Cargo.toml` ensure `nix` is declared with features `["socket", "uio", "cmsg"]`.
2. Rewrite `recv_with_fd` in `scm_rights.rs`:
   - Call `nix::sys::socket::recvmsg::<()>(fd, &mut iov, Some(&mut cmsg_buf), MsgFlags::empty())`.
   - Match on `ControlMessageOwned::ScmRights(fds)` to extract the passed fd.
3. Rewrite `send_with_fd`:
   - Build `ControlMessage::ScmRights(&[fd])` and call `nix::sys::socket::sendmsg`.
4. Delete all remaining `unsafe {}` blocks in the file; add `#![forbid(unsafe_code)]` if the file is the only remaining unsafe site in the crate.
5. Verify with `cargo nextest run -p ripdpi-root-helper-protocol -p ripdpi-root-helper`.

## Acceptance criteria

- [ ] `scm_rights.rs` has ≤2 `unsafe` blocks (only if unavoidable raw fd coercions remain).
- [ ] `cargo nextest run -p ripdpi-root-helper-protocol` passes.
- [ ] Manual `CMSG_*` traversal deleted.
- [ ] No change to the IPC protocol wire format.
