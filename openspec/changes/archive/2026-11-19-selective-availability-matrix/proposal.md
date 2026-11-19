# Change: Add a selective availability diagnostic matrix

Task ID: `DGN-1791519134625558`

## Why

Existing probes do not compare independent availability cohorts through a complete bounded HTTPS response. Alternate SNI success alone does not establish a mobile allowlist.

## What Changes

- Add a manual profile with dated target provenance and optional user hosts.
- Measure and display DNS, TCP, verified TLS, HTTP and body completion for bounded repeats.
- Report selective availability as an observation, with explicit inconclusive and control-failure states.
- Persist results through existing scan history and redacted export.

## Capabilities

### New Capabilities

- `selective-availability-matrix`: bounded cohort measurements and presentation.

### Modified Capabilities

- None.

## Impact

- Native diagnostics request types, stage registry and probe runner; additive optional request configuration.
- Kotlin diagnostics catalog, planning, manual request override, UI and all locales.
- Existing report result/details storage is reused; no Room migration or backend dependency.
