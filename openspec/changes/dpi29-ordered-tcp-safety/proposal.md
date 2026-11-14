# Change: Fix ordered TCP safety and ordering contracts

Task ID: `RST-1794677660326601`

## Why

Large raw spans can fail after an earlier packet leaves. Unsupported exact modes can send a real prefix through another executor. The hostfake default description is incomplete.

## What Changes

- Validate whole ordered batches before emission.
- Select the exact executor for custom order and sequential mode.
- Document and test existing hostfake layouts.
- No breaking changes or schema changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `tcp-fake-send-safety`: size bounds and exact-mode dispatch.
- `hostfake-contracts`: the default/duplicate exception.

## Impact

Privileged operations, desync runtime, owning tests and documentation. No JNI, protobuf, dependencies or deployment.
