# Change: Keep accepted reserve profiles usable offline

Task ID: `DAT-1792661188293948`

## Why

A payload-qualified selector needs its complete reserve set and policy after restart even when subscription delivery is unavailable. Existing encrypted group persistence provides the correct owner, but corrupt storage can be treated as empty and an unusable or stale refresh can be reported as a successful replacement. These paths can destroy or misrepresent the last accepted reserves.

## What Changes

- Preserve the accepted member set, payload policy and durable selection across restart and failed refresh, without a second archive of obsolete credentials.
- Reject corrupt sealed storage instead of loading or overwriting an empty catalogue or reviving legacy plaintext.
- Admit only usable refresh results against the still-current subscription/group snapshot; obsolete results cannot overwrite newer edits or resurrect a deleted group.
- Verify offline reload and positive payload-qualified reserve selection with real local HTTPS/SOCKS traffic and failure regressions.
- No server deployment, endpoint provisioning, production access, new dependency, UI change, wire schema change or change to delivery-token expiry semantics.

## Capabilities

### New Capabilities

- `subscriptions/offline-reserves`: durable accepted reserves and guarded refresh publication.

### Modified Capabilities

- None. The existing `payload-aware-selector-failover` change supplies the real-payload verification contract; this change consumes that implementation without duplicating it.

## Impact

- `core:data:runtime-state` encrypted proxy-group persistence and its tests.
- App subscription refresh admission and offline selector tests.
- Depends on the existing payload-aware selector branch; no modification to the separate deployment producer or its listener defaults.
