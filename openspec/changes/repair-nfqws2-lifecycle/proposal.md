# Change: Repair NFQUEUE backend lifecycle and ownership

Task ID: `VPN-1794158885036621`

## Why

The VPN-10 audit found missed helper failure, lost stale cleanup, process and firewall ownership violations, and incomplete compressed Lua path checks. These defects can leave a session without its requested packet handler or change another instance's state.

## What Changes

- Report a dead helper when backend monitoring starts.
- Keep failed stale cleanup pending and retry it before activation.
- Stop only verified predecessor processes of this private app directory.
- Use unambiguous firewall chain names and safely recognize owned legacy chains.
- Validate the file that upstream selects, including the compressed Lua fallback.
- Preserve explicit root activation, YAML incompatibility rejection, and NFQUEUE bypass policy.
- No public configuration, IPC, JNI, or protobuf schema changes are planned.

## Capabilities

### New Capabilities

- `nfqws2-session-ownership`: Root backend failure reporting, cleanup ownership, and file validation.

### Modified Capabilities

- None.

## Impact

- `:core:service` root-helper management and tests.
- `ripdpi-root-helper` synchronous process and firewall supervisor and tests.
- Internal firewall chain names change; owned legacy state requires safe recovery.
- No dependency, baseline, upstream source, or non-root data-plane change.
