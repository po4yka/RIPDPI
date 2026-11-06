# Change: Generate settings JSON serialization

Task ID: `DAT-1793954543063965`

## Why

Settings snapshots repeat each field in a model, a JSON writer, and a JSON reader. Invalid scalar values can silently become defaults, and invalid list elements can become strings or disappear. A damaged backup can therefore change settings without reporting an error.

## What Changes

- Use generated serializers for the existing flat settings JSON contract.
- Keep valid version 1 settings, defaults, unknown-key tolerance, and credential exclusions.
- BREAKING: reject malformed known values that were previously coerced or replaced with defaults. Enum inputs use their declared wire names.
- Refuse an invalid settings snapshot before backup restore changes stored data.

## Capabilities

### New Capabilities

- `settings-json-codec`: typed settings backup serialization and validation.

### Modified Capabilities

- None.

## Impact

- `:core:data:settings` snapshot codec and `:core:data` backup tests.
- Existing kotlinx.serialization dependency; no new production dependency.
- No protobuf, native wire, UI, device, or deployment change.
