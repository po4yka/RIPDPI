# Change: Repair relay configuration contracts

Task ID: `RLY-1794335295387247`

## Why

The RLY-19 audit found that explicit native activation can retain an Xray provider, invalid Trojan and AnyTLS ports can be saved, Apps Script can bypass the native schema gate, and an unknown Reality transport can select TCP. Independent round trips do not protect the complete language boundary.

## What Changes

- Select Native atomically on explicit native activation.
- Reject invalid imported native endpoints before persistence.
- Require schema 10 for each JNI relay kind and reject unknown Reality transports.
- Add regression checks at the actual contract boundaries and resolve the audit hypotheses from evidence.
- Keep the existing supported mode set and stored data. No breaking schema change.

## Capabilities

### New Capabilities

- `relay-config-boundary`: Consistent relay activation, validation, native decoding, and contract checks.

### Modified Capabilities

- None.

## Impact

- Affects app activation, data validation, service profile reads if a race is confirmed, relay-core and relay-android config logic, owning tests, and contract documentation.
- No new dependency, provider, protocol, locale, stored field, or schema version.
