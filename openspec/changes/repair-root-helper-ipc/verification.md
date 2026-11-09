---
task_id: VPN-1794196711383747
change: repair-root-helper-ipc
commit_sha: 7b475fb9e1b433c80daee34db6cd2ec6b77f23a3
local: required
local_evidence: Regressions and combined source gates have not run for this repair.
remote_ci: required
remote_ci_evidence: No remote run for this change yet.
device: blocked
device_evidence: Root activation and physical or external probes require separate authorization; no device acceptance is claimed.
artifact: required
artifact_evidence: The repaired helper must compile for Android ARM64.
deployment: not_applicable
deployment_evidence: This repair does not deploy a service.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-IPC-CLOEXEC | VPN-1794196899260030 | Kernel flags, working socket, exec and error regressions are pending. | required |
| REQ-IPC-LIMITS | VPN-1794196899730212 | Reproduction, bounded allocation, coverage and handler liveness are pending. | required |
| REQ-IPC-DEADLINE | VPN-1794196900171780 | Late reader and trickle response regressions are pending. | required |
| REQ-IPC-SOCKET | VPN-1794196900171780 | Invalid fd type and cleanup regressions are pending. | required |
| REQ-IPC-COMPAT | VPN-1794196900616146 | Current F3/F4 provenance verified at 6808d8e91; current tests, combined gates and review are pending. | required |

## Acceptance scope

Implement and deliver the local fixes with distinct finding commits. Preserve the existing private-directory trust model. Unconfirmed filesystem attacks and active-VPN raw-route behavior remain explicit audit uncertainties, not invented accepted behavior. Keep this task in review while remote or device evidence is outstanding.

## Current audit disposition

| Audit item | Current disposition | Evidence boundary |
|---|---|---|
| F1 close-on-exec | Repair and regression in progress in the fd ownership lane. | Linux kernel flags and exec oracle required. |
| F2 scalar allocation | Repair and handler regression in progress in the ordered payload lane. | Invalid input must not panic or retain a socket copy. |
| F3 Kotlin connect deadline | Already repaired by 6808d8e91 on the starting main. | Current targeted Gradle run failed before tests because Google Maven access reported No route to host; no new Kotlin success is claimed. |
| F4 global process-name termination | Already repaired by 6808d8e91 on the starting main. | Endpoint cleanup uses the owned socket and nonce; no killall call remains. Current unit validation is pending. |
| H1 late request completion | Reproduction in progress. | Delayed-watchdog schedule must not permit late dispatch. |
| H2 response trickle | Reproduction in progress. | Total budget must bound multiple receives. |
| H3 pathname and nonce permissions | Retained trust-model risk; no independent cross-UID failure established in the app-private directory. | Host inspection does not establish Android filesDir or SELinux access. Do not add peer authority or claim an exploit without proof. |
| H4 incorrect descriptor types | Reproduction in progress. | Test real file, Unix socket, wrong network transport, and unconnected TCP. |
| H5 fd-less active-VPN raw path | Retained device-routing uncertainty at the DPI-37 boundary. | Root helper receives no app socket mark for these commands. Android route/protect acceptance requires a separately authorized device capture; no speculative packet-path change is included. |

## Build environment

The first current Kotlin command was `build-gate --lane ripdpi -- env -u CARGO_BUILD_JOBS ./gradlew :core:service:testDebugUnitTest --tests '*RootHelperSocketDeadlineTest' --tests '*RootHelperEndpointCleanupTest' --tests '*RootHelperManagerTest' --tests '*RootHelperNfqwsTest' --tests '*NfqwsBackendTest' -Pripdpi.skipNativeBuild=true --max-workers=4 -Pripdpi.nativeCpuBudget=4`. It exited 1 before tests. Google Maven failed to resolve metadata with No route to host. No dependency or repository policy was changed. Log: `/tmp/vpn09-current-kotlin-tests-20261109.log`.

Inspection also found user Gradle heap defaults above the current repository ceiling. Later Gradle commands must set a 5 GiB Gradle heap and 3 GiB Kotlin heap for that command; no shared user configuration is edited.
