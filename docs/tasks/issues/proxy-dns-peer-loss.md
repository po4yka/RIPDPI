---
id: DNS-1791554915587312
title: Preserve routed DNS during proxy peer loss
kind: bug
status: doing
area: dns
priority: high
owner: Android acceptance agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: dns-1791554915587312-proxy-dns-peer-loss
created: 2026-10-09
updated: 2026-10-09
---

## Goal

Preserve resolver and TUN identity during ambiguous shared proxy DNS failures, while retaining direct-path, local native proxy endpoint, and resolver-specific failover. This work is coordinated by TST-1791553917096956.

## Acceptance criteria

- Reproduce DNS and peer recovery failures on current main before production policy edits.
- Use the effective tunnel route and consumed shared upstream for attribution, including strict policy when the standalone preference is disabled.
- Preserve endpoint timeout failover through a local native proxy without a consumed shared upstream. Retain the existing Android recovery assertions.
- Run service unit tests and real Android TUN tests with resolver, establishment, payload, receipt, and cleanup assertions.
- Repeat routed Xray acceptance and check all reports and remote CI on the combined clean source.

## Ownership

Android writer owns core:service DNS failover, consumed runtime evidence, unit tests, XrayProviderE2ETest, and NetworkPathE2ETest. Coordinator owns planning artifacts and Simple UI test fixes. Each writer uses a separate worktree. Shared schemas, locales, baselines, and lockfiles remain unchanged.
