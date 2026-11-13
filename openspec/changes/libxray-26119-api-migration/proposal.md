# Change: Migrate the libXray API and preserve native protection

Task ID: `OUT-1794578142905704`

## Why

The latest libXray release removes the facade used by the Android adapter and
changes SOCKS UDP ownership. Updating version labels alone would break runtime
control and prevent the current protection patch from applying. The owner
authorized the complete migration as part of the dependency refresh.

## What Changes

- Build libXray v26.11.9 with its paired Xray-core and gomobile revisions.
- Adapt the native API boundary while preserving Android runtime behavior.
- Preserve fail-closed socket protection and private managed UDP associations.
- Retire the gRPC trailer patch because the new upstream contains its fix.
- Remove the Android STUN dialer's dependency on private Go zone-cache symbols
  while preserving scoped IPv6 and custom-network behavior.
- BREAKING upstream API changes remain inside the native adapter boundary.

## Capabilities

### New Capabilities

- `xray-native-api-migration`: verify the current upstream boundary and its
  protection, cleanup, and artifact contracts.

### Modified Capabilities

- None. Existing service and profile behavior must stay compatible.

## Impact

- libXray source patches, preparation and artifact verification scripts.
- The Kotlin native adapter and its tests if the upstream boundary requires it.
- Native provenance, build pins, and host protection tests.
- AAR production and device acceptance remain distinct evidence gates.
