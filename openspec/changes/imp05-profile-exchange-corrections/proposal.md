# Change: Correct profile exchange

Task ID: `AND-1794728744166445`

## Why

The profile exchange audit found profile replacement, data loss during export, inconsistent import errors, and QR output defects.

## What Changes

Save imported profiles independently. Check admission before confirmation. Apply the same parsing rules at each supported entry point. Preserve representable settings or refuse the link. Protect credential-bearing output.

## Capabilities

### New Capabilities

- `profile-exchange-integrity`

### Modified Capabilities

None.

## Impact

Android import and share screens, profile writes, URI codecs, and QR encoding. The work uses the existing formats and runtime backends.
