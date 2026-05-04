---
title: Select resolver mapping from DNS classification
type: task
status: blocked
area: dns
priority: high
owner: Senior Network Protocol Engineer
parent: epic-encrypted-dns-and-https-svcb-classifier
blocks: []
blocked_by: [decouple-jni-handle-lifetime-and-telemetry-locking]
created: 2026-04-20
updated: 2026-04-23
---

- [ ] #task Select resolver mapping from DNS classification #repo/RIPDPI #area/dns #status/blocked ⏫

## Summary

Implement the resolver selection logic:

```
if DNS_POISONED:
  use encrypted mapping immediately
elif DNS_DIVERGENT and transport failures correlate with system answers:
  prefer encrypted mapping
else:
  keep fastest resolver path
```

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §2 selection logic.

## Acceptance criteria

- [ ] Selection runs after classification, produces a concrete
    `ResolvedMapping { best_ip, ip_family, source }`.
- [ ] `DIVERGENT` correlation check uses observed transport fail phase,
    not a static heuristic.
- [ ] On `CLEAN`, fastest resolver wins — no unnecessary encrypted-DNS
    overhead.
- [ ] Selection is cached per `(host, NetProfile)` with the same TTL as
    the family cache.

## Implementation note

As of 2026-04-23, RIPDPI now consumes the classifier-derived
`DOH_PRIMARY` / `DOH_SECONDARY` signal in two enforcement paths:
authority-scoped native hostname resolution and VPN startup when the
observed hostname-backed hints converge on one resolver role. That lands the
runtime resolver-mapping slice without yet implementing the richer
`ResolvedMapping { best_ip, ip_family, source }` object or the dedicated
`(host, NetProfile)` selection cache described above.

## Links

- [[Classify DNS as clean poisoned divergent ech-capable]]
- [[Epic - Encrypted DNS and HTTPS SVCB classifier]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
