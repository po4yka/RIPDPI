# Relay Deployment Operations

This document defines the minimum deployment-plane contract for owner-operated RIPDPI relay paths. RIPDPI remains a no-backend Android client: the repository does not operate a public service, store live endpoints, or accept user traffic. When a maintainer or user chooses to run relays they control, this document and `quality/release-gates/fleet-release-cadence-policy.json` define the first-class controls that must exist before a relay profile is promoted as an operator-managed path.

## Scope

- Applies to owner-operated VLESS Reality/xHTTP, Hysteria2, TUIC, ShadowTLS, NaiveProxy, MASQUE, AmneziaWG, WARP-derived, and Cloudflare Tunnel relay profiles when those profiles are promoted through the fleet release-gate process.
- Does not apply to the direct on-device strategy path, user-imported private profiles that are never promoted by this repo, or third-party infrastructure not controlled by the operator.
- Live endpoints, raw probe logs, private keys, client UUIDs, bearer tokens, provider account identifiers, tunnel credentials, and per-device credential material belong outside the repo under operator-controlled storage such as `ops/live-infra/`.

## Deployment Plane Map

| Plane | Owner | Repo artifact | Live artifact |
| --- | --- | --- | --- |
| Control plane | Profile publisher | Relay profile JSON, release-gate policy, sanitized promotion report | Provider account, DNS records, profile distribution channel |
| Data plane | Relay host | Declared listener, protocol kind, routing intent | VM/container, listener sockets, firewall rules, certificates, per-device credentials |
| Diagnostic plane | Release gate | Gate result IDs and redacted PASS/WARN/FAIL summaries | Probe runner, packet capture if explicitly enabled, raw endpoint measurements |
| Deployment plane | Operator | `docs/relay-deployment-operations.md`, `quality/release-gates/fleet-release-cadence-policy.json`, `scripts/ci/check_fleet_release_gates.py` | IaC state, secret store, backup store, incident log, rebuild scripts |

The release gate must prove the deployment plane before promotion. A green client runtime test is not sufficient evidence that the relay deployment is disposable, revocable, patched, or safe after IP burn.

## Promotion Contract

Every owner-operated relay promotion must satisfy the `relay-deployment` gate set and the matching staging or production gate set. The required controls are `certificate-rotation-drill`, `per-device-credential-revocation`, `disposable-relay-rebuild`, `firewall-drift-check`, and `incident-playbook-ready`.

Gate results must be sanitized. The committed result may name the gate ID, state, scoped `N/A` reason, and high-level evidence summary. It must not include endpoint hostnames, IP addresses, credential IDs, certificate PEMs, token fragments, private URLs, provider account identifiers, or raw logs.

## Certificate Rotation

Problem addressed: a relay can keep passing connectivity checks while its certificate is near expiry, has the wrong chain, or cannot be rotated without downtime.

Required evidence:

- `certificate-rotation-drill` passes for the release or rotation gate.
- Staging serves the new certificate chain before production promotion.
- Production serves the new chain after promotion and the old certificate is no longer served by the promoted endpoint.
- The sanitized report records expiry margin, issuer family, and certificate fingerprint class without storing PEM material or hostnames.
- Rollback is tested by restoring the previous known-good listener or demoting the profile to a fallback relay path.

Recommended verification:

- Query the endpoint from at least one non-censored vantage and one target-region vantage.
- Verify SNI and ALPN match the relay profile.
- Verify the client accepts the new chain and rejects a deliberately expired or mismatched test chain in staging.

## Per-Device Credential Revocation

Problem addressed: shared credentials make a single leaked profile impossible to revoke without rotating every device.

Required evidence:

- `per-device-credential-revocation` passes at least weekly and before production promotion.
- A revoked device credential fails to authenticate on the promoted relay.
- An unrevoked canary credential still authenticates after the revocation.
- Profile export, logs, and sanitized reports contain no revoked credential material.
- Revocation does not require changing the app package, app settings schema, or unrelated users' credentials.

