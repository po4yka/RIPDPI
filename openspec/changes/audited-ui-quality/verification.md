---
task_id: UIX-1791538891055420
change: audited-ui-quality
commit_sha: null
local: required
local_evidence: The full app retry passed 2295 tests and failed one existing JSON guard. Static analysis and locale lint passed; post-rebase checks are pending.
remote_ci: required
remote_ci_evidence: Push is authorized but not yet performed for this change.
device: required
device_evidence: Device acceptance has not been observed.
artifact: required
artifact_evidence: Actual renders and golden verification are pending.
deployment: not_applicable
deployment_evidence: This change does not own a deployment.
---

# Verification

Evidence will be recorded after each owned implementation and on the combined tree. Requirement-to-step mapping will use the IDs allocated by taskctl.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UIQ-INPUT | UIX-1791539016251165 | 146 tests passed with no failures or skips, with detekt and Full/Simple lint. The same platform Keyboard harness produced 7 runtime failures in 24 tests with the old focus targets. Legacy positional dropdown calls are tested. | passed in the owned worktree; combined checks pending |
| REQ-UIQ-LAYOUT | UIX-1791539016251165 | Five SDK 36 previews were inspected. The medium-window dialog at font scale 2 keeps both actions visible. Explicit action layouts and bounded/unbounded scroll are tested. | passed in the owned worktree; combined checks pending |
| REQ-UIQ-CREDENTIAL | UIX-1791539018423234 | 58 tests passed with no failures or skips, with detekt and ktlint. Tests cover visible password masking, secure-window flags and release, and share-dialog policy. | passed in the owned worktree; combined checks pending |
| REQ-UIQ-RECOVERY | UIX-1791539018423234 | The same 58-test gate covers retained drafts, load/save retry, catalog recovery, and refresh failure/cancellation. Twelve SDK 36 previews were inspected in compact and RTL/font-scale-2 states. | passed in the owned worktree; combined checks pending |
| REQ-UIQ-EVIDENCE | UIX-1791539020136173 | 72 tests passed with no failures or skips. Full and Simple lint and staticAnalysis passed. SDK 36 previews were inspected in both themes and at font scale 2 with RTL. | passed in the owned worktree; combined checks pending |
| REQ-UIQ-LOCALE | UIX-1791539021807814 | Nine recovery and tool-state keys exist in all ten locales. Full/Simple app and service lint passed. Compact and RTL/font-scale-2 SDK 36 previews were inspected. | passed; SDK 37 and device limits remain |

## Diagnostic repair evidence

The diagnostic commits are `55d33313716143be12780348c6433fa2c6f0d303`
and `91c3e9cade12805d280d45222f30809525d8a292`. They were applied to the
integration branch as `b2fa68270` and `efb9d7145`. The final owning command
passed in 2 minutes 35 seconds with 757 Gradle tasks. Its log is
`/tmp/ui-quality-diagnostics-green-final.log`.

The tests cover selected-profile evidence, consent actions, busy profile
selection, tool-state labels, tuner feedback and domain scope, and compact
layouts. Text checks use visible line bounds and ellipsis. They do not use
the width of the internal paragraph container as proof of clipped text.

The integration branch was rebased onto `03242e367`, which adds PMTU
measurements. No PMTU source changes are part of this repair. The task board
was regenerated with taskctl. Task contracts passed for 123 tasks and 335
steps. Combined-tree, remote CI, device, and golden checks remain pending.

## Shared and setup evidence

Shared source commits are `8dceddd97449b57b6c421c3b5c8d325c08fc8ef1`,
`637313d28ff4a7cc25225dd6c2e500c98e2d0775`, and
`9b49cdafcc3fd683b235f441030d1abee2365234`. The final source gate passed in
2 minutes 22 seconds. The log is `/tmp/ui-shared-final-green3.log`.
The SDK 36 render log is `/tmp/ui-shared-preview36-final.log`.

Setup source commits are `56b356503`, `38e339561`, `9584c1825`,
`6fd4bc14e`, and `098b1dec9`. The final owning gate passed in 42 seconds.
The log is `/tmp/ripdpi-ui-setup-final-gates.log`. The advanced-settings
dropdown consumer and its naming assertion were handed to the integration
writer. All ten AdvancedSettingsComponents tests passed on the combined source, including the accessible-name assertion.

Independent final source review found no actionable defects. It checked
all seven RuleEditorScreen calls, callback ownership, dialog constraints,
the legacy dropdown overload, and the new inline action weights.

The Simple runtime command stopped before tests at verifyEmbeddedRelayBundle.
It needs a real, non-empty `src/simple/assets/embedded-relay-bundle.json`.
No placeholder asset was supplied and no gate was disabled. Full/Simple
lint compiles the production UI, but this does not prove Simple runtime.
The required SDK 37 preview command is unsupported by the current renderer;
SDK 36 previews are compatibility evidence, not SDK 37 acceptance.

## Combined gate recovery

The first complete app gate ran 2,294 tests: 2,292 passed, two failed,
and none were skipped. Full/Simple app lint, service lint, detekt, and
ktlint passed. The failure evidence is preserved in the local combined
report snapshot.

The static-analysis boundary check found prohibited engine testing
imports in the new rule-editor test. The test now uses local one-shot
I/O failures and keeps its retry assertions. The original SDK 32
SubscriptionStatusNotifier test lacked its Robolectric SDK artifact.
The exact artifact was obtained from Maven Central, checked with SHA-512,
and installed in the Maven cache. No SDK or repository override was used.

The recovery gate ran 19 tests with no failures, errors, or skips.
The app engine boundary and detekt passed. Ktlint found one import-order
error, which was corrected before the complete retry.

SerializationJsonSourceRulesTest found five private JSON builders in
core diagnostics that are unchanged from the main baseline. A concrete
six-file centralization patch preserves the current serialization flags.
The user approved this patch and contract checks. An isolated writer now owns the repair. No guard was
disabled. This is separate from the UI source repair.

Origin/main advanced to d32c87174 during validation. The integration
branch must preserve those diagnostics changes, rebase, and repeat
combined checks before main integration.

The complete retry passed 2,295 of 2,296 tests with no errors or skips
in 366 suites. Only the existing core JSON source rule failed. The
restored SDK 32 test now runs its three methods. Static analysis, module
boundary checks, detekt, ktlint, Full/Simple app lint, and service lint
passed. The log is `/tmp/ripdpi-ui-quality-combined-retry-gates.log`.
The full XML/HTML and lint snapshot is preserved in the local evidence
directory `ripdpi-ui-quality-full-retry-evidence-eih4465a`.

The advanced-settings accessible name is committed as `63ee5647f`.
The rule-editor test boundary repair is committed as `f64157566`.
Both normal pre-commit and commit-message hooks passed.
