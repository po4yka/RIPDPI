# Change: Preserve WebTunnel transport argument boundaries

Task ID: `TRN-1794489808420764`

## Why

TUN-09 F4 found that decoded PT arguments are serialized as a bridge line and split again. Spaces and escaped delimiters can change the configuration. F1-F3 were already repaired on main by the TUN-10 work.

## What Changes

- Preserve decoded key and value boundaries through semantic validation.
- Use the first repeated value, as pinned goptlib does, and reject malformed entry structure.
- Keep unknown extension keys opaque and retain the supported WebTunnel options.
- BREAKING: malformed empty entries and repeated keys no longer use the former lenient or last-value behavior.

## Capabilities

### New Capabilities

- `webtunnel-arguments`: Preserve the PT argument grammar at WebTunnel configuration admission.

### Modified Capabilities

- None.

## Impact

The ripdpi-webtunnel crate and its tests. No new dependency, public schema, JNI, persisted data or UI change.
