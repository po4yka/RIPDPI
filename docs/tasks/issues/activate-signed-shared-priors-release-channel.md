---
id: SVC-1786272083078316
title: Activate the signed shared-priors release channel
kind: feature
status: backlog
area: service
priority: high
owner: Release and service maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: activate-signed-shared-priors-release-channel
created: 2026-08-09
updated: 2026-08-09
---

## Goal

Activate the existing fail-secure shared-priors consumption path with an owner-approved public verification identity and release locations, proven against one exact production artifact.

## Acceptance criteria

- Production artifacts contain the approved non-zero Ed25519 public key and HTTPS manifest/payload locations, but no private signing material.
- Matching signed content is applied atomically; missing configuration, wrong keys, invalid signatures, hashes, versions, sizes, or records preserve the last accepted store.
- Refresh remains download-only and introduces no diagnostic, identifier, or learned-prior upload.
- Local, hosted CI, Android, exact-artifact, and owner-publication evidence are recorded against one exact commit SHA.
