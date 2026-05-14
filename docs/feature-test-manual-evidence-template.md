# Feature Test Manual Evidence Template

Use this template for the manual or external lab rows that remain open in
`docs/feature-test-checklist.md`. Attach the completed file or paste its
contents into `docs/feature-test-evidence-YYYY-MM-DD.md` after the run.

Do not paste live endpoints, credentials, subscription identifiers, SSIDs,
BSSIDs, phone numbers, account names, or private hostnames. Use redacted labels
such as `relay-vless-reality-primary` or `cellular-provider-a`.

## Run Metadata

| Field | Value |
| --- | --- |
| Date/time | |
| Operator | |
| Git commit | |
| Build artifact | |
| Device model / Android API | |
| Network label | |
| Artifact directory or archive | |
| `check-feature-gap-readiness.sh` output | |

## Rooted Physical Device

| Check | Evidence | Result |
| --- | --- | --- |
| Root access detection succeeds | `adb shell su 0 id` redacted output | |
| `root_mode_enabled` gates helper startup | Settings state and service log excerpt | |
| Helper binary extraction succeeds | App-private path presence or manager log | |
| Helper readiness polling succeeds | `RootHelperManager` log excerpt | |
| Privileged send operation succeeds | Packet-smoke or native action artifact | |
| Readiness timeout reports clear error | Negative run artifact | |
| Helper stop cleans process/socket | Process/socket cleanup check | |
| Logs omit traffic payloads/private config | Redaction scan result | |

## Physical Network Matrix

| Check | Evidence | Result |
| --- | --- | --- |
| Wi-Fi baseline | Probe JSON or lab archive | |
| Cellular baseline | Probe JSON or lab archive | |
| Wi-Fi to cellular handover | Before/after connectivity dump plus probe JSON | |
| Cellular to Wi-Fi handover | Before/after connectivity dump plus probe JSON | |
| IPv4-only path | Router/lab config plus probe JSON | |
| IPv6-only path | Router/lab config plus probe JSON | |
| Captive or limited path | Captive/limited-path indication plus app behavior | |
| Private DNS enabled | Settings dump plus probe JSON | |

## Provider Relay Matrix

Run `test-lab/scripts/check-relay-matrix-config.sh --config <private-matrix>`
before starting provider-backed tests. Store the private matrix outside the
repository and reference only redacted relay IDs here.

| Relay ID | Proxy | VPN | Diagnostics | Restart | Invalid credentials | Reset | Timeout | Malformed response | DNS fallback | Handover | Result |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| mock_relay | | | | | | | | | | | |
| vless_reality | | | | | | | | | | | |
| vless_xhttp | | | | | | | | | | | |
| warp | | | | | | | | | | | |
| cloudflare_tunnel | | | | | | | | | | | |
| masque | | | | | | | | | | | |
| hysteria2 | | | | | | | | | | | |
| tuic_v5 | | | | | | | | | | | |
| shadowtls_v3 | | | | | | | | | | | |
| naiveproxy | | | | | | | | | | | |
| webtunnel | | | | | | | | | | | |
| obfs4 | | | | | | | | | | | |
| snowflake | | | | | | | | | | | |
| google_apps_script | | | | | | | | | | | |

## TalkBack

| Check | Evidence | Result |
| --- | --- | --- |
| TalkBack is active | Accessibility settings dump | |
| Buttons announce useful labels | Transcript or screen recording timestamp | |
| Switches announce state and label | Transcript or screen recording timestamp | |
| Tabs announce destination and selection | Transcript or screen recording timestamp | |
| Progress messages are spoken | Transcript or screen recording timestamp | |
| Error messages are spoken | Transcript or screen recording timestamp | |
| No important control is unreachable | Route list and notes | |

## Routed Linux Netem

| Check | Evidence | Result |
| --- | --- | --- |
| Linux VM/router carries device traffic | Route table / topology note | |
| Packet loss applies and clears | `tc qdisc` before/after output | |
| QUIC drop applies and clears | iptables/nftables before/after output | |
| VPN-mode probe under packet loss | Probe JSON or lab archive | |
| Diagnostics probe under packet loss | Probe JSON or lab archive | |
| No stale success is reported | Probe verdict and error classification | |

## Remote Workflows

| Workflow | Run URL / ID | Commit | Result |
| --- | --- | --- | --- |
| CI | | | |
| CodeQL | | | |
| local-network-lab | | | |
| offline-analytics | | | |
| mutation-testing | | | |

## Final Verdict

| Field | Value |
| --- | --- |
| All required rows covered? | |
| Open gaps | |
| Bugs found | |
| Fix commits | |
| Retest evidence | |
