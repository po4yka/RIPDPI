---
task_id: DNS-1790339241109329
change: dns-resolver-editor-protocols
commit_sha: null
local: passed
local_evidence: "Targeted DNS action, Compose, home card, and start-guard tests passed (30/30); :app:lintGithubFullDebug, :core:service:lintDebug, and staticAnalysis passed with ripdpi.skipNativeBuild=true; prior native cargo test -p ripdpi-tunnel-core --locked doq passed 2/2; taskctl contracts and OpenSpec strict validation passed. Full app tests and translation export will rerun after rebase."
remote_ci: required
remote_ci_evidence: Pending integration and hosted CI.
device: blocked
device_evidence: "No attached Android device was available (adb devices -l showed none). Compose preview could not render because :core:engine:verifyLibXrayArtifacts found no native/xray/artifacts."
artifact: not_applicable
artifact_evidence: No distributable artifact is owned by this UI fix.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DNS-EDITOR-PROTOCOL | DNS-1790339324021183 | `DnsSettingsScreenTest` covers draft selection, Proxy DoQ Save, and running-VPN rejection; `SettingsDnsActionsTest` covers Proxy restart and VPN rejection; `HomeModeCardUiStateTest` and `MainConnectionActionsDoqTest` cover home VPN start guard; native direct/SOCKS5 tests passed | local passed; device pending |
| REQ-DNS-EDITOR-VALIDATION | DNS-1790339324021183 | ODoH config wire, expiry and target path boundary tests passed; app lint and staticAnalysis passed | local passed; device pending |

The home path explains and blocks VPN start with saved DoQ. Tile, widget, boot, and config-screen starts still reach the native fail-closed rejection; these entry points do not yet show the same preflight explanation. The DNS and home Compose implementation follows existing components; the RDS DNS picker preview predates DoQ/ODoH and was not regenerated in this change.
