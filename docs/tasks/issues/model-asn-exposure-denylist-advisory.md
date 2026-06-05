---
title: Model ASN exposure denylist advisory
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-06-05
updated: 2026-06-05
---

## Summary

Model an operator-facing advisory for ASN/service-network denylist exposure reduction without embedding any IP ranges, ASN inventories, firewall commands, or deployable server policy into the Android app.

## Why now

Maintained public sources such as `https://github.com/C24Be/AS_Network_List` and the supplemental sheet at `https://docs.google.com/spreadsheets/d/1YWS5aMEykkM9koxcZW1q_bZBi2j1UGmTbhFhOfnrd4k/edit?gid=2065371898#gid=2065371898` document an operator pattern: reduce server exposure to high-risk ASN/service-network ecosystems on both ingress and egress. RIPDPI should preserve the defensive model and provenance while keeping enforcement server-side and outside the offline Android client.

## Scope

- Add an advisory model or documentation surface that explains the objective: reduce direct server contact with high-risk service networks that may participate in app telemetry, probing, or reputation pipelines.
- Keep the app offline-first: no remote lookup API, no bundled external range feed, no background feed refresh.
- Redact or omit concrete ranges, ASN rows, firewall commands, route blackholes, and provider-specific policy.
- Link users/operators to the deploy repo for controlled server-side implementation once that repo has schema, dry-run, canary, and rollback gates.
- Make diagnostics wording clear that this is an infrastructure hardening layer, not a local DPI bypass strategy.

## Acceptance criteria

- [ ] A short server-hardening/advisory section exists in `docs/server-hardening.md` or a more appropriate architecture doc.
- [ ] The advisory cites the two public source URLs as provenance without copying their inventories.
- [ ] No Android runtime code loads, stores, or applies ASN/IP denylist data.
- [ ] Any UI/diagnostics copy, if added later, states that enforcement belongs to operator-controlled server infrastructure.
- [ ] Tests or static checks prove no generated IP/ASN range fixture was added to app assets.
- [ ] The deploy-side implementation task is linked as the canonical server-enforcement follow-up, and the Android app remains advisory-only until that task ships with review/canary/rollback gates.

## Safety boundaries

- Do not add deployable firewall rules, ipset/nftables snippets, route files, or range examples.
- Do not name product fields or files after carriers, ISPs, operators, or geography-specific cohorts.
- Do not weaken the "No backend server" rule: RIPDPI remains functional offline and locally.

## Links

- `https://github.com/C24Be/AS_Network_List`
- `https://docs.google.com/spreadsheets/d/1YWS5aMEykkM9koxcZW1q_bZBi2j1UGmTbhFhOfnrd4k/edit?gid=2065371898#gid=2065371898`
- `docs/server-hardening.md`
- `../ripdpi-vpn-deploy/docs/tasks/issues/asn-exposure-denylist-gate.md` (sibling repo follow-up)
