# Change: Persist the probe cache with AtomicFile

Task ID: `DGN-1794036642254618`

## Why

An interrupted probe-cache write must not destroy the previous complete cache. The 2026-11-07 audit identifies this as finding 12 (attachment 5) at source revision 202f970.

## What Changes

- Use the existing Android AtomicFile pattern for both reads and writes, with finishWrite and failWrite.
- A completed cache write survives reopen with the same entries and timestamps.
- An interrupted or failed replacement leaves the previous valid cache readable through AtomicFile.
- Correct only the specified defect or infrastructure duplication. No other breaking change is planned; Keep the mutex, 24-hour TTL, 10-entry limit, JSON shape, and safe fallback for an already corrupt file. No database or schema migration.

## Capabilities

### New Capabilities

- `standard-components/persist-probe-cache-with-atomic-file`: An interrupted probe-cache write must not destroy the previous complete cache.

### Modified Capabilities

- None. This delta records the previously unspecified contract for the audited path.

## Impact

- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/ProbeResultCache.kt`

Use only existing platform or workspace libraries. Check the direct module dependency before editing. The parent epic owns programme order and shared lanes. No external publication, deployment, or unrelated protocol change is included.
