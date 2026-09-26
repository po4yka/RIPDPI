## Context

The admission service reads active visible and hidden bridge registrations. Current execution cleans the primary bridge before suspending in `prepareReprobe`, so the registry reports no active scan during preparation.

## Decision

Prepare and register the hidden re-probe bridge before primary cleanup. Keep primary cleanup in a guaranteed path when preparation or registration fails or is canceled. Start the re-probe only after the primary bridge is cleaned and VPN route resume completes.

## Ownership and risk

This writer owns scan execution coordination and tests. Other writers own export, `dpi`, `dpich`, and `rkn`. No shared serialized file changes. The order must preserve the RAW_PATH settlement barrier and avoid two active network probes.

## Validation and rollback

Use a coroutine-controlled preparation pause to assert manual admission remains blocked during handoff. Run focused and full diagnostics unit gates. Revert the local commit to roll back; no data migration is needed.
