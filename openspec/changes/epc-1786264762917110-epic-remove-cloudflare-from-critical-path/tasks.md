# EPC-1786264762917110: Epic - Remove Cloudflare from critical path

## Objective

Epic - Remove Cloudflare from critical path

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [ ] EPC-1786264762918019 No production profile requires Cloudflare for primary transport. — Code/automation landed; operator action pending. The client now gates Cloudflare binary extraction to publish mode (b7b32df5b) so non-publish profiles no longer pull in the… #epic !crit @item:EPC-1786264762917110
- [ ] EPC-1786264762918036 Subscription delivery works through at least one non-Cloudflare endpoint. — Code/automation landed; operator action pending. The deploy repo adds an opt-in continuous payload mirror on the subscription host (5ab17cf). It is opt-in and requ… #epic !crit @item:EPC-1786264762917110
- [x] EPC-1786264762918592 DNS bootstrap and tunneled DNS have non-Cloudflare paths. — Addressed by ad540878e. CriticalResolverChainBuilder in core/data/settings filters DnsProviderCloudflare and DnsProviderCloudflareIp from the critical resolver chain by default; C… #epic !crit @item:EPC-1786264762917110
- [ ] EPC-1786264762918290 Cloudflare XHTTP/HTTPS profiles are manual or low priority when degraded. — Code/automation landed; operator action pending. The client gating (b7b32df5b) keeps Cloudflare off the default non-publish path, and the non-CDN XHTTP fallback fr… #epic !crit @item:EPC-1786264762917110
- [x] EPC-1786264762918877 Monitoring detects Cloudflare-like 16 KB payload throttling, not just TLS success. — Deploy repo adds a per-ASN ~16 KiB payload-throttling probe (a2d4d06); the detection capability — distinct from plain TLS-success checks — is implemented.… #epic !crit @item:EPC-1786264762917110
- [ ] EPC-1786264762918045 Baseline already non-CF: confirm enablenginxxhttp: true (groupvars/all.yml) — the P1 direct nginx-xhttp host is the non-Cloudflare primary; CF-fronted XHTTP is only the optional enablecdnfront tier. (Commit 79f2f5e/be7cd31 adds a second di… #epic !crit @item:EPC-1786264762917110
- [ ] EPC-1786264762918746 Enable the opt-in second direct frontend: nginxxhttp.fallbackenabled: true (role ansible/roles/nginx-xhttp) #epic !crit @item:EPC-1786264762917110
- [ ] EPC-1786264762918048 Pick a free public port via nginxxhttpfallbackport (default 2083); the role's pre-flight assert rejects collisions with xrayport/xrayfallbackport/nginxxhttppublicport/cdnfront.port #epic !crit @item:EPC-1786264762917110
- [ ] EPC-1786264762918189 (If serving a distinct domain) set nginxxhttp.fallbackservername + fallbackcertpem + fallbackkeypem; the firewall opens the port under the same fallbackenabled flag. TLS must terminate directly — no Cloudflare real-IP / Origin-CA in front #epic !crit @item:EPC-1786264762917110
- [ ] EPC-1786264762918719 Host: rides the existing P1 nginx-xhttp host (a second server block on a distinct port); a separate host is not strictly required #epic !crit @item:EPC-1786264762917110
- [ ] EPC-1786264762918951 Repoint clients: make emit-singbox CLIENT=<name> regenerates the bundle so the XHTTP outbound targets the direct host, not a CF front #epic !crit @item:EPC-1786264762917110
- [ ] EPC-1786264762918411 Turn on the role: enablesubscriptionhost: true (groupvars/all.yml) #epic !crit @item:EPC-1786264762917110
- [ ] EPC-1786264762918712 Enable the mirror: subscription.mirror.enabled: true (role ansible/roles/subscription-host, defaults/main.yml) #epic !crit @item:EPC-1786264762917110
- [ ] EPC-1786264762918834 Choose subscription.mirror.backend: rsync (default, rsync-over-ssh) or restic #epic !crit @item:EPC-1786264762917110
- [ ] EPC-1786264762918357 Cadence: subscription.mirror.interval (default 5min, systemd vpn-sub-mirror.timer/.service, outbound-only pull) #epic !crit @item:EPC-1786264762917110
- [ ] EPC-1786264762918183 Provision: a dedicated subscription/delivery host running the role + a reachable build-worker source (the rsync/restic origin). No new public surface; payloads served by the loopback vpn-bootstrap service #epic !crit @item:EPC-1786264762917110
- [ ] EPC-1786264762918593 Keep enablecdnfront: false (baseline in groupvars/all.yml + vpn-fullstack.yml + vpn-p1p2.yml) so no CF outbound is auto-emitted into the client urltest pool #epic !crit @item:EPC-1786264762917110
- [ ] EPC-1786264762918975 Client auto-failover is already wired: scripts/emit-singbox.sh emits a urltest group (tag auto, url: generate204, interval: 5m, tolerance: 50) that passes over a degraded/throttling outbound — provided the direct non-CDN outbound is in the… #epic !crit @item:EPC-1786264762917110
- [ ] EPC-1786264762918149 Per-cohort repoint on degradation: edit the cohort groupvars (vpn-fullstack.yml / vpn-p1p2.yml) + re-run make emit-singbox CLIENT=<name> (see deploy-repo RUNBOOK-add-fallback.md) #epic !crit @item:EPC-1786264762917110
- [ ] EPC-1786264762918605 Wire the degradation signal: enable the per-ASN payload-throttle probe cron by exporting PAYLOADTHROTTLEHOST (scripts/install-operator-crons.sh — opt-in @daily; off until set), and pair it with ansible/roles/watchdog (enablewatchdog: true… #epic !crit @item:EPC-1786264762917110
- [ ] EPC-1786264762918169 Honest scope: demotion is signal (probe) + manual enablecdnfront toggle + client-side urltest auto-failover — there is no closed-loop auto-disable of CF in the deploy code. The checklist closes the milestone operationally, not by automatio… #epic !crit @item:EPC-1786264762917110

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
