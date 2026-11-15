# Change: Repair ECH library probe contracts

Task ID: `DGN-1794732423381679`

## Why

The DIA-06 audit found six defects in library DNS discovery, path attribution and TLS result classification. UDP and TLS fixtures reproduced them at 80d2a664cdb1a0e1061cfc3a35f3e226a0deba4c. The implementation base is origin/main at 1acf7c7c69492899dee8dbc80d553f9e5dc203aa; owning ECH sources did not change between these revisions.

## What Changes

- Preserve clean missing HTTPS data and reject unusable DNS service records.
- Reject unsupported active-path context before I/O and report observed provenance only.
- Preserve authenticated ECH rejection and classify uncertain transport outcomes.
- Apply one deadline to complete DNS discovery, including retries.
- BREAKING: add explicit variants to the internal public Rust outcome enum; update exhaustive repository callers. No JNI, wire, storage or configuration schema changes.

## Capabilities

### New Capabilities

- `diagnostics/ech-library`: contracts for the existing library probe and driver.

### Modified Capabilities

- None.

## Impact

- ripdpi-diagnostics-probes sources, tests and generated Rust API snapshot.
- Existing pinned boring and rcgen may be test-only dependencies for the independent TLS oracle.
- Production scheduling, DIA-15 and active VPN integration are outside this repair.
