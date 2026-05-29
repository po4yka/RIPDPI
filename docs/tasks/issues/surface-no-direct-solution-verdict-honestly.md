---
title: Surface NO_DIRECT_SOLUTION verdict honestly
type: task
status: done
area: diagnostics
priority: medium
owner: unassigned
parent: epic-direct-mode-transport-policy-and-verdicts
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-29
---

- [x] #task Surface NO_DIRECT_SOLUTION verdict honestly #repo/RIPDPI #area/diagnostics #status/done 🔼

## Summary

When the diagnostic exhausts its arms without a stable success, return `NO_DIRECT_SOLUTION` rather than keep burning attempts. Surface this to the user as a real verdict, not an error.

## Plan reference

ripdpi-android-direct-mode-plan-2026-04-20 §3 rule 5 and "Phase 4" end state.

## Acceptance criteria

- [x] Diagnostic returns the verdict with a structured reason code (`IP_BLOCKED`, `TLS_BLOCKED_NO_ARMS_WORKED`, `DNS_BLOCKED_NO_ECH`, etc.).
- [x] UI/diagnostics surface displays the verdict + reason; does not pretend to keep trying.
- [x] A cooldown prevents immediately re-running the full diagnostic for the same host on the same network profile.
- [x] Persisted verdict is subject to the Phase 5 revalidation rules (ASN change, access-type change, etc.).

## Implementation note

The first honest-verdict slice landed on 2026-04-23: diagnostics now keep distinct TLS, QUIC, and likely-IP-block `NO_DIRECT_SOLUTION` causes, and summary text surfaces the verdict reason instead of pretending the scan should keep trying.

Phase 5 persistence / revalidation closed 2026-05-29 via [[Persist direct-mode policy with revalidation]]: a persisted `NO_DIRECT_SOLUTION` verdict only stays runtime-usable while its cooldown is active (`ServerCapabilityRecord.isFreshDirectPolicy`), is dropped on ASN or ECH change (`isInvalidatedByEnvironment`), ages out at the 7-day TTL anchored on confirmation, and is retired after the failure-count threshold. Access-type changes re-scope the network fingerprint key, so the verdict is not even looked up under a different transport. Covered by `ServerCapabilityDirectPolicyTest`.

## Links

- [[Persist direct-mode policy with revalidation]]
- [[Epic - Direct-mode transport policy and verdicts]]
- ripdpi-android-direct-mode-plan-2026-04-20


## doing
