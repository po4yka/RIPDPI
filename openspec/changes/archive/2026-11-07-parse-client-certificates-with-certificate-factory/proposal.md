# Change: Parse client certificates with CertificateFactory

Task ID: `AND-1794036563799026`

## Why

Certificate import must accept valid DER and PEM bytes without first requiring UTF-8 text. The 2026-11-07 audit identifies this as finding 3 (attachment 3) at source revision 202f970.

## What Changes

- Pass bounded bytes to the platform X.509 CertificateFactory. Remove certificate regex and manual Base64 input decoding; keep PEM output normalization.
- The same certificate imported from binary DER and PEM produces equivalent normalized certificate bytes.
- Oversized, empty, and malformed documents fail explicitly before storage; errors contain no key or certificate payload.
- Correct only the specified defect or infrastructure duplication. No other breaking change is planned; Keep the 256 KiB input limit, explicit empty or invalid certificate rejection, certificate order, and existing private-key and PKCS12 import contracts.

## Capabilities

### New Capabilities

- `standard-components/parse-client-certificates-with-certificate-factory`: Certificate import must accept valid DER and PEM bytes without first requiring UTF-8 text.

### Modified Capabilities

- None. This delta records the previously unspecified contract for the audited path.

## Impact

- `app/src/main/kotlin/com/poyka/ripdpi/security/MasqueClientCredentialImporter.kt`

Use only existing platform or workspace libraries. Check the direct module dependency before editing. The parent epic owns programme order and shared lanes. No external publication, deployment, or unrelated protocol change is included.
