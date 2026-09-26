# Change: Explain redacted network history fields

Task ID: `UIX-1790434646817990`

## Why

Capture-time privacy projection stores placeholders. The history UI currently displays them as raw text when sensitive details are shown. It also exposes legacy cellular identities and ASN when sensitive details are hidden.

## What Changes

- Show a localized not-stored marker for redacted scalar fields and a count for redacted address lists.
- Hide legacy carrier/operator fields, Wi-Fi network IDs, private DNS hostnames, and ASN until sensitive details are shown.
- Keep connection history snapshots free of raw DNS, private DNS hostnames, and public IPs because that screen has no sensitive-details toggle.
- Preserve coarse radio and network type information.

## Capabilities

### Modified Capabilities

- `diagnostics/network-history-presentation`: Clarify stored redaction and honor the visibility toggle.

## Impact

- `:app` diagnostics and connection history mappers, tests, and ten locales.
