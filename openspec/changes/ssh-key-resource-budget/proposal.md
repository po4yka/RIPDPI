# Change: Bound SSH private-key decoding work

Task ID: `RLY-1794325389593996`

## Why

H1 reproduced CPU-bound key decryption inside the SSH constructor task. A local encrypted OpenSSH key with 64 bcrypt rounds stalled a current-thread runtime for 1205 ms. Cancellation could not interrupt the KDF. Arbitrary key cost can delay runtime work and shutdown.

## What Changes

- BREAKING: Reject private keys above explicit size and KDF cost limits before connection work starts. Keep parameters and pinned crypto versions unchanged.
- Run admitted private-key decoding outside Tokio workers, with one global decode slot.
- Retain ownership and join admitted decoding before close completes. Cancellation while queued starts no KDF.
- Preserve password, plain-key, and permitted encrypted-key authentication.

## Capabilities

### New Capabilities

- `ssh-key-resource-budget`: bounded admission and owned decoding for SSH private keys.

### Modified Capabilities

- None.

## Impact

- `ripdpi-ssh` and the existing vendored russh key decoder; no wire, JNI, storage, dependency, or baseline changes.
- Expensive keys need an explicit local conversion by their owner. No automatic re-encryption or parameter reduction occurs.
