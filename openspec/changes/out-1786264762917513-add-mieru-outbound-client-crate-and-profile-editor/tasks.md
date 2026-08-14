# OUT-1786264762917513: Add Mieru outbound client crate and profile editor

## Objective

Add Mieru outbound client crate and profile editor

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [ ] OUT-1786266573979348 Verify implemented Mieru TCP carrier against upstream or live-server reference vectors; self-consistency alone is insufficient #feature @item:OUT-1786264762917513
- [ ] OUT-1786266573979902 Resolve and verify the deferred Mieru UDP carrier requirement; the current TCP-only implementation remains incomplete #feature @item:OUT-1786264762917513
- [x] OUT-1786264762917550 Multiplexing implemented for low/middle/high (mux.rs): many sessionID-tagged sub-sessions share one carrier. A single serialized Encryptor keeps the per-direction nonce monotonic (nonce-reuse-safe under concurrent streams); a single reader… #feature @item:OUT-1786264762917513
- [x] OUT-1786264762917605 MieruProfileScreen validates server + port, username, password, protocol mode (TCP/UDP), mTU #feature @item:OUT-1786264762917513
- [x] OUT-1786264762917618 The replay key comes from a shared network-time source, never a direct device-clock read. Implemented the workspace's first network-time provider (ripdpi-network-time: monotonic-from-anchor with device-clock fallback), wired the relay faca… #feature @item:OUT-1786264762917513
- [x] OUT-1786264762917419 Credentials redacted in all diagnostic surfaces #feature @item:OUT-1786264762917513
- [x] OUT-1786264762917565 Subscription import path recognizes mieru:// URIs #feature @item:OUT-1786264762917513

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
