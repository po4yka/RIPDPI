---
task_id: RST-1786264762917044
change: rst-1786264762917044-add-cloudflare-workers-transport-mode
commit_sha: e7045f7bedbe1605f8d33c15080c62910ccf34bf
local: passed
local_evidence: "Observed local gates on 2026-08-30: targeted RED/GREEN Kotlin unit for session-only Worker config; node --check docs/native/cloudflare-workers/relay.js; node --test docs/native/cloudflare-workers/relay.test.mjs; Gradle :core:engine:testDebugUnitTest :core:service:testDebugUnitTest :core:data:runtime-state:testDebugUnitTest :core:data:model:testDebugUnitTest :app:testGithubFullDebugUnitTest with -Pripdpi.skipNativeBuild=true; Gradle app/core ktlint+detekt; Gradle :app:compileGithubFullDebugKotlin :app:testGithubFullDebugUnitTest :app:lintGithubFullDebug :core:service:lintDebug; cargo test --locked for ripdpi-ws-tunnel, ripdpi-ws-bootstrap, ripdpi-proxy-config, ripdpi-proxy-runtime-adapter, ripdpi-proxy-runtime; cargo clippy --locked for the same crates with -D warnings; cargo fmt --check; cargo metadata --locked --no-deps; python3 scripts/ci/check_cross_language_runtime_contracts.py; python3 scripts/ci/check_native_architecture_contracts.py; python3 scripts/ci/check_architecture_health.py; git diff --check; taskctl validate; strict OpenSpec validate."
remote_ci: not_applicable
remote_ci_evidence: "User explicitly instructed local checks and push without launching, waiting for, or monitoring GitHub CI/CD for this change."
device: not_applicable
device_evidence: No Android device behavior is owned by this portfolio area.
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this portfolio area.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RST-1786264762917044-001 | RST-1786264762917807 | `AppSettingsSectionMapperTest`, `CloudflareWorkerTransportConfigTest`, `WsTunnelWorkerCredentialStoreTest`, `WsTunnelWorkerTransportProvisionerTest`, `BackupExportUseCaseTest`, and `RememberedCloudflareWorkerTransportTest` cover typed URL/ref settings, Keystore-backed bearer resolution, rollback/rotation/clear, and no bearer persistence in AppSettings/backup/remembered policy JSON. | passed |
| REQ-RST-1786264762917044-002 | RST-1786264762917191 | `RipDpiProxyJsonCodecTest`, `ConnectionPolicyResolverTest`, `RememberedCloudflareWorkerTransportTest`, and Rust `ripdpi-ws-tunnel`/`ripdpi-proxy-config`/`ripdpi-proxy-runtime-adapter` tests cover optional Worker routing, canonical `X-Ripdpi-Upstream`, `Authorization: Bearer`, direct-route preservation, TLS SNI/cert verification, HTTP/1.1 ALPN, VPN socket protection ordering, and fake-SNI rejection. | passed |
| REQ-RST-1786264762917044-003 | RST-1786264762917435 | `docs/native/cloudflare-workers/relay.js` plus `docs/native/cloudflare-workers/relay.test.mjs` provide and test a deployable reference Worker with bearer auth, Telegram-only upstream allowlist, binary-only WebSocket relay, size cap, and fail-closed non-upgrade handling. | passed |
| REQ-RST-1786264762917044-004 | RST-1786264762917161 | Rust `ripdpi-ws-tunnel` Worker-route fixture exercises a local TLS WebSocket edge over RFC 6455, captures SNI/ALPN/Host/Auth/Upstream headers, and verifies binary framed round-trip behavior. | passed |
| REQ-RST-1786264762917044-005 | RST-1786264762917285 | `docs/native/cloudflare-workers-ws-edge.md` and `docs/native/cloudflare-tunnel-operations.md` document opt-in deployment, secret rotation, cost/limit/rate behavior, no-open-relay boundaries, and unsupported fake-SNI composition. | passed |
