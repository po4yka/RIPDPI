---
id: TRN-1794384538458949
title: Repair the TUN-02 WARP service findings
kind: bug
status: doing
area: transport
priority: high
owner: WARP repair coordinator
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-tun02-warp-service
created: 2026-11-11
updated: 2026-11-11
---

## Goal

Repair the eight findings in the TUN-02 audit on ec4f13320a and the two
additional findings from validation. Keep WARP
WireGuard, local license storage, and local Zero Trust import as separate
contracts. Make the supported profile setup available to the user.

## Acceptance criteria

- F1: readiness requires an authenticated peer; peer failure reaches health.
- F2: WARP works without a VPN callback when VPN is inactive; active protection remains fail-closed.
- F3: automatic endpoint selection uses the current network and rejects stale endpoints without an account refresh.
- F4: normalize provider IPv4 and IPv6 socket addresses before native lookup.
- F5: a local license does not prove WARP+; refresh uses server account state.
- F6: GET refresh retains the existing token when the response omits it.
- F7: local Zero Trust import validates keys, interface addresses, and endpoint before activation.
- F8: an explicit user action creates or imports a supported profile with loading and error states.
- F9: endpoint probes keep packet buffers off bounded worker stacks.
- F10: scanner pools include each explicit IP family without a DNS dependency; IPv6 selection does not retain a failed IPv4 address.
- Keep each finding in a separate fix commit. Run affected tests, independent review, static analysis, locale gates, and ci-gates before integration.
- Record local, Android, artifact, hosted CI, and external compatibility evidence separately. Do not use real credentials or external transport probes without separate authorization.

## Parallel ownership

- Coordinator: this task, OpenSpec artifacts, combined gates, commit integration, main, remote push, and serialized generated API/architecture artifacts.
- Native worker: F1/F2 and native F4 in warp-core runtime/tunnel/config and warp-android/vpn_protect. F2 WARP engine API policy and engine mapper are serialized with F8.
- Control-plane worker: F3/F5/F6/F7, existing Kotlin WARP services, runtime resolver/callers, credential account merge; separate Kotlin F2 propagation and F4 parser patches for combined commits.
- Enrollment worker: F8 app UI, all ten locale resources, translation export, new service facade, new native keypair modules and lib exports. It supplies the engine bridge patch after F2 releases that file.
- Final quality fixes: the coordinator owns the runtime resolver, enrollment flow, and affected tests in the integration worktree. The control-plane worker owns only endpoint parsing, Zero Trust input validation, and scanner control flow in its separate worktree. These lanes do not share files.
- Rebase handoff: after the frozen artifact run and nine fix commits, the native worker alone owns the integration worktree during the shared WireGuard rebase and native conflict resolution. The coordinator makes no repository edits during this phase. Ownership returns before combined validation and evidence updates.
- F10: the scanner worker owns only WarpEndpointScannerSupport.kt and its existing candidate-pool tests in a separate scanner-families worktree. The coordinator owns the requirement, task state, integration, and final checks. The worker does not edit the integration worktree.
- No dependency, Cargo.lock, proto, golden, or quality-baseline edits are planned. The F2 guarded-probe and F8 keypair JNI exports need an exact two-symbol ELF diff and explicit approval under JNI_CONTRACT.md. Coordinator owns any approved snapshot regeneration. Every writer uses its own worktree; reviews are read-only and precede commits.
