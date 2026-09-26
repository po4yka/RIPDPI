## Context

The pending conflict snapshot contains profile, settings, and path mode. The admitted path also receives a deadline, candidate limit, target overrides, owner, and raw-path resume policy.

## Decision

Keep these options in the pending request and pass them through the existing request factory and start path after resolution. Copy caller-owned target lists when creating the pending request so later caller mutations cannot change it. Retain the selected profile and settings snapshot.

## Ownership and risk

This writer owns scan controller and tests. Other writers own export, `dpi`, `dpich`, and `rkn`. No shared serialized file or contract changes. A resolved request must not silently run with changed options.

## Validation and rollback

Run a focused conflict-resolution regression test and the diagnostics module unit gate. Revert this local commit to roll back; no data migration is needed.
