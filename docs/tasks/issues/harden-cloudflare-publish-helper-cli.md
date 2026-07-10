---
title: Harden Cloudflare publish helper CLI
type: task
status: doing
area: service
priority: high
owner: Codex primary lane
parent: epic-june-2026-audit-remediation
blocks: []
blocked_by: []
created: 2026-07-10
updated: 2026-07-10
---

## Goal

Make Cloudflare Tunnel publish startup fail closed and transactional, remove tunnel token, UUID, and sensitive filesystem paths from helper process argv, and give helper version probing an independent bounded timeout.

## Ownership

- Primary serialized writer: `core/service` Cloudflare publish runtime/process/binary/config code, its JVM tests, `ripdpi-cloudflare-origin` CLI and tests, and Cloudflare operations documentation.
- Read-only audit lanes: startup rollback/lifecycle, CLI secret exposure and telemetry privacy, and timeout/process supervision.
- Explicitly unowned and unchanged: `native/rust/Cargo.lock`, protobuf/JNI/native relay schemas, `RelayKindDescriptors`, locale resources, baselines, goldens, signing configuration, credentials, secrets, and user data.

## Acceptance criteria

- [ ] A publish startup failure after either helper launches terminates and closes every helper/resource already acquired, leaving no runnable partial session.
- [ ] `cloudflared` and `ripdpi-cloudflare-origin` argv contain no tunnel token, VLESS UUID, credential/config/state path, or local-origin URL containing sensitive path material; secrets/config are delivered through a bounded private stdin contract or an equivalently non-argv local channel.
- [ ] The helper CLI rejects missing, malformed, oversized, or trailing startup configuration before listening and never echoes secret values in errors or telemetry.
- [ ] Cloudflared version probing has a separate timeout from publish readiness/startup and forcibly reaps a timed-out probe process.
- [ ] Deterministic JVM and Rust regression tests prove rollback, argv redaction, stdin/config parsing, timeout independence, and process cleanup without live Cloudflare/provider dependencies.
- [ ] Focused tests, affected-module lint/clippy, relay interoperability checks, architecture gates, and rebased-tree collision-prone gates pass.
- [ ] The completed branch is rebased onto latest `origin/main`, fast-forwarded to `main`, pushed, and the isolated task worktree/branch is cleaned up without touching unrelated dirty state.

## Work log

- 2026-07-10: Task opened. No implementation changes yet.
