## Purpose

Define the observable completion contract for Epic - Remove Cloudflare from critical path. Remove Cloudflare from every critical path for Russian users while keeping it as an optional low-priority fallback where it still works

## ADDED Requirements

### Requirement: REQ-EPC-1786264762917110-001 — No production profile requires Cloudflare for primary transport. — Code/automat…

The RIPDPI implementation MUST satisfy this portfolio criterion: No production profile requires Cloudflare for primary transport. — Code/automation landed; operator action pending. The client now gates Cloudflare binary extraction to publish mode (b7b32df5b) so non-publish profiles no longer pull in the Cloudflare path, an….

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that No production profile requires Cloudflare for primary transport. — Code/automation landed; operator action pending. The client now gates Cloudflare binary extraction to publish mode (b7b32df5b) so non-publish profiles no longer pull in the Cloudflare path, an…

### Requirement: REQ-EPC-1786264762917110-002 — Subscription delivery works through at least one non-Cloudflare endpoint. — Cod…

The RIPDPI implementation MUST satisfy this portfolio criterion: Subscription delivery works through at least one non-Cloudflare endpoint. — Code/automation landed; operator action pending. The deploy repo adds an opt-in continuous payload mirror on the subscription host (5ab17cf). It is opt-in and requires the operator to….

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Subscription delivery works through at least one non-Cloudflare endpoint. — Code/automation landed; operator action pending. The deploy repo adds an opt-in continuous payload mirror on the subscription host (5ab17cf). It is opt-in and requires the operator to…

### Requirement: REQ-EPC-1786264762917110-003 — DNS bootstrap and tunneled DNS have non-Cloudflare paths. — Addressed by ad5408…

The RIPDPI implementation MUST satisfy this portfolio criterion: DNS bootstrap and tunneled DNS have non-Cloudflare paths. — Addressed by ad540878e. CriticalResolverChainBuilder in core/data/settings filters DnsProviderCloudflare and DnsProviderCloudflareIp from the critical resolver chain by default; Cloudflare DNS is opt….

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that DNS bootstrap and tunneled DNS have non-Cloudflare paths. — Addressed by ad540878e. CriticalResolverChainBuilder in core/data/settings filters DnsProviderCloudflare and DnsProviderCloudflareIp from the critical resolver chain by default; Cloudflare DNS is opt…

### Requirement: REQ-EPC-1786264762917110-004 — Cloudflare XHTTP/HTTPS profiles are manual or low priority when degraded. — Cod…

The RIPDPI implementation MUST satisfy this portfolio criterion: Cloudflare XHTTP/HTTPS profiles are manual or low priority when degraded. — Code/automation landed; operator action pending. The client gating (b7b32df5b) keeps Cloudflare off the default non-publish path, and the non-CDN XHTTP fallback frontend (79f2f5e) pro….

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Cloudflare XHTTP/HTTPS profiles are manual or low priority when degraded. — Code/automation landed; operator action pending. The client gating (b7b32df5b) keeps Cloudflare off the default non-publish path, and the non-CDN XHTTP fallback frontend (79f2f5e) pro…

### Requirement: REQ-EPC-1786264762917110-005 — Monitoring detects Cloudflare-like 16 KB payload throttling, not just TLS succe…

