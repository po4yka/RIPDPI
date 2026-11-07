---
task_id: AND-1794036523065646
change: parse-import-deep-links-with-uri
commit_sha: 0d99822abbf6e579925e2e7e06f60330e3935778
local: passed
local_evidence: "Initial regression gate: 44 tests, five failures before the fix. Review regression: 45 tests, one raw-remarks failure before normalization. Final planned app gate: 45 tests passed, zero failures/skips. staticAnalysis and ci-gates passed; independent re-review found no actionable issues."
remote_ci: not_applicable
remote_ci_evidence: This scope authorizes local work and main commits; remote publication is not owned by this task.
device: not_applicable
device_evidence: No Android lifecycle, JNI, or device socket ownership change is planned. Use the child host or Robolectric gates; revisit this category if implementation expands those contracts.
artifact: not_applicable
artifact_evidence: No release APK, native binary publication, or golden blessing is requested.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-IMPORT-DEEP-LINKS-WITH-URI-BEHAVIOR | AND-1794036525535986 | 45 targeted app tests; staticAnalysis; ci-gates; independent re-review | Passed |
| REQ-IMPORT-DEEP-LINKS-WITH-URI-COMPAT | AND-1794036525967551 | 45 targeted app tests; staticAnalysis; ci-gates; independent re-review | Passed |

## Evidence boundary

Both regression failures were observed before their fixes. Final targeted tests and all named gates passed. The exact implementation SHA is recorded above. An intermediate lint run could not read a generated KSP factory from another variant; both variant KSP outputs were prepared before the successful full rerun. Remote CI, device, artifact, and deployment results are not inferred.
