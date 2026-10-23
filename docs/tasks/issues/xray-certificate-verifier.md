---
id: TRN-1792776009699531
title: Rebuild Xray with the patched certificate verifier
kind: bug
status: review
area: transport
priority: high
owner: Security runtime maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: xray-certificate-verifier
created: 2026-10-23
updated: 2026-10-23
---

## Goal

Ship the existing stable Xray runtime with the patched Go certificate verifier for CVE-2026-111943.

## Acceptance criteria

- Require Go 1.27.3 in the producer and in every packaged ABI.
- Reject old, malformed, and mismatched compiler provenance.
- Build the real AAR for all four ABIs and pass native protection, lifecycle, and artifact gates.
- Preserve Xray source pins and VPN protection patches. No production deployment is part of this task.

## Ownership

Security runtime maintainer owns `.github/actions/build-xray/action.yml`, `scripts/native/{build-libxray.sh,libxray_artifacts.py,libxray-build.Dockerfile}`, `scripts/tests/{test_libxray_artifacts.py,test_ci_native_dependency_graph.py}`, `native/xray/patches/manifest.json`, `docs/native/libxray-packaging.md`, and this task/spec. Root owns cross-repository integration. No Rust lock or version catalog edits are planned.