The RIPDPI implementation MUST satisfy this portfolio criterion: Monitoring detects Cloudflare-like 16 KB payload throttling, not just TLS success. — Deploy repo adds a per-ASN ~16 KiB payload-throttling probe (a2d4d06); the detection capability — distinct from plain TLS-success checks — is implemented. (Continuous coverag….

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Monitoring detects Cloudflare-like 16 KB payload throttling, not just TLS success. — Deploy repo adds a per-ASN ~16 KiB payload-throttling probe (a2d4d06); the detection capability — distinct from plain TLS-success checks — is implemented. (Continuous coverag…

### Requirement: REQ-EPC-1786264762917110-006 — Baseline already non-CF: confirm enablenginxxhttp: true (groupvars/all.yml) — t…

The RIPDPI implementation MUST satisfy this portfolio criterion: Baseline already non-CF: confirm enablenginxxhttp: true (groupvars/all.yml) — the P1 direct nginx-xhttp host is the non-Cloudflare primary; CF-fronted XHTTP is only the optional enablecdnfront tier. (Commit 79f2f5e/be7cd31 adds a second direct fallback fronte….

#### Scenario: Verify criterion 6

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Baseline already non-CF: confirm enablenginxxhttp: true (groupvars/all.yml) — the P1 direct nginx-xhttp host is the non-Cloudflare primary; CF-fronted XHTTP is only the optional enablecdnfront tier. (Commit 79f2f5e/be7cd31 adds a second direct fallback fronte…

### Requirement: REQ-EPC-1786264762917110-007 — Enable the opt-in second direct frontend: nginxxhttp.fallbackenabled: true (rol…

The RIPDPI implementation MUST satisfy this portfolio criterion: Enable the opt-in second direct frontend: nginxxhttp.fallbackenabled: true (role ansible/roles/nginx-xhttp).

#### Scenario: Verify criterion 7

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Enable the opt-in second direct frontend: nginxxhttp.fallbackenabled: true (role ansible/roles/nginx-xhttp)

### Requirement: REQ-EPC-1786264762917110-008 — Pick a free public port via nginxxhttpfallbackport (default 2083); the role's p…

The RIPDPI implementation MUST satisfy this portfolio criterion: Pick a free public port via nginxxhttpfallbackport (default 2083); the role's pre-flight assert rejects collisions with xrayport/xrayfallbackport/nginxxhttppublicport/cdnfront.port.

#### Scenario: Verify criterion 8

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Pick a free public port via nginxxhttpfallbackport (default 2083); the role's pre-flight assert rejects collisions with xrayport/xrayfallbackport/nginxxhttppublicport/cdnfront.port

### Requirement: REQ-EPC-1786264762917110-009 — (If serving a distinct domain) set nginxxhttp.fallbackservername + fallbackcert…

The RIPDPI implementation MUST satisfy this portfolio criterion: (If serving a distinct domain) set nginxxhttp.fallbackservername + fallbackcertpem + fallbackkeypem; the firewall opens the port under the same fallbackenabled flag. TLS must terminate directly — no Cloudflare real-IP / Origin-CA in front.

#### Scenario: Verify criterion 9

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that (If serving a distinct domain) set nginxxhttp.fallbackservername + fallbackcertpem + fallbackkeypem; the firewall opens the port under the same fallbackenabled flag. TLS must terminate directly — no Cloudflare real-IP / Origin-CA in front

### Requirement: REQ-EPC-1786264762917110-010 — Host: rides the existing P1 nginx-xhttp host (a second server block on a distin…

The RIPDPI implementation MUST satisfy this portfolio criterion: Host: rides the existing P1 nginx-xhttp host (a second server block on a distinct port); a separate host is not strictly required.

#### Scenario: Verify criterion 10

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Host: rides the existing P1 nginx-xhttp host (a second server block on a distinct port); a separate host is not strictly required

### Requirement: REQ-EPC-1786264762917110-011 — Repoint clients: make emit-singbox CLIENT=<name> regenerates the bundle so the…

The RIPDPI implementation MUST satisfy this portfolio criterion: Repoint clients: make emit-singbox CLIENT=<name> regenerates the bundle so the XHTTP outbound targets the direct host, not a CF front.

#### Scenario: Verify criterion 11

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Repoint clients: make emit-singbox CLIENT=<name> regenerates the bundle so the XHTTP outbound targets the direct host, not a CF front

### Requirement: REQ-EPC-1786264762917110-012 — Turn on the role: enablesubscriptionhost: true (groupvars/all.yml)

The RIPDPI implementation MUST satisfy this portfolio criterion: Turn on the role: enablesubscriptionhost: true (groupvars/all.yml).

#### Scenario: Verify criterion 12

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Turn on the role: enablesubscriptionhost: true (groupvars/all.yml)

### Requirement: REQ-EPC-1786264762917110-013 — Enable the mirror: subscription.mirror.enabled: true (role ansible/roles/subscr…

The RIPDPI implementation MUST satisfy this portfolio criterion: Enable the mirror: subscription.mirror.enabled: true (role ansible/roles/subscription-host, defaults/main.yml).

#### Scenario: Verify criterion 13

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Enable the mirror: subscription.mirror.enabled: true (role ansible/roles/subscription-host, defaults/main.yml)

### Requirement: REQ-EPC-1786264762917110-014 — Choose subscription.mirror.backend: rsync (default, rsync-over-ssh) or restic

The RIPDPI implementation MUST satisfy this portfolio criterion: Choose subscription.mirror.backend: rsync (default, rsync-over-ssh) or restic.

#### Scenario: Verify criterion 14

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Choose subscription.mirror.backend: rsync (default, rsync-over-ssh) or restic

### Requirement: REQ-EPC-1786264762917110-015 — Cadence: subscription.mirror.interval (default 5min, systemd vpn-sub-mirror.tim…

The RIPDPI implementation MUST satisfy this portfolio criterion: Cadence: subscription.mirror.interval (default 5min, systemd vpn-sub-mirror.timer/.service, outbound-only pull).

#### Scenario: Verify criterion 15

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Cadence: subscription.mirror.interval (default 5min, systemd vpn-sub-mirror.timer/.service, outbound-only pull)

### Requirement: REQ-EPC-1786264762917110-016 — Provision: a dedicated subscription/delivery host running the role + a reachabl…

The RIPDPI implementation MUST satisfy this portfolio criterion: Provision: a dedicated subscription/delivery host running the role + a reachable build-worker source (the rsync/restic origin). No new public surface; payloads served by the loopback vpn-bootstrap service.

#### Scenario: Verify criterion 16

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Provision: a dedicated subscription/delivery host running the role + a reachable build-worker source (the rsync/restic origin). No new public surface; payloads served by the loopback vpn-bootstrap service

### Requirement: REQ-EPC-1786264762917110-017 — Keep enablecdnfront: false (baseline in groupvars/all.yml + vpn-fullstack.yml +…

The RIPDPI implementation MUST satisfy this portfolio criterion: Keep enablecdnfront: false (baseline in groupvars/all.yml + vpn-fullstack.yml + vpn-p1p2.yml) so no CF outbound is auto-emitted into the client urltest pool.

#### Scenario: Verify criterion 17

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Keep enablecdnfront: false (baseline in groupvars/all.yml + vpn-fullstack.yml + vpn-p1p2.yml) so no CF outbound is auto-emitted into the client urltest pool

### Requirement: REQ-EPC-1786264762917110-018 — Client auto-failover is already wired: scripts/emit-singbox.sh emits a urltest…

The RIPDPI implementation MUST satisfy this portfolio criterion: Client auto-failover is already wired: scripts/emit-singbox.sh emits a urltest group (tag auto, url: generate204, interval: 5m, tolerance: 50) that passes over a degraded/throttling outbound — provided the direct non-CDN outbound is in the bundle (it is when….

#### Scenario: Verify criterion 18

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Client auto-failover is already wired: scripts/emit-singbox.sh emits a urltest group (tag auto, url: generate204, interval: 5m, tolerance: 50) that passes over a degraded/throttling outbound — provided the direct non-CDN outbound is in the bundle (it is when…

### Requirement: REQ-EPC-1786264762917110-019 — Per-cohort repoint on degradation: edit the cohort groupvars (vpn-fullstack.yml…

The RIPDPI implementation MUST satisfy this portfolio criterion: Per-cohort repoint on degradation: edit the cohort groupvars (vpn-fullstack.yml / vpn-p1p2.yml) + re-run make emit-singbox CLIENT=<name> (see deploy-repo RUNBOOK-add-fallback.md).

#### Scenario: Verify criterion 19

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Per-cohort repoint on degradation: edit the cohort groupvars (vpn-fullstack.yml / vpn-p1p2.yml) + re-run make emit-singbox CLIENT=<name> (see deploy-repo RUNBOOK-add-fallback.md)

### Requirement: REQ-EPC-1786264762917110-020 — Wire the degradation signal: enable the per-ASN payload-throttle probe cron by…

The RIPDPI implementation MUST satisfy this portfolio criterion: Wire the degradation signal: enable the per-ASN payload-throttle probe cron by exporting PAYLOADTHROTTLEHOST (scripts/install-operator-crons.sh — opt-in @daily; off until set), and pair it with ansible/roles/watchdog (enablewatchdog: true — already the defaul….

#### Scenario: Verify criterion 20

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Wire the degradation signal: enable the per-ASN payload-throttle probe cron by exporting PAYLOADTHROTTLEHOST (scripts/install-operator-crons.sh — opt-in @daily; off until set), and pair it with ansible/roles/watchdog (enablewatchdog: true — already the defaul…

### Requirement: REQ-EPC-1786264762917110-021 — Honest scope: demotion is signal (probe) + manual enablecdnfront toggle + clien…

The RIPDPI implementation MUST satisfy this portfolio criterion: Honest scope: demotion is signal (probe) + manual enablecdnfront toggle + client-side urltest auto-failover — there is no closed-loop auto-disable of CF in the deploy code. The checklist closes the milestone operationally, not by automation alone.

#### Scenario: Verify criterion 21

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Honest scope: demotion is signal (probe) + manual enablecdnfront toggle + client-side urltest auto-failover — there is no closed-loop auto-disable of CF in the deploy code. The checklist closes the milestone operationally, not by automation alone
