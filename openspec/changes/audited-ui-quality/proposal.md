# Change: Repair audited UI quality defects

Task ID: `UIX-1791538891055420`

## Why

The user requested an app-wide UI audit, implementation, and delivery to main. Source inspection found inaccessible controls, exposed passwords, stale feedback, and diagnostic results attached to the wrong selected profile.

## What Changes

- Repair shared focus, loading labels, dialog reachability, checkbox labels, motion, and touch targets.
- Mask credential inputs and protect explicit credential screens with the existing secure-window contract.
- Make route, refresh, editor-error, and loading states clear and recoverable.
- Keep diagnostics scoped to the selected profile and remove unsupported cross-network trends. Clear stale tuner feedback and prevent profile changes during a scan.
- Localize visible tool states across all ten locales.

## Capabilities

### New Capabilities

- `audited-ui-quality`: regression coverage for these repairs.

### Modified Capabilities

- None. Existing service, protocol, data-store, and navigation contracts remain.

## Impact

Only app presentation sources, resources, tests, and task evidence. No production dependency is added. Golden changes require separate authorization.
