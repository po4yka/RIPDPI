---
id: EPC-1786264762917110
title: Epic - Remove Cloudflare from critical path
kind: epic
status: blocked
area: epic
priority: critical
owner: unassigned
parent: null
blocked_by: []
spec_mode: required
openspec_change: epc-1786264762917110-epic-remove-cloudflare-from-critical-path
created: 2026-05-01
updated: 2026-08-27
status_detail: Producer emission of optional mirror and explicit selector metadata requires sibling-repository authorization; real non-Cloudflare endpoint provisioning and live delivery acceptance require separate deployment authorization.
status_note: code/automation landed across client + deploy; non-Cloudflare hosts await operator provisioning
---

## Goal

Remove Cloudflare from every critical path for Russian users while keeping it as an optional low-priority fallback where it still works.

## Scope

- In scope: dependency audit, non-Cloudflare delivery, non-Cloudflare DNS fallback, direct/non-CF HTTPS fallback, client selector changes, large-payload health checks, per-ISP monitoring, and migration runbook.
- Out of scope: deleting all Cloudflare usage, Cloudflare enterprise static IP procurement, and storing live endpoints or tokens in TaskNotes.

## Implementation ownership — 2026-08-27

- DNS implementation lane: production encrypted-DNS candidate planning in `core/data/model`, startup DNS selection in `core/service`, and their existing data/service regression tests.
- Subscription implementation lane: production selector and subscription-refresh integration in `app`, plus focused tests, in `/private/tmp/ripdpi-critical-subscriptions-20260827`.
- Integration lane: portfolio/OpenSpec records, generated board, review, combined-tree validation, and integration to `main`.
- Serialized files: the integration lane alone owns dependency locks, storage schemas, locale resources, golden fixtures, and task records. Storage changes require a coordinated handoff before implementation.
- Storage handoff: the subscription lane exclusively owns `ProxyGroupStores.kt` and subscription import models/parsers for explicit delivery-mirror and automatic-selection policy metadata. `SelectorSelectionStore.kt` and its data-module tests were implemented by the integration lane and handed to the subscription lane as an exact reviewed patch; that lane owns its three app test doubles and prober consumers. Selection provenance and atomic compare-and-set must distinguish an explicit manual choice from an automatic or pre-existing selection. No dependency, locale, golden, JNI, or protobuf changes are assigned.
- Coordinator handoff: the integration lane owns `SelectorUrltestCoordinator.kt` and adds its refresh regression to the handed-off `SelectorUrltestProberTest.kt`; the subscription lane freezes that test after its CAS regression passes.
- Export boundary handoff: the integration lane owns `core/data/.../backup/BackupV1.kt` and `BackupGroupRedactionTest.kt` so SHARE exports remove all mirror credentials and endpoints; FULL export remains explicit credential export.
- Completion requires production callers to use the policy; isolated helper tests are insufficient. Operator provisioning and live non-Cloudflare delivery remain separate acceptance gates and are not authorized by a Git push.

## Status

In progress. The 2026-08-27 production-path audit found client integration gaps beyond the previously listed operator work. Repairs cover automatic DNS fallback and remembered startup policy, subscription mirror delivery, and explicit selector policy. The epic remains open until the integrated client changes and real non-Cloudflare provisioning/delivery satisfy their separate acceptance gates.

## Child work

- Audit Cloudflare-only dependencies (closed task)
- Provision non-Cloudflare delivery host
- Add multi-delivery subscription mirror support (closed task)
- Add Cloudflare large-payload healthcheck (closed task)
- Demote Cloudflare profiles from default auto selection (closed task)
- Add non-Cloudflare HTTPS XHTTP fallback frontend
- Remove Cloudflare DNS from critical resolver chain (closed task)
- Add Cloudflare degradation classification runbook (closed task)
- Add Russian ISP payload monitoring probes

## Milestones

