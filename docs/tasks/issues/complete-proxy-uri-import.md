---
id: OUT-1794722656313653
title: Fix proxy URI import and complete TUIC and Mieru support
kind: feature
status: doing
area: outbound
priority: high
owner: URI import team
parent: null
blocked_by: []
spec_mode: required
openspec_change: complete-proxy-uri-import
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Fix IMP-01 findings F1-F7. Add a usable TUIC v5 URI import path and pinned Mieru advanced TCP settings. Keep imported profiles and secrets available after later imports and restarts.

## Acceptance criteria

- All seven audit regressions have separate code commits and owning regression tests.
- TUIC URI fields reach saved relay credentials, native configuration and export without loss. Unsupported native options fail before writes.
- Mieru v3.36.0 handshake and traffic-pattern fields retain protobuf presence and affect the actual TCP engine.
- Real Room tests cover repeated import, restart, reselection, export, failure and cancellation.
- Pinned Go vectors and loopback peers verify Mieru wire behavior; native and Kotlin contract gates pass.
- Independent review has no open blocking findings; main receives the commits and origin/main is pushed.

## Ownership

- Root owns task/OpenSpec artifacts, combined validation, commit integration and push.
- Codec worker owns F1/F4/F5/F7 and TUIC typed profiles/callers. It is the first writer for ProxyGroupStores.kt, NativeRelayProfileValidation.kt and SelectorRelayProfileMapping.kt.
- Persistence worker owns F2 transaction/import paths, F3 saved Mieru mapper slice, and F6 scanner privacy and tests. F3 owns RelayProfileShareMapper.kt before the TUIC writer uses it.
- Mieru worker owns new advanced Kotlin leaf types and ripdpi-mieru Rust files. It receives the shared profile/mapping/share/config lanes only after the other writers finish those files.
- Workers use separate worktrees. Shared files have one active writer. No worker edits task artifacts, baselines, Cargo.lock or version catalogs.
- Required reviewers: pr-reviewer for the combined behavior/contract change; async-cancel-safety for native async changes; JNI verifier only if an actual JNI signature changes.

## Scope

Use the existing native TUIC v5 capability set. Mieru remains TCP carrier only. Do not add a backend, new production dependency, root requirement, new UDP carrier, or identifiers for seed defaults. Audit hypotheses require confirmed evidence before a separate change.
