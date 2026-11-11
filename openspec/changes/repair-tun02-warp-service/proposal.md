# Change: Repair WARP profile setup and runtime integrity

Task ID: `TRN-1794384538458949`

## Why

The TUN-02 audit found false runtime readiness, off-VPN protection failures,
stale endpoint reuse, incompatible response parsing, false account state,
incomplete Zero Trust imports, and no user entry point for profile setup.
These defects prevent reliable use of the supported WARP WireGuard path.
The first repair test run also reproduced a stack overflow in the endpoint
probe worker. This additional finding needs its own fix commit.

## What Changes

- Require peer authentication for WARP readiness and report peer failure.
- Keep endpoint probe packet buffers on the heap for small worker stacks.
- Preserve active VPN protection while allowing normal operation outside VPN.
- Resolve endpoints independently of account refresh and use the current network.
- Normalize provider endpoints and accept GET responses without a new token.
- Keep local license data separate from the account state returned by the server.
- Reject incomplete Zero Trust profiles before activation.
- Add explicit profile setup actions with loading, cancellation, and errors.
- BREAKING: incomplete local Zero Trust imports no longer become provisioned.

## Capabilities

### New Capabilities

- `warp-service-integrity`: authenticated runtime readiness, profile setup,
  truthful account state, and network-scoped endpoint selection.

### Modified Capabilities

- None. Existing WireGuard and AmneziaWG contracts remain separate.

## Impact

- Native WARP core and Android adapter; Kotlin service, data, engine API, and UI.
- The internal setup contract gains an endpoint. A keypair JNI operation uses
  existing native dependencies to support Android API 27.
- No new production dependency, backend, remote license activation, browser
  enrollment, MASQUE support, or real-account acceptance is included.
