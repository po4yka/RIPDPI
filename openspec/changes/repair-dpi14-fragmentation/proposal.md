# Change: Repair TCP IP fragmentation contracts

Task ID: `RST-1794557321446019`

## Why

The DPI-14 audit found nine defects in TCP fragmentation validation, packet emission, error progress, round gates, IPv6 profile transport, helper errors, privilege and MTU fallback, IPv4 identification, and packet length checks. A valid configuration can duplicate application bytes or report that no bytes were sent after a partial send.

## What Changes

- Reject other TCP send steps beside ipfrag2 and preserve TLS preludes.
- Enforce round-one and internal-offset eligibility at runtime.
- Keep TCP queue state consistent with emitted fragments and report uncertain sends.
- Preserve IPv6 profiles, fragment order, and typed helper failures.
- Select safe fallback only before emission; retain VPN protection failures.
- Reserve fragment IDs by the IP reassembly tuple and enforce complete packet limits.
- BREAKING: require matching version-four helper and app IPC implementations.

## Capabilities

### New Capabilities

- `tcp-ip-fragmentation`: Validated TCP fragmentation and safe failure semantics.

### Modified Capabilities

- None. Existing source contracts are documented by the new focused capability.

## Impact

Kotlin chain validation and root-helper IPC callers; Rust config, planner, desync runtime, runtime platform, privileged operations, helper protocol and packet builders. No new dependencies, backend, root activation, or external probes. Each finding receives a separate implementation commit.
