---
task_id: UIX-1792228984398359
change: compose-common-styles
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: Publication is not authorized.
device: required
device_evidence: null
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RDS-STYLE-PARITY | UIX-1792229085066929 | Full/Simple unit tests and verifyScreenshots | Pending |
| REQ-RDS-STYLE-OVERRIDE | UIX-1792229082522189 | Style override and button interaction tests | Pending |
| REQ-RDS-STYLE-THEME | UIX-1792229083651465 | Theme, state, reduced-motion and control tests | Pending |
| REQ-RDS-STYLE-COMPATIBILITY | UIX-1792229081138948 | `styles-dependency-gate-final.log`: AAR metadata, Full/Simple compilation, build convention tests | Passed locally |
| REQ-RDS-STYLE-ACCEPTANCE | UIX-1792229086339829 | Local, screenshot, device and profileable evidence | Pending |

## Initial dependency gate

- Baseline source: `829f5a90b04aabcd4d8a4d0820190c9f48f3de98`.
- The published beta BOM resolves Foundation, UI, and Runtime 1.13.0-beta01 and Material3 1.5.0-beta01.
- Android CLI installed `platforms/android-37.1` revision 1.
- The new CI minor-platform regression failed before the change and passed after it. The native dependency graph and tool pinning suites passed all 36 tests.
- Build convention sources compiled during the first Gradle gate. That run failed because the new worktree had no ignored libXray artifact directory. It does not establish app compatibility.
- The standard verifier accepted the existing libXray artifact set from the main checkout: SHA-256 `c5865cc277651bc7964500333566f9c540fe5c4053881aa109785c8d5b24ab35`. The next gate selects it with the supported `ripdpi.prebuiltXrayAarDir` property.
- Dependency gate reports are under `build/reports/styles-dependency-gate*.log`.
- The final dependency gate passed: `:app:checkGithubFullDebugAarMetadata`, `:app:compileGithubFullDebugKotlin`, `:app:compileGithubSimpleDebugKotlin`, and `:build-logic:convention:test`. It ran with Compose reports enabled and completed in 4 minutes 37 seconds after the host build slot became available. A diagnostic confirmed compile SDK 37.1 for app, service, and the benchmark module. The published libXray artifacts were verified through the normal build task. No native build was skipped.
- The beta dependencies add deprecation warnings for existing BasicTextField/VisualTransformation and sheet APIs. They do not block compilation. Changing editing-state or modal behavior is outside this migration.
- Component, screenshot, device, and performance validation remain pending. The successful dependency gate does not cover provisional source candidates in the other worktrees.
