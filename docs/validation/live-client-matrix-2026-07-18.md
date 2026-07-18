# Live Android client matrix — 2026-07-18

## Scope and handling

This report records a physical-device validation of the Github Simple Debug flavor against the private `android-ripdpi` deployment bundle. It is intentionally redacted: endpoint addresses, UUIDs, authentication values, keys, PSKs, short IDs, client tunnel addresses, and full external addresses are not retained here.

- Device: Pixel 7, non-rooted, Android 17 / API 37, security patch 2026-07-05.
- Underlying network: Wi-Fi with usable IPv4. The SIM remained `OUT_OF_SERVICE` with no mobile data registration.
- Application traffic oracle: Chrome, a UID separate from RIPDPI. In-app debug probes are treated only as control-plane evidence because the app package is excluded from its own VPN.
- Route mode for the transport rows below: full tunnel.
- Private evidence directory: mode `0700`; individual configuration artifacts are mode `0600` and are not committed.
- Bundle integrity: the canonical source asset and the asset embedded in the final APK had the same SHA-256, `58cd11802f76fa0839afe0dbd6176d67b667f62e5f71e19cab062b1218307432`.

## Transport matrix

External IPv4 values are represented by a 16-hex SHA-256 prefix over the address text. A matching hash means the observed address matched another row; it is not an address prefix.

| Priority | Transport / port | Active tag evidence | HTTP/HTTPS and independent IPv4 | DNS | IPv6 | Result | Fix |
|---|---|---|---|---|---|---|---|
| P0 | VLESS + REALITY + Vision, TCP/443 | `simple-seed-VlessReality`; native REALITY TLS handshake | Chrome loaded the control endpoint through VPN; egress hash `b55b9aefc25b5172` | A query completed with `rcode=0`; resolver identity was not independently attributed | No usable result | Partial pass | — |
| P0 | REALITY fallback, TCP/2053 | Primary was replaced by a TEST-NET blackhole; startup selected `simple-seed-VlessReality-2`; native REALITY TLS handshake | Chrome loaded the control endpoint through VPN; egress hash `b55b9aefc25b5172` | A query completed with `rcode=0`; resolver identity was not independently attributed | No usable result | Partial pass | — |
| P1 | VLESS + XHTTP, TCP/443 | Deterministic single-relay bundle selected `simple-seed-VlessXhttp`; UI now reports `VLESS+XHTTP` | Chrome loaded the control endpoint through VPN; egress hash `a168bccda90846d6` | A query completed with `rcode=0`; resolver identity was not independently attributed | No usable result | Partial pass | `58a575b6a` |
| P2 | Hysteria2, UDP/443 | Deterministic single-relay bundle selected `simple-seed-Hysteria2` | Chrome loaded the control endpoint through VPN; egress hash `5d63d3394124d70d` | A query completed with `rcode=0`; resolver identity was not independently attributed | No usable result | Partial pass | — |
| P2 | AmneziaWG, UDP/51820 | Native handshake initiation/response, new session, keepalive, and listener-ready events | Chrome loaded the control endpoint through VPN; egress hash `5d63d3394124d70d` | A query completed with `rcode=0`; resolver identity was not independently attributed | No usable result | Partial pass | — |

The direct-path IPv4 hash was `cc65970271f24a60`, distinct from every VPN egress above. The Hysteria2 and AWG rows intentionally share an egress hash because those transports terminate behind the same deployed egress; their attribution comes from native transport events, not the address alone.

## Failover and recovery

| Scenario | Expected | Observed | Result | Fix |
|---|---|---|---|---|
| Startup primary endpoint unavailable | Advance to the next viable relay without using the direct path | With the TCP/443 primary replaced by a TEST-NET blackhole, startup selected the TCP/2053 REALITY fallback and Chrome used the VPN egress | Pass | — |
| Active Hysteria2 endpoint unavailable | Detect failed data plane and advance to AWG after debounce | Before remediation, repeated Chrome requests produced native `silent_drop` failures while relay listener health remained `running`, and no switch occurred. After remediation, fresh proxy error telemetry triggered explicit SOCKS egress confirmation; after the debounce the coordinator selected AWG | Pass | `69a09695f` |
| AWG startup immediately after active failover | Establish the replacement without surfacing Connected prematurely | The first replacement start received no handshake before readiness timeout. The runtime reported `Failed` / `Connection error`, not `Connected`. A user retry immediately received a handshake response and restored Chrome traffic through egress hash `5d63d3394124d70d` | Partial; transient recovery risk remains | `69a09695f` covers detection, not the transient AWG readiness failure |
| Brief total network loss while AWG active | Rebind/restart and resume traffic | Listener restarted, a new handshake completed, UI returned to Connected, and the control probe recovered | Pass | — |
| Kill application process while AWG active | Sticky VPN service recovers without direct-path success | Process PID changed; service restarted; AWG completed another handshake | Pass | — |
| Restart application | Persist the selected transport and reconnect | AWG selection persisted and reconnected | Pass | — |
| Kill only VPN service | Restore or fail closed | Covered by the process/service restart exercise; a separately isolated service-only kill still needs repeatable automation | Partial | — |
| Wi-Fi to mobile data handover | Rebind and keep policy | Device mobile service and data registration were unavailable (`OUT_OF_SERVICE`, data state `-1`) | Blocked by physical network | — |

