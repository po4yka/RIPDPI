---
id: OUT-1794578142905704
title: Migrate libXray API and preserve native protection contracts
kind: chore
status: review
area: outbound
priority: high
owner: Project maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: libxray-26119-api-migration
created: 2026-11-13
updated: 2026-11-13
---

## Goal

Use libXray v26.11.9 with its paired Xray-core and gomobile sources while
preserving VPN protection, managed-runtime ownership, and private UDP sessions.
This is the API migration authorized during dependency refresh
`CIC-1794577079488154`.

## Acceptance criteria

- The current upstream API can start, stop, and query the managed runtime.
- Failed protection cannot admit non-loopback direct traffic.
- Failed start and stop retain correct instance and cleanup ownership.
- Private RUF1 UDP associations retain authentication, endpoint, nonce, and
  request-ID checks; ordinary SOCKS listeners keep their standard behavior.
- Source checks, protection tests, Kotlin tests, AAR production, and artifact
  verification pass, with device and external-network coverage reported apart.

## Ownership

The main agent writes and integrates all changes on `main`, as requested.
The migration worker prepares an isolated temporary patch. It owns no shared
checkout files. Cargo and Gradle catalog writes remain with the main agent.
