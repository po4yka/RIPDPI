# Change: Restore service client CI prerequisites

Task ID: `CIC-1792987990860435`

## Why

The service contract mirror cannot pass client CI. WebTunnel release packaging lacks the Tokio macro feature used by its library. Four Simple Home tests still expect the previous confirmation control and share-label case.

## What Changes

- Compile the existing WebTunnel asynchronous bridge in non-test builds.
- Exercise the current disconnect confirmation dialog and report actions in Simple Home tests.
- Keep all assertions on confirmation, enabled states, and callback counts. No breaking change.

## Capabilities

### New Capabilities

- `service-client-ci-prerequisites`: release packaging and Simple Home regression checks required by the service contract mirror.

### Modified Capabilities

- None.

## Impact

- WebTunnel Cargo feature selection and Simple Home unit tests.
- No dependency version, public API, Android runtime behavior, deployment authority, or golden image changes.
