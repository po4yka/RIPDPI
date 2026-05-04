---
title: Define NetProfile HostProfile and ArmStats
type: task
status: backlog
area: service
priority: high
owner: unassigned
parent: epic-privacy-preserving-strategy-learner
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Define NetProfile HostProfile and ArmStats #repo/RIPDPI #area/service #status/backlog ⏫

## Summary

Introduce the three data classes that back the learner. Field shapes come
straight from the plan; keep them minimal and explicit.

```text
NetProfile { asn, access_type, ip_family, dns_class,
           udp443_ok, tcp443_ok, observed_fail_phase }
HostProfile { etld_plus_1, h3_advertised, https_rr_present,
            ech_capable, app_family }
ArmStats { arm_id, alpha, beta, p50_ttfb_ms, bytes_overhead,
         repeated_failures, last_success_at }
```

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §5 "Local state".

## Acceptance criteria

- [ ] Types defined with serde support (stable schema, versioned).
- [ ] No leakage of user-identifying data: no URL, no SSID, no precise
    location anywhere on these types.
- [ ] Unit tests cover serialization round-trips and enum exhaustiveness.

## Links

- [[Epic - Privacy-preserving strategy learner]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
