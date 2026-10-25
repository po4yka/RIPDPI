## Context

The current producer pins Go 1.27.2. The stable Xray 26.3.27 core uses the Go certificate verifier and has no bundled fallback root dependency. Rebuild with Go 1.27.3 to receive the upstream verifier fix without changing protocol versions.

## Goals / Non-Goals

- Goal: produce a real patched stable runtime and verify the compiler in every native library.
- Non-goal: promote Xray 26.10.21, change camouflage or server pins, deploy, or change application APIs.

## Decisions

- Store the exact Go version in the existing native patch manifest; build, CI, and artifact verification read that policy.
- Read bounded ELF section and Go inline build-info data using the existing Python standard-library verifier. This preserves offline verification without a new Go runtime dependency for Gradle consumers.
- Update the container compiler and remove gomobile init, which can replace the pinned gobind.
- Allow native darwin/arm64 builds: the pinned gomobile selects the NDK universal compiler from darwin-x86_64. Preserve rejection of unsupported Linux arm64 hosts.

## Contracts and ownership

Security runtime maintainer owns the native producer, CI action, artifact verifier, packaging tests, patch manifest, Docker recipe, documentation, and this task/spec. Root owns integration. No shared version catalog, Rust lock, JNI, wire, or persistence contract changes are required.

## Risks / Trade-offs

- ELF build-info parsing must handle all four ABIs and reject malformed lengths and duplicate sections. Verify against actual rebuilt libraries as well as parser fixtures.
- Exact pinning intentionally rejects other compiler versions until reviewed.

## Migration Plan

Rebuild existing artifacts with the new producer. Existing artifacts fail verification. Do not roll back to a vulnerable compiler. Required gates: Python packaging and CI graph tests, shell syntax, real native protection/lifecycle tests, a build for all ABIs, release artifact verification, task validation, and independent review. Hosted CI is recorded separately; device and deployment changes are not owned here.
