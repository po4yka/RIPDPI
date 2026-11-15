# Change: Preserve subscription import semantics

Task ID: `DAT-1794720910687853`

## Why

The IMP-03 audit found accepted inputs whose route, trust, transport, node set, or credentials change during import. Some partial results replace a working subscription. Selector defaults do not reach saved state.

## What Changes

- Reject constraints that the native profile cannot retain.
- Apply one complete node admission policy before bootstrap or refresh persistence.
- Preserve selected members, imported defaults, and Xray credential precedence.
- Reject lossy payload decoding and validate URL-test policy values.
- BREAKING: previously accepted unsafe or malformed configurations now fail without replacing working data.

## Capabilities

### New Capabilities

- `subscriptions/import-integrity`: import admission, semantic preservation, and initial selector preference.

### Modified Capabilities

- None. Existing native transports and activation authority remain the owners of connection start.

## Impact

- Affects :core:data:runtime-state, :core:data, :core:service selection callers where needed, and :app import/refresh paths.
- Saved selector metadata can gain an optional initial preference. Existing saved groups retain explicit choices.
- No new dependency, backend, JNI, protobuf, or transport wire format.
