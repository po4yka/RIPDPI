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
- [ ] OUT-1786264762917966 SshProfileScreen validates host, port, user, and auth selection. Private key is stored via EncryptedFile; never SharedPreferences. (Validation done. Persistence: all profile editors — SSH, AnyTLS, AmneziaWG — are preview-only by design (@H… #feature @item:OUT-1786264762917254
- [ ] OUT-1786264762917540 Host key fingerprint is surfaced on first connect with explicit accept / reject action. (deferred: no connect-from-editor path exists; the Rust SshError::HostKeyUntrusted is config-driven with no runtime accept/reject channel, so a connect… #feature @item:OUT-1786264762917254
- [x] OUT-1786264762917012 Passphrase and private-key material are redacted in all diagnostic surfaces #feature @item:OUT-1786264762917254

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
