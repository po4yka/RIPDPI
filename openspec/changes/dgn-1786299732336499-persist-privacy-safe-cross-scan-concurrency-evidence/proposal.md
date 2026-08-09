# Change: Persist privacy-safe cross-scan concurrency evidence

Task ID: `DGN-1786299732336499`

## Why

The fingerprint/concurrency diagnostic supports replicated evidence within one
scan, but its alternative confirmation path cannot work because target-rotation
history is not retained across scans. Re-running a scan therefore loses useful
independence evidence or risks relying on raw target identity.

## What Changes

- Persist a bounded categorical history keyed by privacy-safe scope and stable target alias.
- Confirm only fresh, independent, eligible observations across scans.
- Integrate retention, reset, archive/backup projection, and explicit stale or partial states.
- Preserve current single-scan behavior.

## Capabilities

### New Capabilities

- `cross-scan-concurrency-evidence`: Confirm fingerprint/concurrency conjunction
  across independent scans without retaining raw network facts.

### Modified Capabilities

- `connection-concurrency-diagnostics`: Add a second bounded confirmation path.

## Impact

- Affects diagnostics contracts and classifier, Room persistence/migration,
  retention/reset, archive/backup projections, Kotlin UI/export, and tests.