- [ ] No production profile requires Cloudflare for primary transport. — *Code/automation landed; operator action pending.* The client now gates Cloudflare binary extraction to publish mode (`b7b32df5b`) so non-publish profiles no longer pull in the Cloudflare path, and a direct non-CDN HTTPS XHTTP frontend exists on the deploy side (`79f2f5e`). Whether a given production profile actually avoids Cloudflare depends on the operator deploying the non-CDN frontend on a real host and pointing profiles at it.
- [ ] Subscription delivery works through at least one non-Cloudflare endpoint. — *Code/automation landed; operator action pending.* The deploy repo adds an opt-in continuous payload mirror on the subscription host (`5ab17cf`). It is opt-in and requires the operator to enable it and provision the mirror endpoint before this is true in production.
- [x] DNS bootstrap and tunneled DNS have non-Cloudflare paths. — *Addressed by `ad540878e`.* `CriticalResolverChainBuilder` in `core/data/settings` filters `DnsProviderCloudflare` and `DnsProviderCloudflareIp` from the critical resolver chain by default; Cloudflare DNS is opt-in via `CriticalResolverProfile.CloudflareAllowed`. 10 tests lock in the exclusion semantics.
- [ ] Cloudflare XHTTP/HTTPS profiles are manual or low priority when degraded. — *Code/automation landed; operator action pending.* The client gating (`b7b32df5b`) keeps Cloudflare off the default non-publish path, and the non-CDN XHTTP fallback frontend (`79f2f5e`) provides the alternative to fail over to. End-to-end "demote when degraded" still depends on operator selector/priority configuration against live endpoints, so this is not yet fully done.
- [x] Monitoring detects Cloudflare-like 16 KB payload throttling, not just TLS success. — Deploy repo adds a per-ASN ~16 KiB payload-throttling probe (`a2d4d06`); the detection capability — distinct from plain TLS-success checks — is implemented. (Continuous coverage across all RU ASNs still depends on operator-run probe hosts, but the throttling-detection automation itself has landed.)

## Risks

- Direct fallback hostnames change the origin exposure threat model.
- Alternative CDNs can become the same failure class if all choices are foreign hyperscale edges.
- Adding multiple delivery mirrors must not create shared subscription URLs or token leakage.

## Notes

Keep live hostnames, tokens, and provider details out of this note. Store sensitive operational mapping under `ops/live-infra/`.

## Resolution

Status as of 2026-05-30: **code/automation landed in both repos; epic stays open pending operator provisioning of real non-Cloudflare hosts and a non-Cloudflare DNS path.** No live hostnames or tokens are recorded here.

What landed, and WHERE:

- Client (this repo, RIPDPI):
  - `b7b32df5b` — fix(service): gate Cloudflare publish binary extraction to publish mode. Cloudflare binary extraction now only happens in publish mode, so non-publish profiles no longer pull the Cloudflare code path onto the critical path.
- Deploy repo (ripdpi-vpn-deploy):
  - `5ab17cf` — feat(subscription-host): add opt-in continuous payload mirror. Provides a non-Cloudflare subscription delivery endpoint (opt-in; operator must enable and provision the mirror host).
  - `79f2f5e` — feat(nginx-xhttp): add opt-in direct non-CDN HTTPS XHTTP fallback frontend. Provides a direct, non-Cloudflare HTTPS XHTTP frontend to fail over to (opt-in; operator must deploy it on a real host).
  - `a2d4d06` — feat(monitoring): add per-ASN ~16 KiB payload-throttling probe. Detects Cloudflare-like large-payload throttling rather than relying on TLS-handshake success alone.

Honest milestone state:

- Met (code): 16 KB payload-throttling monitoring (`a2d4d06`).
- Code/automation landed, operator action pending: no-Cloudflare primary transport, non-Cloudflare subscription delivery, Cloudflare demotion-when-degraded. The code and automation exist (client gating + deploy-side opt-in mirror/frontend), but each requires the operator to provision and enable real non-Cloudflare hosts before it is true in production.
- Not addressed by this batch: non-Cloudflare DNS bootstrap / tunneled DNS path — tracked separately.

## Operator provisioning checklist (sibling `ripdpi-vpn-deploy`)

The three open milestones are **operator/deploy-side and gated on provisioning real non-Cloudflare hosts** — they are NOT implemented in this (client) repo. The code/automation has landed in `ripdpi-vpn-deploy`; what remains is the operator enabling and provisioning it. The knob names below are real Ansible vars/roles; **do not record live hostnames, tokens, IPs, key material, or ASN-specific endpoints here** (see Notes — store those under `ops/live-infra/`). Documented default ports (e.g. 2083) are fine as defaults, not as a live host's bindings.

> Hash note: the three landed deploy commits were rebased into `main` — `a2d4d06`→`326771c` (16 KiB probe), `79f2f5e`→`be7cd31` (non-CDN XHTTP fallback), `5ab17cf`→`80f27f3` (subscription mirror). Same file sets; audited present at HEAD on 2026-06-11.

