---
title: Add network-security-config with opportunistic domainEncryption
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-28
---

- [ ] #task Add network-security-config with opportunistic domainEncryption #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-network-security-config-with-opportunistic-domainencryption`
- **Verify:** `just build`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add `res/xml/network_security_config.xml` with `<domainEncryption mode="opportunistic"/>` as the base config, and point `AndroidManifest.xml` at it. Opportunistic unlocks platform ECH when both the library and DNS say yes.

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §4A.

## Current status

Verified 2026-05-28 against the current tree:

- The manifest was already wired to `@xml/network_security_config`, and this pass adds `xml-v37` overlays so Android 17+ gets opportunistic `domainEncryption` without changing older-platform resources.
- The same pass adds enabled per-domain config blocks for the current owned-stack probe hosts used by the first browser/remediation slice.
- `NscDomainEncryptionGeneratorTest` covers generated enabled, disabled, and opportunistic `domainEncryption` blocks.
- `EchReadinessProbeInstrumentedTest` covers native HTTPS-RR plus rustls ECH negotiation when network tests are enabled; it does not prove platform Network Security Config ECH behavior.
- Still open: Android 17 instrumented proof that the platform stack attempts ECH from Network Security Config when DNS supplies a config.

## Acceptance criteria

- [x] Config file exists with the base `domainEncryption` block on the Android-17+ resource path.
- [x] Manifest references the config via `android:networkSecurityConfig="@xml/network_security_config"`.
- [x] App still builds on minSdk targets below Android 17; the new attribute is ignored harmlessly on older versions.
- [ ] Instrumented test on Android 17 confirms ECH is attempted when DNS surfaces an ECH config.

## Work log

- **2026-05-28** — Docs audit refreshed the status. The static Network Security Config path and generated domain-encryption XML are covered by source/tests. The current instrumented ECH probe proves native ECH readiness, not platform Network Security Config ECH behavior, so the Android 17 platform proof remains open.
- **2026-05-16** — Blocked on Android 17 instrumented test requiring a physical device for ECH attempt verification. Static parts (config file `res/xml/network_security_config.xml` + `res/xml-v37/network_security_config.xml` overlay, manifest reference `@xml/network_security_config`, multi-platform build passing `just build`) are landed and verified. The remaining acceptance criterion (instrumented test on Android 17 confirming ECH is attempted when DNS surfaces an ECH config) cannot run in CI — no physical Android 17 device available.
- 2026-05-16: Reclassified to backlog — no concrete blocker recorded in frontmatter (physical device constraint is an environment limitation, not a tracked dependency slug).

## Links

- [[Epic - Owned-stack mode with Android 17 ECH]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
