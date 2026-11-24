# OUT-1786264762917254: Add SSH outbound client crate and profile editor

## Objective

Add SSH outbound client crate and profile editor

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [x] OUT-1786264762917104 ripdpi-ssh crate compiles with a maintained SSH crate dependency (evaluate russh, thrussh successors) #feature @item:OUT-1786264762917254
- [x] OUT-1786264762917444 Password and OpenSSH private-key auth both supported #feature @item:OUT-1786264762917254
- [x] OUT-1786264762917938 Host-key verification is on by default; "trust on first use" is a per-profile opt-in #feature @item:OUT-1786264762917254
- [x] OUT-1786264762917617 direct-tcpip forwarding to arbitrary target host:port works for TCP; UDP is out of scope for v1 #feature @item:OUT-1786264762917254
- [x] OUT-1786264762917966 Verify editor validation and the Keystore-encrypted atomic profile persistence contract #feature @item:OUT-1786264762917254
- [x] OUT-1786264762917540 Verify that the editor lets the user accept or reject the observed host fingerprint before saving an unpinned profile #feature @item:OUT-1786264762917254
- [x] OUT-1786264762917012 Passphrase and private-key material are redacted in all diagnostic surfaces #feature @item:OUT-1786264762917254

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
