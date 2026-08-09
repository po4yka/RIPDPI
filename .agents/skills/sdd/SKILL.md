---
name: sdd
description: Route RIPDPI changes through the risk-based OpenSpec specification workflow.
---

# Specification-driven development

Use OpenSpec for every feature, behavioral epic, user-visible change, breaking contract, protobuf/JNI/wire/storage/configuration schema, cross-module change, or security/network/protocol/service-lifecycle change.

- Create or find the portfolio task first with `./taskctl`.
- Use `$openspec-propose` to produce proposal, delta specs, design, mdtask tasks, and verification.
- Use `$openspec-apply-change` only after the planning artifacts pass strict validation.
- Use `$openspec-archive-change` only after review and complete evidence.

Narrow single-module regression fixes, tests, docs, dependency-only work, mechanical refactors, tooling, and research may use `spec_mode: not-required` with an allowed reason. Features and epics cannot waive OpenSpec.
