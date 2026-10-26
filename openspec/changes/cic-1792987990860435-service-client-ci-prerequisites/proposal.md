# Change: Restore service client CI prerequisites

Task ID: `CIC-1792987990860435`

## Why

The service contract mirror cannot pass client CI. WebTunnel release packaging lacks the Tokio macro feature used by its library. Four Simple Home tests still expect the previous confirmation control and share-label case. The guide audit also omits the existing subscription import page from its flow sections.

## What Changes

- Compile the existing WebTunnel asynchronous bridge in non-test builds.
- Exercise the current disconnect confirmation dialog and report actions in Simple Home tests.
- Include the existing subscription import page in the audited import flow.
- Apply the user-approved size limits from verified CI binaries while retaining all recorded measurements.
- Keep all assertions on confirmation, enabled states, and callback counts. No breaking change.

## Capabilities

### New Capabilities

- `service-client-ci-prerequisites`: release packaging and Simple Home regression checks required by the service contract mirror.

### Modified Capabilities

- None.

## Impact

- WebTunnel Cargo feature selection Simple Home unit tests, the guide audit manifest, and the approved native size policy.
- No dependency version, public API, Android runtime behavior, deployment authority, or golden image changes.
