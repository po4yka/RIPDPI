# Change: Decouple SSH editor from engine probe contracts

Task ID: `SVC-1793330036179924`

## Why

The SSH observer exposes engine-api result types to app code. The app-engine boundary gate rejects these imports by design. The service must own the result consumed by its UI callers.

## What Changes

- Return service-owned SSH observation and failure types from SshHostKeyObserver.
- Convert native results inside core:service with exhaustive classification.
- Update UI and tests without changing trust confirmation, cancellation, socket protection, or error messages.
- Keep the boundary gate and all quality rules unchanged. Verify full staticAnalysis and hosted CI/CodeQL.

## Capabilities

### New Capabilities

- `ssh-observation-service-boundary`: UI-facing SSH observation result ownership.

### Modified Capabilities

- None.

## Impact

- core:service observer contract and adapter, app SSH editor and tests.
- One instrumentation assertion uses the service result; native instrumentation cases keep native types.
- No JNI, wire, storage, dependency, locale, or golden fixture changes.