## DNS, IPv6, and leak observations

- The five transport configurations completed IPv4 DNS control queries with successful response codes, but the tests did not independently identify the recursive resolver actually observed on the wire. This remains open.
- Chrome access to an IPv6-only control endpoint failed with `DNS_PROBE_FINISHED_NXDOMAIN`. The VPN interface had a ULA address but an unreachable IPv6 default route, while the underlying Wi-Fi exposed no usable IPv6 route. IPv6 forwarding and IPv6 leak resistance are therefore not proven by this network.
- The active-endpoint fault test never remained falsely `Connected`: the remediated coordinator selected AWG, and the failed first AWG readiness attempt moved the service to `Failed` / `Connection error`.
- No claim is made yet for WebRTC, per-app routing, system connectivity checks, LAN/private reachability, QUIC-to-TCP fallback, or a packet-capture proof of zero direct window.
- Always-on VPN and lockdown settings were not left enabled after the test; the final device state had no configured always-on package and the Simple app data was cleared.

## Routing matrix status

The transport rows prove full-tunnel IPv4 egress for a separate application UID. A source audit found that the required destination split is not currently executable in production: `split_tunnel_mode` controls Android per-app allow/exclude policy only, while persisted domain, CIDR, geosite, and geo-IP rules are not bridged into the native TCP/UDP/DNS data plane. The sing-box importer accepts package rules but ignores destination route fields, and the active tunneled outbound is attached to all proxy groups. The expected current result for a single included application is therefore all-tunneled, not the configured region-scoped direct / non-local tunneled policy.

This is a confirmed high-severity capability gap, not an unexecuted green row. It requires a dedicated destination-egress policy bridge with fail-closed `Tunneled / Direct / Block` decisions shared by TCP, UDP, and DNS before the 20-domain matrix can be meaningful. Category/geosite routing, DNS-cache independence, local/private routes, and default-unknown-domain behavior remain open. Android per-app include/exclude is a separate supported mechanism and still needs its own physical test.

## Security observations

- The embedded bundle and generated device bundle were byte-identical before testing.
- Local private client configuration files were mode `0600`; the evidence directory was mode `0700`.
- Distinct-client checks were performed without printing values; no Android credential was reported as equal to the compared client fixtures.
- REALITY validation completed without enabling an insecure/allow-insecure option in the tested profiles.
- The test APK contains live private client material by design and must not be distributed. It was never added to Git.
- A read-only live server audit confirmed the expected P0/P1/P2 transport listeners and service processes, Tailscale-based administration, default-drop nftables posture, restricted SSH, Android client presence, cross-client credential uniqueness, and client/server AWG and relay parameter agreement without printing values.
- The same audit found three gate defects: listener verification classifies every non-loopback socket as public and false-reds on tailnet/DHCP/system sockets; canonical `make validate` scans ignored operator-local secrets; and the distribution bundle validator rejects the runtime embedded representation of the AWG private key. These are validation defects, not proof that the live listener or client contracts are wrong, and require separate deploy-repository fixes.

## Regression and build evidence

- `./gradlew :app:testGithubSimpleDebugUnitTest -Pripdpi.skipNativeBuild=true --console=plain` — passed.
- `./gradlew testDebugUnitTest -Pripdpi.skipNativeBuild=true --console=plain` — passed, 314 actionable tasks.
- `./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true --console=plain` — passed, 717 actionable tasks.
- `./gradlew :app:assembleGithubSimpleDebug --console=plain` — passed with native libraries; the resulting APK was installed on the Pixel for both remediation retests.
- Physical instrumentation `VpnStartupWindowE2ETest#vpnStartupWindowHoldsDnsPacketUntilNativeReady` — passed on the Pixel with a directly reachable local UDP fixture. The test held a DNS datagram from a separate test process until native release and rejected an early Running state. An initial `adb reverse` control attempt failed before the assertion; rerunning over the verified direct Wi-Fi fixture control path passed.
- Focused regression coverage verifies fresh proxy-error detection, successful-probe latch clearing, counter-reset baselining, XHTTP descriptor propagation, and the rendered XHTTP/generic-VLESS labels.
- Pre-commit gates reported zero new architecture indicators and passed secret, large-file, baseline, formatting, and repository-rule checks for both fixing commits.

## Open completion blockers

This goal is not complete. The following matrix rows remain unproven or red: independent DNS resolver attribution, usable IPv6 and IPv6 leak testing, destination split routing (confirmed missing bridge), the full 20-domain matrix, WebRTC, dual-vantage packet-level direct-window evidence, per-app include/exclude, LAN/private routing, QUIC/TCP fallback, isolated VPN-service kill, and Wi-Fi/mobile handover. The deploy validation defects require fixes even though the live server posture itself passed the read-only audit. The transient first AWG readiness timeout after failover also requires continued observation and, if reproducible, its own root-cause fix and regression.
