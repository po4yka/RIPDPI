# Change: Resolve native redirects and DNS endpoints with url

Task ID: `DNS-1794036588840800`

## Why

Native redirect and encrypted-DNS endpoint handling must extract valid hosts and resolve relative references against the current URL. The 2026-11-07 audit identifies this as finding 5 at source revision 202f970.

## What Changes

- Use the existing url crate for parsing, host_str, and join. Carry the current URL through redirect chains and omit fragments from HTTP request targets.
- https://host.example?token=x yields host host.example and request target /?token=x.
- Invalid or disallowed redirect schemes and malformed endpoints fail within the existing redirect budget without leaking query credentials.
- Correct only the specified defect or infrastructure duplication. No other breaking change is planned; Keep Apps Script front host and SNI transport, scheme restrictions, redirect budget, endpoint ports, and error redaction. Do not turn on an ordinary automatic HTTP redirect client.

## Capabilities

### New Capabilities

- `standard-components/parse-native-urls-with-url-crate`: Native redirect and encrypted-DNS endpoint handling must extract valid hosts and resolve relative references against the current URL.

### Modified Capabilities

- None. This delta records the previously unspecified contract for the audited path.

## Impact

- `native/rust/crates/ripdpi-apps-script-core/src/domain_fronter/redirect.rs`
- `native/rust/crates/ripdpi-apps-script-core/src/domain_fronter.rs`
- `native/rust/crates/ripdpi-ech-dns/src/endpoints/parse.rs`
- `native/rust/crates/ripdpi-ech-dns/src/endpoints/builder.rs`

Use only existing platform or workspace libraries. Check the direct module dependency before editing. The parent epic owns programme order and shared lanes. No external publication, deployment, or unrelated protocol change is included.
