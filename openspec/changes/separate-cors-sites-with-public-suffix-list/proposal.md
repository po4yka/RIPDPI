# Change: Separate CORS sites with the Public Suffix List

Task ID: `RLY-1794036597359685`

## Why

Credential policy must distinguish independent tenants below public and private suffixes. The 2026-11-07 audit identifies this as finding 6 at source revision 202f970.

## What Changes

- Define the allowed-origin policy first, then replace the last-label heuristic with an offline PSL implementation that includes private suffixes and standard URL parsing.
- An explicitly permitted origin receives only the CORS headers allowed by the documented policy.
- Independent github.io and fly.io tenants do not receive credential permission solely because they share a public suffix.
- Correct only the specified defect or infrastructure duplication. No other breaking change is planned; PSL domain grouping must not itself grant credential access. Define scheme and port treatment, localhost and IP behavior, invalid Origin rejection, and list update policy before implementation.

## Capabilities

### New Capabilities

- `standard-components/separate-cors-sites-with-public-suffix-list`: Credential policy must distinguish independent tenants below public and private suffixes.

### Modified Capabilities

- None. This delta records the previously unspecified contract for the audited path.

## Impact

- `native/rust/crates/ripdpi-apps-script-core/src/proxy/http_relay.rs`

A bundled PSL crate or maintained offline list is a new production dependency. Before adding it, assess license, private-section coverage, list freshness, APK size, maintenance, and compatibility; obtain approval required by repository policy. Creating this task does not approve that dependency. The parent epic owns programme order and shared lanes. No external publication, deployment, or unrelated protocol change is included.
