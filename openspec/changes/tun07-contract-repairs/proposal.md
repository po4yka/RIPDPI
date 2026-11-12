# Change: Repair obfs4 relay and managed transport contracts

Task ID: `RLY-1794477185649013`

## Why

The TUN-07 audit found an exposed standalone obfs4 relay without a Tor stream layer and a permission parser that rejects valid Arti bridge identities. Managed PT startup and socket stop also need explicit ownership and completion rules.

## What Changes

- BREAKING: new relay selection omits standalone obfs4; legacy standalone configurations fail with guidance to use Tor with an obfs4 bridge. Stored IDs remain intact.
- Valid Tor bridge identities reach the existing Arti path unchanged.
- Managed PT readiness waits for version and method completion, preserves failure causes, and accepts only bounded loopback control endpoints.
- Stop closes owned sockets and prevents late admission. PT arguments escape equals signs.

## Capabilities

### New Capabilities

- `managed-pt-contract`: local adapter readiness, parameters and resource ownership.

### Modified Capabilities

- None.

## Impact

App relay selection and core service validation, managed subprocess startup and SOCKS adapter. No dependency, protobuf, JNI or storage schema changes. VPN rejection and Tor trust checks remain intact. External bridge and physical-device acceptance are separate evidence.
