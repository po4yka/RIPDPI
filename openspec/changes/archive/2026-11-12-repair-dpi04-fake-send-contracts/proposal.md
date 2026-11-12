# Change: Repair fake TCP send contracts

Task ID: `ENG-1794501890187395`

## Why

The DPI-04 audit found unsafe error exits, lost replay state, incorrect genuine
packet modifiers, and input and helper limits that reject valid operations.
These faults can change the real stream or report an incomplete operation as
safe to retry.

## What Changes

- Apply group activation before entropy changes.
- Restore queued real bytes before normal TTL on every fake-send exit.
- Prevent fallback or replay after a possible payload commit.
- Keep genuine TTL and MD5 independent from fake modifiers.
- Parse malformed inline bytes without panic and use modulo TCP sequence arithmetic.
- Admit bounded bulk fake requests only after the helper advertises its limits.
- Reject unsupported IPv4 zero-ID operations before packet emission.

## Capabilities

### New Capabilities

- `tcp-fake-send-safety`: fake-send activation, stream preservation, replay safety, and bounded helper requests.

### Modified Capabilities

- None.

## Impact

- Rust config, desync runtime, runtime platform and adapter, privileged operations,
  root-helper protocol, and root-helper dispatch.
- Helper preflight gains additive limits. Existing v3 request encoding remains.
- BREAKING: IPv4 zero-ID requests now return an explicit unsupported capability
  error on the IP_HDRINCL backend instead of silently producing a different ID.
- No new production dependencies, backend services, root activation, or changes
  to golden fixtures are required.
