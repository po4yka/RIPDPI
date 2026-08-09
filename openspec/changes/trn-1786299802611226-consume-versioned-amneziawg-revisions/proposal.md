# Change: Consume versioned AmneziaWG revisions and stage interoperability

Task ID: `TRN-1786299802611226`

## Why

RIPDPI currently implements one pinned AWG semantic line and receives bundle
parameters without an explicit wire revision. Once deploy emits the canonical
revision contract, the client must reject ambiguity and stage later revisions
without changing the established current path.

## What Changes

- Parse and persist the deploy-owned AWG revision/provenance contract.
- Select runtime semantics explicitly and refuse unsupported or inconsistent entries.
- Preserve current-revision behavior through migration and regression fixtures.
- Add a staging-only later-revision path with upstream, cross-stack, and device evidence.

## Capabilities

### New Capabilities

- `amneziawg-revision-consumer`: Validate and activate only explicitly supported AWG revisions.
- `amneziawg-staged-interop`: Prove a later revision without production eligibility.

### Modified Capabilities

- `standalone-amneziawg`: Make wire semantics explicit across import, persistence, runtime, and diagnostics.

## Impact

- Affects subscription parsing, profile storage, cross-repository contract assets,
  native runtime/config/JNI, UI diagnostics, fixtures, and Android acceptance.
