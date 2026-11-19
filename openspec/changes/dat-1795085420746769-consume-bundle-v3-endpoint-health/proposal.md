# Change: Consume ripdpi-bundle v3 and feed endpoint health into failover

Task ID: `DAT-1795085420746769`

## Why

The parser supports only bundle v2 (`SingBoxSubscriptionParser.RIPDPI_SCHEMA_VERSION = 2`). Server operators now know per-endpoint health from probes, and the field-report client needs an opaque `server_ref` and a report endpoint, both carried by bundle v3. Without health in the bundle the client keeps selecting endpoints that are known to be down.

## What Changes

- Parse `ripdpi-bundle` v3: v2 plus per-endpoint `health` {status healthy|degraded|down|unknown, observed_at RFC3339, ttl_seconds} and top-level `field_report` {endpoint https URL, cohort_token, max_reports_per_day}.
- BREAKING: reject v2 bundles with a typed error; no shim and no dual parser.
- Health feeds selector failover (`PayloadFailoverPolicy` and its import path): down is excluded, degraded is deprioritised, unknown and expired (observed_at + ttl_seconds passed) are neutral.
- Golden tests for parsing and ordering.
- Expose the endpoint id as `server_ref` and the `field_report` block to R3 through the parse result.

## Capabilities

### New Capabilities

- `bundle-v3-endpoint-health`: bundle v3 parsing, v2 rejection, health-aware failover ordering.

### Modified Capabilities

- None.

## Impact

- Kotlin: `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/subscription/SingBoxSubscriptionParser.kt`, `.../subscription/PayloadFailoverImport.kt`, `.../PayloadFailoverPolicy.kt`, `core/data/src/test/kotlin/com/poyka/ripdpi/data/RipdpiBundleContractTest.kt`, vendored files under `core/data/src/test/resources/contract/`.
- Blocked by R2 (DGN-1795085357320979): shares the vendored contract lane and drift-pin mechanism.
- Overlap: `re-vendor-the-deployer-bundle-fixtures-with-the-30-minute-probe-cadence` (DAT-1793428393229398, review) and `device-server-migration` (TRN-1792673614153980, review) touch the same fixtures and parser; rebase on them and re-vendor after they land. `correct-dia*` and `connection-stage-scale` do not touch the parser.
- Counterpart `ripdpi-vpn-deploy` changes: D1 contracts (schema), D4 bundle v3 generation (producer; live acceptance needs it), D3 protocol probes (source of health), D7 cross-repo e2e.
- Parent: EPC-1795085162356362.
