# Change: Parse import deep links with standard URI components

Task ID: `AND-1794036523065646`

## Why

Import links must separate query from fragment and accept only complete HTTPS subscription targets. The 2026-11-07 audit identifies this as finding 2 at source revision 202f970.

## What Changes

- Use java.net.URI for the outer custom scheme and existing OkHttp HttpUrl for the decoded HTTP target. Keep a narrow adapter for provider-specific link forms.
- The outer #label is not part of the URL extracted from ripdpi://import?sub=https%3A%2F%2Fexample.com%2Fsub#label.
- An incomplete target https://, a non-HTTPS target, and malformed escapes return typed errors without navigation or network access.
- Correct only the specified defect or infrastructure duplication. No other breaking change is planned; Keep sub before url, first-value duplicate handling, typed encoding errors, subscription versus one-shot intent, and Hiddify nested URL forms.

## Capabilities

### New Capabilities

- `standard-components/parse-import-deep-links-with-uri`: Import links must separate query from fragment and accept only complete HTTPS subscription targets.

### Modified Capabilities

- None. This delta records the previously unspecified contract for the audited path.

## Impact

- `app/src/main/kotlin/com/poyka/ripdpi/proxyimport/RipDpiImportDeepLinkParser.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/proxyimport/SingBoxDeepLinkParser.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/proxyimport/ImportDeepLinkUri.kt`

Use only existing platform or workspace libraries. Check the direct module dependency before editing. The parent epic owns programme order and shared lanes. No external publication, deployment, or unrelated protocol change is included.
