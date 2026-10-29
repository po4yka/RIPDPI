---
id: AND-1793270044084741
title: Close native and Android security audit findings
kind: bug
status: review
area: android
priority: high
owner: Security remediation
parent: null
blocked_by: []
spec_mode: required
openspec_change: harden-native-android-boundaries
created: 2026-10-29
updated: 2026-10-29
---

## Goal

Close the five confirmed Rust and Android audit defects without changing unrelated runtime behavior.

## Acceptance criteria

Reject unauthenticated helper clients; remove legacy Shadowsocks UDP; bound SS2022 session state; bound cancelled import reads; preserve fixed-read lengths in release builds. Each fix has a regression, independent review, and its own commit.

## Ownership

- Primary: planning, task state, io-uring fixes, combined Kotlin corrections, integration and shared gates.
- Transport worker: ripdpi-shadowsocks, relay-tls-transports Shadowsocks adapter, owning protocol capability callers/tests/docs. Two separate fix commits.
- Helper worker: core/service helper authentication, ripdpi-naiveproxy and owning tests.
- Import worker: app proxyimport reader/activity and owning tests; assigned after planning.
- Shared lane: Primary alone owns Cargo.lock, version catalog, generated API snapshots, locale sets, task board and OpenSpec artifacts. No dependency or baseline changes planned.
- Writers use separate worktrees; no writer edits main.
