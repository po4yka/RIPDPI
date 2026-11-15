# Change: Repair DIA-18 DNS and public IP evidence

Task ID: `DGN-1794748905877489`

## Why

DIA-18 can send requests after the user disables network checks. IP family loss, missing GeoIP data, and unchecked payloads can produce false findings. Errors appear as successful checks. The local audit reproduced these failures.

## What Changes

- Respect the active-network setting in both entry points and ASN lookups.
- Compare numeric addresses within each family and validate reflected-IP payloads.
- Preserve unknown provider traits, country agreement, execution status, and resolver context.
- Cancel HTTP body reads and bound response size. Describe default-route observations without an underlay claim.
- Include endpoint evidence in the copied report.
- Additive in-memory result status and export status fields; no JNI, protobuf, or stored-schema change.

## Capabilities

### New Capabilities

- `detection-ip-evidence`: DNS settings observations and public-IP diagnostic evidence with explicit uncertainty.

### Modified Capabilities

- None.

## Impact

`:core:detection`, `:core:diagnostics-data`, app detection settings/results, localized status labels, tests, and diagnostics documentation. No new dependency, external probe, backend, root activation, or release.
