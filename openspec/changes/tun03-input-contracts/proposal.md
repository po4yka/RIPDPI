# Change: Fix AWG profile input and compatibility regressions

Task ID: `TRN-1794384012167321`

## Why

Five TUN-03 audit findings let import change an IPv6 endpoint, let Kotlin accept a header key that native code rejects, or let unsupported input lose requested settings. Reject invalid input before activation and preserve supported profiles.

## What Changes

- Preserve IPv6 hosts through URI and configuration import.
- Require canonical Base64 header protection keys.
- Reject malformed and duplicate URI query fields.
- Reject unknown nested wire settings without changing stored rows or secrets.
- Reject unknown carriers while retaining the absent-field UDP default.
- No schema, wire format, dependency, or supported-profile breaking change. Previously accepted malformed input will be rejected.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `awg3-native-engine`: preserve profile meaning across input, storage, and activation.

## Impact

- `:core:data:model`, `:core:data:runtime-state`, `:core:data`, `:core:service`, and app import tests.
- No native transport, locale, golden fixture, production server, or deployment changes.
