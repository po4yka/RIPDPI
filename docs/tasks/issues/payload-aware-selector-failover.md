---
id: TRN-1792659369802828
title: Verify payloads before automatic selector failover
kind: feature
status: doing
area: transport
priority: high
owner: Client runtime implementer
parent: null
blocked_by: []
spec_mode: required
openspec_change: payload-aware-selector-failover
created: 2026-10-22
updated: 2026-10-22
---

## Goal

Import an explicit payload-failover policy and switch the active selector only
after complete authenticated payload transfers through real candidate relays.

## Acceptance criteria

1. The shared bundle extension is validated and persisted without adding unsupported fields to ordinary sing-box outbounds.
2. Two independent HTTPS controls require exact bounded length and SHA-256; short bodies, redirects, bad certificates, wrong hashes and timeouts never qualify a candidate.
3. Consecutive failures/recovery, cooldown and bounded backoff prevent flapping; network changes invalidate observations and all-failed candidates never select direct egress.
4. Import, real HTTP body verification and virtual-clock selector tests pass; scoped Android module gates and static analysis are observed.
5. Delivery alone is not deployment or filtered-network acceptance. A refusal-only implementation cannot close this feature.

Coordinated producer task: `po4yka/ripdpi-vpn-deploy#SCR-1792659157802819`.

## Ownership

- Client runtime implementer owns this isolated worktree: subscription importer and persisted policy, service payload verifier, app selector controller, their tests, mirrored bundle schema and this task/OpenSpec change.
- Deployment implementer owns the source schema and emitter in the sibling deployment repository. Synchronize the exact reviewed schema; do not edit their worktree.
- No JNI/protobuf/Rust/locale changes are planned. Shared-file ownership stays with this writer for the bundle mirror and selector model.
