---
title: Package deterministic release native symbols
type: task
status: review
area: ci
priority: high
owner: Release native-symbol lane
parent: null
blocks: []
blocked_by: []
created: 2026-07-17
updated: 2026-07-17
---

## Goal

Produce a fail-closed, deterministic native-symbol bundle for every release JNI library
without shipping unstripped Android binaries.

## Scope

- Retain line tables in the production Cargo profile and split symbols with the pinned NDK
  LLVM tools before Android packaging.
- Keep packaged JNI libraries and helper executables stripped while preserving exact GNU
  build-ID correlation with their symbol sidecars.
- Validate and package the exact four-ABI by five-library matrix through one shared script.
- Wire CI and release workflows to the same explicit symbol contract.

## Ship definition

- Focused producer, packager, and workflow contract tests cover success and fail-closed paths.
- One release-verification variant assembles the complete symbol bundle from four ABI shards.
- Release publishing invokes the same packager and uploads an explicit, non-empty artifact.
- A host-ABI NDK smoke proves debug sections, stripping, and build-ID correlation.

## Work log

- Production Android Rust artifacts now retain line tables until the pinned NDK tools split
  JNI debug sidecars and strip packaged JNI libraries and helper executables.
- CI and release workflows use one fail-closed packager for the exact four-ABI by five-JNI
  matrix, build-ID correlation, manifest hashing, and deterministic ZIP output.
- Focused contract tests cover valid packaging plus missing, extra, mismatched, duplicated,
  unstripped, and missing-debuglink inputs.
- A real arm64-v8a NDK build verified five JNI sidecars, three stripped helpers, unique matching
  build IDs, and byte-identical outputs on an up-to-date rebuild. The remote four-ABI CI run
  remains the final environment-level verification.
