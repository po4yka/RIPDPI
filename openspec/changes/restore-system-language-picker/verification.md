---
task_id: UIX-1790432109043744
change: restore-system-language-picker
commit_sha: null
local: passed
local_evidence: Focused picker tests (3/3), app ktlint main/test, app detekt, app/service lint, taskctl validation passed locally on 2026-09-26.
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: not_applicable
artifact_evidence: No distributable artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-APP-LANGUAGE-SYSTEM | UIX-1790432252771292 | `LanguagePickerSheetTest.deviceLanguageChoiceClearsAnExplicitOverride` | Passed locally |
| REQ-APP-LANGUAGE-EXPLICIT | UIX-1790432252771292 | `LanguagePickerSheetTest.explicitLanguageChoiceRemainsAvailableFromSystemLanguage` and `explicitLanguageWithDeviceRegionSelectsTheSupportedLanguage` | Passed locally |
| REQ-APP-LANGUAGE-LOCALES | UIX-1790432257011846 | `:app:lintGithubFullDebug :core:service:lintDebug`; zero `MissingTranslation` findings | Passed locally |
