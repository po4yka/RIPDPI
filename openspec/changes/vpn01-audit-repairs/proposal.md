# Change: Repair VPN ownership and packet I/O failure handling

Task ID: `VPN-1794133736183612`

## Why

A destroyed VPN instance can finish cleanup after a new instance acquires the
same process resources. The VPN-01 audit reproduced loss of route observation.
The audit also identified terminal TUN I/O paths that need reproduction and
explicit cleanup guarantees.

## What Changes

- Prevent a new VPN instance from acquiring shared resources while predecessor
  cleanup remains incomplete. A bounded start wait may fail; a later start can retry.
- After reproduction, stop the packet loop on terminal read/readiness errors
  and retire owned sessions on write errors.
- Keep ambiguous legacy physical observations unavailable. Do not select a
  network from VPN overlay state or invent direct-DNS authority.
- Describe the current repository-owned packet-fd implementation.
- No breaking JNI, protobuf, storage, wire, or configuration change is planned.

## Capabilities

### New Capabilities

- `vpn-runtime-failure-ownership`: predecessor cleanup and terminal packet I/O ownership.

### Modified Capabilities

- None. Existing physical-network-handover-authority ambiguity rules stay in force.

## Impact

- `:core:service` VPN service lifecycle and regression tests.
- `ripdpi-tunnel-core` packet loop and regression tests.
- Native tunnel documentation. No new dependency or root requirement.
- Host evidence does not establish Android routing or external compatibility.
