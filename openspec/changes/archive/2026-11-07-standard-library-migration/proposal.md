# Change: Replace confirmed custom infrastructure with standard components

Task ID: `EPC-1794036260770217`

## Why

The audit found defects and duplicated infrastructure that existing standard components can cover. A coordinated plan prevents parser contracts and shared dependency files from drifting during the replacements.

## What Changes

- Track exactly 15 confirmed cases as separate implementation tasks.
- Sequence real prerequisites and serialize shared dependency edits.
- Require regression and compatibility evidence for each completed replacement.
- Breaking changes are limited to rejected malformed inputs and the defects specified by each child; no blanket protocol migration is approved.

## Capabilities

### New Capabilities

- `standard-components/standard-library-migration`: Evidence and execution boundaries for the audited replacements.

### Modified Capabilities

- None at epic level; child changes define their own behavior.

## Impact

Kotlin application/core modules and Rust infrastructure/protocol crates named by the child records. This epic changes planning artifacts only. Product code, dependencies, and runtime behavior change only through reviewed child implementations.
