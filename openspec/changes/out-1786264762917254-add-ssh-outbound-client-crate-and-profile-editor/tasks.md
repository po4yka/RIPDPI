# OUT-1786264762917254: Add an interactive SSH host-key trust flow

## Objective

Let a default-created SSH profile complete first connection through an explicit observed-host-key accept or reject decision.

## Ownership

- `native/rust/crates/ripdpi-ssh/**`
- Kotlin/JNI runtime event and profile trust persistence paths

## Execution

- [x] OUT-1786264762917104 Implement the maintained russh-backed outbound crate #feature @item:OUT-1786264762917254
- [x] OUT-1786264762917444 Support password and OpenSSH private-key authentication #feature @item:OUT-1786264762917254
- [x] OUT-1786264762917938 Enforce host-key verification by default #feature @item:OUT-1786264762917254
- [x] OUT-1786264762917617 Implement direct-tcpip forwarding #feature @item:OUT-1786264762917254
- [x] OUT-1786264762917966 Validate SSH profiles and keep credentials in the Keystore-backed path #feature @item:OUT-1786264762917254
- [ ] OUT-1786264762917540 Surface the observed SSH host key on first connect and require explicit accept or reject before persistence #feature !high @item:OUT-1786264762917254
- [x] OUT-1786264762917012 Redact passphrase and private-key material #feature @item:OUT-1786264762917254

## Verification

- focused Rust host-key policy tests
- Kotlin/JNI event, accept, reject, persistence, and changed-key regression tests