### Milestone 1 — no production profile requires Cloudflare for primary transport
- [ ] **Baseline already non-CF:** confirm `enable_nginx_xhttp: true` (`group_vars/all.yml`) — the P1 **direct** nginx-xhttp host is the non-Cloudflare primary; CF-fronted XHTTP is only the optional `enable_cdn_front` tier. (Commit `79f2f5e`/`be7cd31` adds a *second* direct fallback frontend for CF-outage survival; it is not the primary path itself.)
- [ ] Enable the opt-in second direct frontend: `nginx_xhttp.fallback_enabled: true` (role `ansible/roles/nginx-xhttp`).
- [ ] Pick a free public port via `nginx_xhttp_fallback_port` (default `2083`); the role's pre-flight `assert` rejects collisions with `xray_port`/`xray_fallback_port`/`nginx_xhttp_public_port`/`cdn_front.port`.
- [ ] (If serving a distinct domain) set `nginx_xhttp.fallback_server_name` + `fallback_cert_pem` + `fallback_key_pem`; the firewall opens the port under the same `fallback_enabled` flag. **TLS must terminate directly — no Cloudflare real-IP / Origin-CA in front.**
- [ ] Host: rides the existing P1 nginx-xhttp host (a second server block on a distinct port); a separate host is not strictly required.
- [ ] Repoint clients: `make emit-singbox CLIENT=<name>` regenerates the bundle so the XHTTP outbound targets the direct host, not a CF front.

### Milestone 2 — subscription delivery through ≥1 non-Cloudflare endpoint
- [ ] Turn on the role: `enable_subscription_host: true` (`group_vars/all.yml`).
- [ ] Enable the mirror: `subscription.mirror.enabled: true` (role `ansible/roles/subscription-host`, `defaults/main.yml`).
- [ ] Choose `subscription.mirror.backend`: `rsync` (default, rsync-over-ssh) or `restic`.
  - rsync: set `subscription.mirror.source` (remote spec), `subscription.mirror.rsync_opts` (default `-az --delete`), SSH key secret `subscription.mirror.ssh_key` → `ssh_key_path`.
  - restic: set `subscription.mirror.restic_repo`, `restic_snapshot_path`, secret `restic_password` → `restic_password_file`.
- [ ] Cadence: `subscription.mirror.interval` (default `5min`, systemd `vpn-sub-mirror.timer`/`.service`, outbound-only pull).
- [ ] Provision: a dedicated subscription/delivery host running the role + a reachable build-worker source (the rsync/restic origin). No new public surface; payloads served by the loopback `vpn-bootstrap` service.

### Milestone 3 — Cloudflare XHTTP/HTTPS profiles manual / low-priority when degraded
- [ ] Keep `enable_cdn_front: false` (baseline in `group_vars/all.yml` + `vpn-fullstack.yml` + `vpn-p1p2.yml`) so no CF outbound is auto-emitted into the client urltest pool.
- [ ] Client auto-failover is already wired: `scripts/emit-singbox.sh` emits a `urltest` group (tag `auto`, `url: generate_204`, `interval: 5m`, `tolerance: 50`) that passes over a degraded/throttling outbound — **provided the direct non-CDN outbound is in the bundle** (it is when `enable_nginx_xhttp: true`).
- [ ] Per-cohort repoint on degradation: edit the cohort `group_vars` (`vpn-fullstack.yml` / `vpn-p1p2.yml`) + re-run `make emit-singbox CLIENT=<name>` (see deploy-repo `RUNBOOK-add-fallback.md`).
- [ ] Wire the degradation signal: enable the per-ASN payload-throttle probe cron by exporting `PAYLOAD_THROTTLE_HOST` (`scripts/install-operator-crons.sh` — opt-in @daily; **off until set**), and pair it with `ansible/roles/watchdog` (`enable_watchdog: true` — already the default; verify it has not been overridden in your host vars) for alert-and-demote.
- [ ] **Honest scope:** demotion is *signal (probe) + manual `enable_cdn_front` toggle + client-side urltest auto-failover* — there is **no closed-loop auto-disable of CF** in the deploy code. The checklist closes the milestone operationally, not by automation alone.

## Links

- cloudflare-ru-critical-path-removal-2026-05-01
- vps-proxy-fleet
- [[ripdpi-android]]
- [[Epic - Fail-closed Android VPN policy engine]]
- Epic - Subscription and profile import
- Child issues: 6

## Work log

