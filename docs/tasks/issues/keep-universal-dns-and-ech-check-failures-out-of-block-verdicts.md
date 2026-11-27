---
id: DNS-1795705017215412
title: Keep universal DNS and ECH check failures out of block verdicts
kind: bug
status: review
area: dns
priority: medium
owner: Diagnostics engineer
parent: null
blocked_by: []
spec_mode: required
openspec_change: dns-1795705017215412-keep-universal-dns-and-ech-check-failures-out-of-block-verdicts
created: 2026-11-26
updated: 2026-11-27
status_detail: Merged to main; remote CI evidence recorded (run 46009834608 on d9aa00b99f).
---

## Goal

The ECH and DNS survey and the CDN ECH updater do not treat a failure of every measurement as a signal of blocking.

## Context

Aggregate public measurements for Russia from 2026-11-21 to 2026-11-26 show the DNS check and the ECH check failing on every measurement without an anomaly flag. A failure on every measurement carries no information about blocking. The source is single and unverified here.

The first step is to verify the current behavior of the survey classification and the ECH updater. The change is needed only if either one maps a universal failure to a block, downgrade or suppress outcome.

Neighbours: DNS-1795610829282002 (bounded concurrency of the resolver survey) and the existing ECH comparison tasks change other parts of the same code and must not be duplicated.

## Acceptance criteria

- A survey outcome where every resolver or measurement fails is classified as universal failure, separate from a block signal.
- The ECH updater does not mark a config as blocked, downgrade it or suppress it when every measurement fails.
- Regression tests cover a universal failure and a partial failure, and only the partial failure can produce a block signal.
- History and redacted exports show the universal-failure class.

## Ownership

Not assigned yet. Before a writer starts, record the owner of the resolver survey classification and the CDN ECH updater, and serialize any diagnostics schema-version constant.
