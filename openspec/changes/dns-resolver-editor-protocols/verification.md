---
task_id: DNS-1790339241109329
change: dns-resolver-editor-protocols
commit_sha: null
local: passed
local_evidence: "Targeted app DNS/home/config tests passed (36/36), targeted core/service resolver, controller, intent and queue tests passed (56/56); :app:lintGithubFullDebug, :core:service:lintDebug, and staticAnalysis passed with ripdpi.skipNativeBuild=true; prior native cargo test -p ripdpi-tunnel-core --locked doq passed 2/2; taskctl contracts, OpenSpec strict validation, and translation export passed. Combined-tree gates will rerun after rebase."
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
| REQ-DNS-EDITOR-PROTOCOL | DNS-1790339324021183 | `DnsSettingsScreenTest` covers draft selection and DoQ/ODoH forms; `SettingsDnsActionsTest` covers Proxy DoQ Save, restart, and rejected VPN/interleaved Save; home and config restart tests cover guarded actions; native direct/SOCKS5 tests passed | local passed; device pending |
| REQ-DNS-EDITOR-VALIDATION | DNS-1790339324021183 | ODoH config wire, expiry and target path boundary tests passed; `ConnectionPolicyResolverTest` rejects effective VPN DoQ before tunnel composition; `ServiceIntentArbiterTest`, `ServiceShellDelegateDnsLeaseTest`, and controller tests cover both start/save orders, overlapping starts finishing out of order, failed start, and Stop; app/service lint and staticAnalysis passed | local passed; device pending |

Home and config restart paths explain why saved DoQ cannot activate in VPN mode. Tile, widget, and Android-owned recovery rely on connection policy rejection before tunnel setup; those entry points do not show the same preflight explanation. A foreground-service dispatch that Android never delivers retains its VPN start reservation until a later service command finishes or the process ends; this avoids a save/start race but can temporarily prevent DoQ Save. The DNS and home Compose implementation follows existing components; the RDS DNS picker preview predates DoQ/ODoH and was not regenerated in this change.
