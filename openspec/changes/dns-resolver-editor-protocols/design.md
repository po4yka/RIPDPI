## Context

The current encrypted DNS Compose form has a DoH fallback branch. App UI state omits persisted ODoH fields, although DataStore, Kotlin active settings, service mapping, and native tunnel already carry them.

## Goals / Non-Goals

- Goal: DoQ and ODoH forms read and write all required endpoint data without protocol loss.
- Non-goal: Change native DNS behavior, persisted schema, or bundled resolver catalog.

## Decisions

- Reuse existing common host/port/bootstrap and text-field components for DoQ.
- Give ODoH its own fields for proxy URL and operator, target host/path and operator, config source/bytes and timestamps, matching the existing tunnel contract.
- Use the existing ODoH source constants and validate HTTPS URL, target fields, even-length hex, and positive timestamps before mutation.

## Contracts and ownership

App DNS UI, mapper, actions, ViewModel, tests and DNS-specific locale keys belong to DNS editor lane. Locale files and generated board are serialized at integration. Protobuf fields 288-296 and native mapping stay unchanged.

## Risks / Trade-offs

- Manually supplied ODoH config bytes can expire; the existing native resolver validates their freshness. The UI accepts only positive timestamp and TTL values and leaves native expiry checks intact.
- DNS protocol switching clears protocol-specific fields; switching and saving are covered by regression tests.

## Migration Plan

No migration. Existing saved DoQ and ODoH fields are projected into the new forms. Rollback restores the previous UI while persisted data remains compatible. Verify targeted unit/Compose tests, app lint, and task contract validation.
