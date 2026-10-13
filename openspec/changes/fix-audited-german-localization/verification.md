---
task_id: UIX-1791912007411833
change: fix-audited-german-localization
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: Local resource and rendered UI checks own this presentation change; physical-device acceptance is not claimed.
artifact: not_applicable
artifact_evidence: No signed APK or release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DE-RESOURCES | UIX-1791912114857745 | German resource regressions, rendered controls and privacy captions | Required |
| REQ-DE-DYNAMIC | UIX-1791912115576158 | Known/unknown status and readable summary tests | Required |
| REQ-DE-MEASUREMENTS | UIX-1791912115576158 | German/English switch, unit thresholds and overflow tests | Required |
| REQ-DE-PARITY | UIX-1791912117280968 | Locale parity/export, full app tests, static analysis, lint, architecture and independent review | Required |

## Boundaries

No schema, protocol, raw-evidence or dependency changes. No fixture refresh is authorized. Simple screenshots require a real private relay bundle; the prior source search found no usable bundle or generator credentials. Hosted CI and linguistic/device acceptance are separate evidence.


## Observed source and resource evidence

The implementation starts from `ddd0024d494fb2e734223b8c6e77ec239a255bbe`.
The plan commit is `37d32edba7f9492c8e0d0d633a837b61fcf7afec`; source edits
are not yet committed. A recent fetch still shows the original upstream.

- Six German resource tests reproduced 107 failures on unchanged source.
  The exact audit inventory has 87 English active keys, 20 semantic active
  keys and five unused keys. The combined legacy diff matches all 112 keys.
- After correction, all 26 resource regressions passed: RU 4, ZH 5, ES 5,
  HI 6 and DE 6. Three missing CLDR `many` forms in ES/FR/PT-BR were fixed
  after the first combined test exposed them; no test gate was weakened.
- All ten locale sets, option references/order and translation export passed.
  The app has 4,986 strings and 24 plurals, service has 10 strings and one
  plural, and Quick Connect has 15 strings. The manifest has 4,996 strings.
- Architecture health passed: 21 baseline indicators, no new/worsened/stale
  indicators, 116 crates, 354 edges and 4,049 measured source files.
- File limits passed with no new violations or baseline growth. Locked Cargo
  metadata and task contracts passed (129 tasks, 365 execution steps).
- An independent source review found no critical or warning findings. Raw
  Telegram values, errors and tones are preserved. Locale and bit-rate paths
  are shared by UI and readable share output. Renderer defaults preserve
  existing technical exports; only the app passes localized headings.
- Scoped app/core ktlintFormat passed. Targeted app/core tests are queued
  through the machine-wide build gate. No JVM, UI render or screenshot pass
  is claimed before its result is observed.

German render tests use the actual support, privacy, update and block-editor
components at normal and enlarged font scales. The matching RDS reference is
`docs/design/rds/preview/components-settings-row.html`; share summary uses
`diagnostic-report-summary.html`. Production layout is unchanged. Reference
HTML and golden fixtures are untouched.

## Known input boundary

The ignored Simple asset `app/src/simple/assets/embedded-relay-bundle.json`
is absent in this worktree. The prior scoped search found only placeholder
CI fixtures, no bundle in published or local APKs, and no Terraform/SOPS
inputs for the owning `ripdpi-vpn-deploy` generator. The Simple gate must
remain explicitly blocked if the required real input is still absent.
The user did not authorize new production relay provisioning. Placeholder
profiles are not completion evidence.
