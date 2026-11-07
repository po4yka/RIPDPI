# Change: Decode profile URI fragments exactly once

Task ID: `DAT-1794036514216544`

## Why

Imported profile names must preserve plus signs, percent characters, and Unicode without a second decode. The 2026-11-07 audit identifies this as finding 1 (attachment 2) at source revision 202f970.

## What Changes

- Use java.net.URI fragment semantics once. Make raw and decoded fragment inputs explicit; remove URLDecoder from already decoded inputs.
- A profile with fragment #A%2BB imports with display name A+B.
- Malformed authority or userinfo escapes return null without partial import or credential disclosure; existing query and fragment repair preserves stray percent characters as text.
- Correct only the specified defect or infrastructure duplication. No other breaking change is planned; Keep each supported share-link format and export/import round trips. Do not change relay activation or stored profile identifiers.

## Capabilities

### New Capabilities

- `standard-components/normalize-profile-uri-fragments`: Imported profile names must preserve plus signs, percent characters, and Unicode without a second decode.

### Modified Capabilities

- None. This delta records the previously unspecified contract for the audited path.

## Impact

- `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/uri/ProxyUriCodec.kt`

Use only existing platform or workspace libraries. Check the direct module dependency before editing. The parent epic owns programme order and shared lanes. No external publication, deployment, or unrelated protocol change is included.