Recommended verification:

- Mint two canary credentials for the same relay profile, revoke one, and prove only the revoked credential fails.
- Confirm the server-side denylist or credential store is reloaded without reopening admin endpoints.
- Confirm stale client profiles are either rejected or redirected to a documented replacement profile.

## Disposable Rebuild

Problem addressed: a relay that cannot be rebuilt quickly after IP burn, provider suspension, or suspected compromise becomes a long-lived single point of failure.

Required evidence:

- `disposable-relay-rebuild` passes before production promotion.
- A fresh relay instance reaches readiness from checked-in IaC or documented bootstrap inputs.
- The new instance receives only intended listener state, firewall state, certificates, and credentials.
- Host-local mutable state is not required for a successful rebuild.
- The old instance can be drained, demoted, and destroyed without reusing host-local secrets.

Recommended verification:

- Build a staging relay from an empty VM/container using the same documented inputs that production uses.
- Run profile validation and smoke probes against the new relay before switching the profile.
- Destroy the staging instance and confirm no credential, certificate, or tunnel token remains on the host.

## Firewall Drift

Problem addressed: provider firewalls, host firewalls, temporary debugging listeners, and helper metrics endpoints can drift away from the declared relay profile.

Required evidence:

- `firewall-drift-check` passes daily and before production promotion.
- Public listener set matches the declared relay profile.
- Provider firewall and host firewall allowlists match the expected ports and protocols.
- Admin panels, SSH, metrics endpoints, cloudflared metrics, debug HTTP origins, and local helper ports are not reachable from public networks.
- A detected drift is a no-ship condition until the firewall is reconciled or the profile is demoted.

Recommended verification:

- Compare observed TCP/UDP listeners from an external scanner with the profile's declared transport.
- Compare provider firewall rules with host firewall rules.
- Probe metrics and admin paths from outside the host network and require failure unless the endpoint is explicitly public by design.

## Incident Playbooks

Problem addressed: incidents are handled slowly or inconsistently when the relay deployment plane has no written response path.

Required evidence:

- `incident-playbook-ready` passes at least weekly and before production promotion.
- The current operator can execute or point to playbooks for IP burn, credential leak, certificate expiry or mis-issuance, firewall drift, provider block, and partial deploy failure.
- Each playbook names detection signal, immediate containment, user/profile impact, rollback path, replacement path, evidence to preserve, and post-incident hardening.
- Playbooks explicitly state which actions are safe to automate and which require human approval.

Minimum playbooks:

- IP burn or provider block: demote affected profile, stop promoting the burned endpoint, rebuild on a clean provider/ASN when available, run non-ru and target-region smoke checks, and keep old endpoint out of primary/fallback pairs until manually cleared.
- Credential leak: revoke the affected per-device credential, verify revoked credential failure and canary success, rotate any shared bootstrap token, invalidate profile exports that contained the leaked material, and publish a replacement profile without exposing other users.
- Certificate expiry or mis-issuance: remove the expiring/mis-issued certificate from production, serve the previous valid chain or a freshly issued chain, verify SNI/ALPN compatibility, and block promotion until the rotation drill passes.
- Firewall drift or public admin exposure: remove the exposed listener/rule, rotate any credentials that may have been reachable, verify external scans fail for admin/metrics paths, and preserve a sanitized incident note.
- Partial deploy failure: stop rollout, keep existing primary profile active, destroy half-configured replacement hosts, and rerun disposable rebuild before retrying.

## Rollback

Rollback must prefer demotion over silent fallback. If a deployment control fails, mark the relay profile as non-primary or remove it from the promoted fleet, keep the Android client on the last known-good profile, and publish a sanitized gate report explaining which control failed. Do not ship a profile as primary when any required `relay-deployment` gate is `FAIL` or unscoped `N/A`.

## Prevention

Keep the policy and this document synchronized through `scripts/ci/check_fleet_release_gates.py`. Adding a new required deployment control requires updating the machine-readable policy, this document, and the release-gate tests in the same change.
