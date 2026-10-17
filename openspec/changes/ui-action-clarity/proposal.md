# Change: Expose recovery actions and simplify interface choices

Task ID: `UIX-1792228010381450`

## Why

Home recovery actions require an extra disclosure. The interface mode changes
before users can compare Guided and Advanced. History filters occupy space even
when unused, and the return-to-latest action does not show new event arrivals.

## What Changes

- Show the main available Home recovery action without opening details.
- Offer Guided and Advanced as explicit choices with descriptions and cancellation.
- Keep history search and active filters compact; disclose the full filter controls.
- Show the number of new matching events while preserving the reader position.
- No breaking changes.

## Capabilities

### New Capabilities

- `ui-action-clarity`: direct recovery, explicit persona selection, compact history filters, and new-event feedback.

### Modified Capabilities

- None.

## Impact

- App-only Compose presentation, history UI projections, localized resources,
  translation export metadata, and behavior/device tests.
- Existing settings, recovery callbacks, runtime state, storage, network, JNI,
  and protobuf contracts stay in place. No new dependency is required.