- 2026-08-27: Production-path audit correction: the earlier `CriticalResolverChainBuilder`, selector policy, and subscription-mirror helper tests did not prove their use by runtime callers. This open epic owns the production integration repairs. Automatic DNS fallback exclusion is implemented in `8d901b6b3`; the data module (771 tests), failover controller (10 tests), and `staticAnalysis` passed locally. Startup preference replay and subscription/selector integration require their own final checks. Client-recognized mirror/classification metadata does not prove that the sibling emitter or real hosts supply it; provisioning and live delivery remain required.
- 2026-06-05: DNS milestone verified done via `ad540878e` + `CriticalResolverChain.kt` (Cloudflare excluded from critical chain by default); 3 remaining milestones (non-CF primary transport, non-CF subscription delivery, CF demotion-when-degraded) have code/automation landed but await operator provisioning of real non-Cloudflare hosts; 2 open child tasks (provision non-CF delivery host, Russian ISP payload monitoring probes) remain unresolved; epic stays in doing.
- 2026-06-05 (re-audit): Source-verified both [x] milestones. DNS exclusion — the "10 tests" claim confirmed (4+3+3=10 `@Test` across `CloudflareDnsNotInCriticalChainTest.kt`, `CloudflareDnsRemovedFromCriticalListTest.kt`, `CloudflareDnsExplicitOnlyTest.kt` under `core/data/settings`); `CriticalResolverChainBuilder.build()` filters `cloudflareProviderIds` (`DnsProviderCloudflare`/`DnsProviderCloudflareIp`) unless `CriticalResolverProfile.CloudflareAllowed`. 16 KB throttling monitoring — deploy-repo commit `a2d4d06` (per-ASN ~16 KiB payload-throttling probe) confirmed present in sibling `~/GitHub/ripdpi-vpn-deploy`, as are `5ab17cf` and `79f2f5e`; client gating `b7b32df5b` confirmed in this repo. The named child items (provision non-CF host, ISP probes) are operator/deploy-side, not task files in this directory. Status unchanged: doing.
- 2026-06-11 (audit + operator checklist): Re-verified both `[x]` code milestones **still hold in source** and ran the client tests. (1) DNS exclusion — `core/data/settings/.../CriticalResolverChain.kt:28` defines `cloudflareProviderIds = setOf(DnsProviderCloudflare, DnsProviderCloudflareIp)`; `build()` excludes them by default and `filterForCriticalPath` keeps them only when `CriticalResolverProfile.CloudflareAllowed` (lines 40-61); `:core:data:settings:testDebugUnitTest` BUILD SUCCESSFUL with the milestone's **10 tests green** (`CloudflareDnsNotInCriticalChainTest` 4 + `CloudflareDnsRemovedFromCriticalListTest` 3 + `CloudflareDnsExplicitOnlyTest` 3, 0 failures), plus the demotion tests (`CloudflareDegradationClassTest` 3, `selector.CloudflareDegradedExclusionTest` 4) green. No drift. (2) 16 KiB throttling monitoring — sibling-repo probe `a2d4d06` (now `326771c` in deploy `main`) present at HEAD as `scripts/probe-payload-throttle.sh` (executable); it drives a `1024..32768` size ladder with `THRESHOLD=16384` and emits `throttled` only when the small-payload baseline succeeds **and** a ≥16 KiB step shows a completion cliff or RTT spike — genuinely distinct from TLS-handshake success (`blocked`/`unknown` cover plain connectivity failure). The deploy commits are present (`79f2f5e`→`be7cd31`, `5ab17cf`→`80f27f3`). Wrote the **operator provisioning checklist** for the three remaining milestones into this epic (real Ansible knob names, no secrets). No provisioning implemented here (operator/deploy-gated). Status stays `doing` (provisioning pending). Audited the secret-redaction boundary: only knob/var/role names recorded — no live hostnames/tokens/IPs/keys/ASN endpoints.

### Current client integration boundary — 2026-08-27

- Production DNS fallback and remembered startup policy now share the automatic-provider rule, including the catalog malware-filtering profile. Explicit user selection remains available. The catalog regression was observed failing before the fix and passing afterward in data and service tests.
- The client recognizes optional, validated `ripdpi.subscription_mirrors` and `ripdpi.cloudflare_outbound_tags` fields. Existing per-device storage seals mirror credentials; SHARE exports must remove the whole mirror set. Redirects cannot forward endpoint credentials, and terminal revocation/expiry is not bypassed through another mirror.
- Automatic selector decisions use explicit operator classification and compare-and-set selection snapshots; an explicit manual Cloudflare choice remains pinned. An existing automatic fallback is retained only until a direct candidate is reachable; this change does not add a forced runtime teardown. Latency probes measure TCP connection time, not payload health or real egress correctness.
- Producer integration is still pending: the sibling subscription generator does not yet emit these new optional fields. Its canonical schema fixture is unchanged. Enabling real mirror delivery requires a producer update plus separately authorized endpoint provisioning and deployment; client tests are not evidence that those actions happened.

### Integrated local verification — 2026-08-27

Source `a78d1f18a620846e31e41785347328d1a327a05a` passed the combined data/runtime-state/service/app unit suites (779 + 181 + 1,852 + 1,765 = 4,577 tests, zero failures/errors/skips), `staticAnalysis`, Full/Simple Android lint, and architecture health without new or worsened indicators. See the linked OpenSpec verification record for the exact command and host-only boundary. Remote CI, physical acceptance, producer integration, and deployment are separate evidence; no unresolved acceptance is marked complete by these local results.
