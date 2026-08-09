# Change: Activate the signed shared-priors release channel

Task ID: `SVC-1786272083078316`

## Why

The shared-priors parser, signature verifier, Android download worker, and native application path are implemented, but production builds embed an all-zero verification key and optional empty release URLs. The fail-secure `NoProductionKey` state prevents every production bundle from being accepted, so the release channel is intentionally inert rather than usable.

## What Changes

- Production builds receive an owner-controlled Ed25519 public verification key and HTTPS manifest/payload locations through the release configuration boundary.
- Missing, malformed, or test-only release configuration remains fail-closed and cannot replace the last accepted priors.
- Release validation proves that the exact shipped artifact contains the approved public configuration and accepts a matching signed fixture while rejecting tampered content.

## Capabilities

### New Capabilities

- `signed-shared-priors-release-channel`: Fetch, verify, and apply an owner-published shared-priors release without weakening the existing offline and privacy boundaries.

### Modified Capabilities

- None.

## Impact

- Rust verification contract: `ripdpi-shared-priors` embedded production key.
- Android release configuration: `core/service` build constants and refresh worker.
- Release/CI inputs and artifact verification for public, non-secret URLs and a public verification key.
- No private signing key is stored in the repository or APK, and user data upload remains out of scope.
