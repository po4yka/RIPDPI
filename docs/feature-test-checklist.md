# Feature Test Checklist

This checklist is the release and nightly QA inventory for RIPDPI application features. Use it when changing runtime behavior, diagnostics, relay paths, settings persistence, UI flows, or test-lab tooling.

The goal is not to run every combination on every pull request. The goal is to make the complete feature surface explicit so each change can choose a justified slice, while release, nightly, and manual lab passes can cover the full matrix.

## How to Use

- [ ] Identify the changed feature, its owning module, and every affected runtime mode before selecting tests.
- [ ] Run the direct feature checks for that area.
- [ ] Add at least one cross-feature combination for each changed boundary: runtime mode, DNS mode, relay path, packet strategy, diagnostics profile, network type, locale, and persistence.
- [ ] Record the evidence artifact: unit test, integration test, packet smoke, emulator/device log, Roborazzi image, GitHub Actions run, or test-lab archive.
- [ ] For release candidates, complete the release matrix in this document or record the reason a row is not applicable.
- [ ] When a feature is root-only, verify both rooted behavior and non-rooted graceful degradation.
- [ ] When a feature writes or exports data, verify redaction and retention.

## Test Dimensions

Every feature should be tested against the dimensions that can affect it.

| Dimension | Values to cover |
| --- | --- |
| Build channel | Debug, release verification build, benchmark/profile build when performance-sensitive |
| Device type | Emulator x86_64, physical arm64, rooted physical device when root-only behavior changes |
| Android API | API 27 minimum, API 31 foreground-service restrictions, API 35/36 current target behavior |
| Runtime mode | Stopped app, proxy service, VPN service, VPN plus relay, diagnostics-only debug probe |
| Network | Wi-Fi, cellular, dual-stack, IPv4-only, IPv6-only, private DNS enabled, metered, network handover, captive or limited path |
| DNS mode | System DNS, plain resolver override, DoH, DoT, DNSCrypt, DoQ, fallback resolver loop |
| Relay path | None, mock relay, VLESS Reality TCP, VLESS xHTTP, chain relay, Cloudflare Tunnel, MASQUE, Hysteria2, TUIC v5, ShadowTLS v3, Trojan, AnyTLS, Shadowsocks, Mieru TCP, SSH TCP, Tor, NaiveProxy subprocess, Google Apps Script path, in-repository WebTunnel PT helper, external PT paths (Snowflake Go binary, obfs4), separate WARP/AmneziaWG tunnel profiles |
| Packet strategy | None, split, disorder, fake, TLS record, TLS random record, hostfake, OOB, delayed split, parser variants, QUIC, DTLS, UDP length, IPv6 extension headers, Lua rawsend, root-only FakeRst, root-only MultiDisorder, root-only IpFrag2, root-only SeqOverlap, adaptive marker offsets |
| Diagnostics profile | Connectivity, quick strategy probe, full matrix audit, home composite run, RAW_PATH run |
| Data state | Fresh install, migrated install, cleared app data, imported profile, remembered network, full history |
| UI state | Light theme, dark theme, compact width, expanded width, large font, TalkBack, RTL locale |
| Locale | en, ru, es, de, fr, fa, ar, zh-CN, hi, pt-BR |

## Core Smoke Matrix

Run this matrix before considering a build broadly healthy.

| Area | Checklist | Primary evidence | Required combinations |
| --- | --- | --- | --- |
| Startup | App launches, theme loads, navigation root renders, no startup crash | `./gradlew :app:testGithubFullDebugUnitTest` plus manual/emulator launch | Fresh install, migrated install, light/dark |
| Permissions | VPN consent, notification permission, foreground-service notification, battery optimization guidance | Device run log and UI check | API 27, API 31+, API 35/36 |
| Proxy service | Service starts, exposes local SOCKS5 endpoint, handles stop/restart, rejects port conflict cleanly | Unit tests, device curl through proxy, service logs | System DNS, encrypted DNS, relay off/on |
| VPN service | TUN starts, traffic routes, upstream sockets are protected, service stops cleanly | Device smoke, service logs, debug probe | Wi-Fi, cellular, handover |
| Diagnostics | Connectivity and strategy workflows run, progress is structured, cancellation leaves clean state | Diagnostics unit tests, test-lab archive | VPN running, VPN stopped, command-line settings enabled |
| Relay paths | Each configured relay starts, validates inputs, reconnects, and reports errors | Unit tests, mock relay, test-lab VPN run | Proxy mode, VPN mode, DNS fallback |
| Packet strategies | Candidate is serialized, applied, logged, and either succeeds or degrades with clear reason | Packet smoke, native tests, diagnostics report | IPv4, IPv6, TCP, UDP, QUIC where relevant |
| Settings | Changes persist, migrate, export/import, and reset without stale state | DataStore tests, manual settings pass | Fresh install, migrated install, locale switch |
| Logging/export | Logs and archives redact sensitive values and omit traffic payloads | Redaction tests, archive inspection | Diagnostics export, support archive, failure path |
| Localization | All locale keys exist, strings fit, RTL renders, native language names stay stable | lint, locale key diff, Roborazzi | Nine locales, large font, RTL |
| CI release gates | Static analysis, native lint/tests, packet smoke, coverage, release verification | GitHub Actions run | Push, pull request, manual/nightly |

## App Shell, Navigation, and Settings

- [ ] First launch renders Home without saved settings.
- [ ] App returns to the last selected tab after process recreation where that is supported.
- [ ] Bottom navigation and nested routes preserve expected back-stack behavior.
- [ ] Settings screens render with all feature flags enabled.
- [ ] Settings screens render with all optional relay and root features disabled.
- [ ] Search, filtering, or section expansion state survives rotation when the screen owns that state.
- [ ] Theme selection applies immediately and persists across restart.
- [ ] Fixed RIPDPI color tokens preserve contrast in both light and dark themes; platform dynamic color remains disabled.
- [ ] Large font mode keeps controls usable and does not overlap important text.
- [ ] TalkBack reads interactive controls with useful labels.
- [ ] RTL layout renders for Persian without clipping or reversed semantics.
- [ ] Language selector shows native language names in every locale.
- [ ] Reset-to-default behavior clears only the intended settings.
- [ ] Importing a profile rejects malformed or unsupported values with a clear local error.
- [ ] Exporting a profile omits private runtime-only values.
- [ ] Migrated settings keep old defaults compatible with current schema.
- [ ] The app handles missing native libraries with a clear non-crashing state.
- [ ] Background-to-foreground resume refreshes service state.
- [ ] Process kill during active service does not leave UI in a false running state after relaunch.

## Proxy Service

- [ ] Proxy starts from a clean stopped state.
- [ ] Proxy start is idempotent when tapped repeatedly.
- [ ] Proxy stop closes listeners and releases the configured port.
- [ ] Port conflict reports an actionable local error.
- [ ] SOCKS5 TCP CONNECT works for a known reachable host.
- [ ] SOCKS5 DNS resolution follows the selected DNS mode.
- [ ] Proxy handles local client disconnect without leaking sessions.
- [ ] Proxy handles upstream timeout and reports classified failure.
- [ ] Proxy mode with relay disabled uses direct outbound path.
- [ ] Proxy mode with each relay path uses that relay for outbound traffic.
- [ ] Proxy mode with encrypted DNS uses the selected resolver chain.
- [ ] Proxy mode with packet strategy enabled applies only compatible TCP/UDP actions.
- [ ] Proxy mode with command-line settings enabled reflects the command-line strategy and blocks automatic UI-only strategy trials.
- [ ] Proxy mode continues to work after changing DNS settings and restarting.
- [ ] Proxy mode continues to work after changing relay settings and restarting.
- [ ] Proxy service notification appears, updates state, and dismisses after stop.
- [ ] Proxy service logs do not include credentials, resolver tokens, URLs with secrets, or payload bytes.

## VPN Service

- [ ] VPN consent flow starts only when required.
- [ ] VPN start creates foreground notification before the platform timeout.
- [ ] TUN routes are installed and removed cleanly.
- [ ] Upstream sockets use the protection path so service traffic does not loop back into TUN.
- [ ] JNI socket protection is used when available.
- [ ] Unix socket protection fallback is used when JNI registration is not available.
- [ ] VPN start fails gracefully if native startup returns an error.
- [ ] VPN stop cancels native work and waits for cleanup.
- [ ] VPN restart after failure creates a fresh native session.
- [ ] VPN handles device sleep and wake without stale UI state.
- [ ] VPN handles Wi-Fi to cellular handover.
- [ ] VPN handles cellular to Wi-Fi handover.
- [ ] VPN handles private DNS being enabled at the OS level.
- [ ] VPN handles IPv4-only network.
- [ ] VPN handles IPv6-only network.
- [ ] VPN handles dual-stack network.
- [ ] VPN with relay disabled uses direct outbound path.
- [ ] VPN with relay enabled routes through the selected relay path.
- [ ] VPN with encrypted DNS and relay enabled keeps resolver behavior stable.
- [ ] VPN with packet strategy enabled applies compatible actions and reports unsupported actions.
- [ ] Always-on or lockdown platform behavior is verified where configured on the device.
- [ ] VPN foreground notification action stops the service.
- [ ] VPN logs include lifecycle events but not traffic content.

## DNS and Resolver Resilience

- [ ] System DNS path resolves A and AAAA records.
- [ ] Plain resolver override is stored and used only where supported.
- [ ] DoH resolver succeeds with default provider order.
- [ ] DoT resolver succeeds with default provider order.
- [ ] DNSCrypt resolver succeeds when supported by the selected provider.
- [ ] DoQ resolver succeeds when UDP is available.
- [ ] Resolver failover switches to the next candidate after catastrophic errors.
- [ ] Resolver failover switches after retry threshold for slower failures.
- [ ] Resolver loop stops after the configured alternative set is exhausted.
- [ ] Service does not halt while failover candidates remain.
- [ ] DNS query timeout is reported distinctly from refused/reset behavior.
- [ ] DNS tampering diagnostics detect protocol-level anomalies.
- [ ] DNS tampering diagnostics detect record-level divergence.
- [ ] DNS diagnostics handle malformed compression pointers.
- [ ] DNS diagnostics handle missing EDNS0 or authority sections.
- [ ] DNS diagnostics handle extra CNAME or authority mismatch.
- [ ] DNS diagnostics do not log queried private hostnames in release logs.
- [ ] DNS behavior is verified in proxy mode.
- [ ] DNS behavior is verified in VPN mode.
- [ ] DNS behavior is verified in diagnostics-only mode.
- [ ] DNS behavior is verified during network handover.

## Packet Strategy Features

For each strategy family, verify serialization, config validation, runtime application, diagnostics reporting, and graceful unsupported-device handling.

| Strategy family | Required checks |
| --- | --- |
| None | Baseline path works and does not emit packet-action telemetry |
| Split | Marker resolution, host marker, SNI marker, HTTP host marker, IPv4/IPv6 |
| Disorder | Reordered segment generation, overlap boundaries, unsupported path reporting |
| Fake | TTL setting, TTL fallback, payload template selection, capability logging |
| TLS record | Record boundary calculation, split position, malformed-input rejection |
| TLS random record | Random record generation, deterministic test seed behavior, bounds checking |
| Hostfake | Template selection, randomized host behavior, no sensitive host logging |
| OOB | Urgent pointer action, unsupported platform behavior, packet-smoke artifact |
| Delayed split | 50 ms and 150 ms timing, cancellation, timeout accounting |
| Parser variants | Unix EOL, method EOL, parser-only behavior, legacy compatibility |
| QUIC | Disabled mode, compatible burst, realistic burst, SNI split, fake version, dummy prepend |
| DTLS | ClientHello parsing, marker offsets, unsupported input reporting |
| UDP length | Length mutation, checksum behavior, tunnel compatibility |
| IPv6 extension headers | Header insertion, unsupported network behavior, packet-smoke artifact |
| Lua rawsend | Script load, argument validation, rawsend action, failure isolation |
| Root-only FakeRst | Root capability probe, privileged send path, non-root degradation |
| Root-only MultiDisorder | Root capability probe, privileged send path, non-root degradation |
| Root-only IpFrag2 | Root capability probe, privileged send path, non-root degradation |
| Root-only SeqOverlap | TCP repair path, replacement fd swap, non-root degradation |
| Adaptive marker offsets | TLS/HTTP/QUIC offset mapping, fragmented input, reassembled input |

Additional combination checks:

- [ ] Every TCP candidate can be selected from configuration.
- [ ] Every QUIC candidate can be selected from configuration.
- [ ] Candidate ordering remains modern-first in diagnostics reports.
- [ ] Fake-TTL-required candidates are skipped or marked when TTL is unavailable.
- [ ] Root-only strategies are hidden, disabled, or explained when root mode is off.
- [ ] Unsupported packet actions do not crash proxy mode.
- [ ] Unsupported packet actions do not crash VPN mode.
- [ ] Packet action telemetry is structured and redacted.
- [ ] Strategy settings round-trip through DataStore.
- [ ] Strategy settings round-trip through export/import where supported.

## Relay and Tunneling Paths

Each path needs validation in proxy mode, VPN mode, restart behavior, failure classification, and redaction.

| Relay path | Required feature checks |
| --- | --- |
| Mock relay | Local deterministic success, forced failure, archive artifact |
| VLESS Reality | Required fields, TLS settings, invalid credential handling, reconnect |
| VLESS xHTTP | HTTP transport settings, path/header validation, reconnect |
| Chain relay | Entry and exit profile resolution, unsupported hop rejection, two-hop telemetry |
| WARP | Enrollment state, endpoint selection, reconnect, missing state handling |
| Cloudflare Tunnel | Published config validation, local config validation, failure reporting |
| MASQUE | HTTP/3 availability, fallback when QUIC is unavailable, auth failure |
| Hysteria2 | UDP availability, auth failure, reconnect, loss handling |
| TUIC v5 | UDP availability, auth failure, reconnect, loss handling |
| ShadowTLS v3 | TLS settings, password failure, reconnect |
| Trojan | TLS settings, password failure, TCP and UDP forwarding, reconnect |
| AnyTLS | TLS settings, auth failure, TCP and UDP forwarding, reconnect |
| Shadowsocks | Cipher/password validation, TCP and UDP forwarding, reconnect |
| Tor | Bridge and pluggable-transport bootstrap, opt-in latency expectations, reconnect |
| NaiveProxy | HTTP auth, TLS verification, reconnect |
| WebTunnel | URL/path validation, HTTP error handling, reconnect |
| obfs4 | Bridge config validation, missing cert handling, reconnect |
| Snowflake | Broker configuration, relay discovery failure, reconnect |
| Google Apps Script path | Endpoint validation, quota/error response handling, reconnect |

Combination checks:

- [ ] Each relay path works with proxy mode where supported.
- [ ] Each relay path works with VPN mode where supported.
- [ ] Each relay path reports unsupported runtime combinations clearly.
- [ ] Each relay path works with system DNS.
- [ ] Each relay path works with encrypted DNS where compatible.
- [ ] Each relay path handles DNS resolver failover.
- [ ] Each relay path handles network handover.
- [ ] Each relay path handles process restart while configured.
- [ ] Each relay path handles invalid credentials without leaking them.
- [ ] Each relay path handles server reset, timeout, and malformed response.
- [ ] Each relay path emits structured logs without private endpoint details.
- [ ] Relay configuration import rejects malformed profiles.
- [ ] Relay configuration export redacts private values.

## Diagnostics Workflows

- [ ] Connectivity profile runs to completion with default targets.
- [ ] Quick strategy probe runs automatic recommendations.
- [ ] Full matrix audit runs selected target cohorts.
- [ ] Home composite run executes the eight-stage plan: automatic audit; concurrent detection signals, default connectivity, RU throttling, RU circumvention, and DPI full; dependent path comparison; then DPI strategy.
- [ ] Home composite skips remaining stages after audit failure or timeout.
- [ ] Home composite marks current stage failed if the service halts.
- [ ] Home composite fallback finalization runs when audit is not actionable.
- [ ] Native deadline is shorter than Kotlin stage timeout to allow partial result recovery.
- [ ] Cancellation performs the grace-period partial-result poll.
- [ ] Progress reports active lane, candidate index, total count, candidate id, and label.
- [ ] Reports include audit assessment and target selection.
- [ ] Export/share summaries include selected cohort and coverage/confidence.
- [ ] Automatic diagnostics are unavailable when command-line settings are enabled.
- [ ] Remembered-network persistence is driven only by validated recommendations.
- [ ] Full matrix audit remains manual-apply only.
- [ ] RAW_PATH diagnostics stop the VPN service before direct probing.
- [ ] RAW_PATH diagnostics do not require TUN socket protection.
- [ ] Diagnostics can run when proxy service is stopped.
- [ ] Diagnostics can run when proxy service is active if the workflow supports it.
- [ ] Diagnostics can run after a failed prior run without stale progress.
- [ ] Diagnostics cancellation clears UI busy state.
- [ ] Diagnostics history stores summary metadata and not traffic payloads.
- [ ] Diagnostics exports are redacted.

## Autolearn and Remembered Networks

- [ ] Validated recommendation is saved for the current network fingerprint.
- [ ] Recommendation is not saved when diagnostics confidence is too low.
- [ ] Full matrix audit result does not auto-apply.
- [ ] Network handover triggers hidden re-check where supported.
- [ ] Remembered recommendation applies only to matching network identity.
- [ ] Remembered recommendation is cleared when user resets learning state.
- [ ] Known telemetry/system hosts are excluded from promotion.
- [ ] Capacity limit of 512 learned hosts is enforced.
- [ ] Preferred-group statistics are not diluted by filtered hosts.
- [ ] Learning state survives app restart.
- [ ] Learning state migrates safely across schema versions.
- [ ] Learning state export/import behavior is documented and verified.

## Browser and HTTP Stack

- [ ] In-app browser launch route opens expected destination without exposing private query values in logs.
- [ ] Diagnostics-to-browser handoff preserves only intended state.
- [ ] Home-to-browser handoff works after service restart.
- [ ] Secure HTTP client verifies TLS certificates.
- [ ] HTTP/2-only retry path is triggered only for eligible failures.
- [ ] Android ECH behavior is gated by platform support.
- [ ] HTTP redirect parsing emits expected fields.
- [ ] HTTP response parser extracts status, headers, body sample metadata, and redirect fields without storing full private content.
- [ ] TLS parser extracts alert, version, and ServerHello metadata.
- [ ] SSH parser extracts banner metadata.
- [ ] Parser failures are classified and do not abort unrelated diagnostics.

## Root Helper and Privileged Operations

- [ ] Root access detection succeeds on rooted test device.
- [ ] Root access detection fails gracefully on non-rooted device.
- [ ] `root_mode_enabled` gates helper startup.
- [ ] Helper binary extraction succeeds from APK assets.
- [ ] Helper startup through `su` reports readiness.
- [ ] Helper socket readiness polling times out with clear error.
- [ ] IPC client connects per operation.
- [ ] SCM_RIGHTS fd passing works for privileged operations.
- [ ] JSON command validation rejects malformed input.
- [ ] Replacement fd from TCP repair swaps via `dup2()` where required.
- [ ] Capability probe reports supported root-only actions.
- [ ] Local fallback path is used when helper is unavailable.
- [ ] Non-rooted device sees graceful degradation for root-only actions.
- [ ] Helper stop cleans up process and socket.
- [ ] Helper logs do not include traffic payloads or private config.

## Logging, History, Export, and Privacy

- [ ] Debug logs include lifecycle and decision events needed for triage.
- [ ] Release logs omit verbose/debug-only entries.
- [ ] Logs redact passwords, tokens, auth headers, private keys, profile secrets, SSID/BSSID-like values, subscription identifiers, and private endpoint material.
- [ ] Logs omit tunneled traffic payloads.
- [ ] Diagnostics history stores summary, timestamps, profile, result, and selected candidate metadata.
- [ ] Diagnostics history can be cleared.
- [ ] Exported diagnostic summary contains enough data for local triage.
- [ ] Exported diagnostic summary redacts private values.
- [ ] Support archive includes command output, logs, and lab artifacts when requested.
- [ ] Support archive excludes release credentials and local secrets.
- [ ] Redaction applies to success path and failure path.
- [ ] Retention or cleanup settings remove old artifacts.
- [ ] Archive generation failure reports local error without partial misleading success.

## UI, Compose, Localization, and Accessibility

- [ ] Home screen renders stopped, starting, running, degraded, failed, and diagnostics-running states.
- [ ] Settings screen renders all configuration sections.
- [ ] Diagnostics screen renders idle, running, progress, success, degraded, failed, and canceled states.
- [ ] History screen renders empty, populated, filtered, and deleted states.
- [ ] Relay configuration UI validates required fields.
- [ ] Strategy configuration UI validates incompatible selections.
- [ ] Root-only controls reflect root mode and capability state.
- [ ] Compose state collection uses lifecycle-aware collection.
- [ ] One-shot UI events do not repeat after rotation.
- [ ] Long-running work is not launched directly from composable recomposition.
- [ ] All string keys in `app` exist in en, ru, es, de, fr, fa, ar, zh-CN, hi, and pt-BR.
- [ ] All string keys in `core/service` exist in all service locales.
- [ ] Native language display-name keys remain byte-identical across locales.
- [ ] Persian RTL layout keeps icons and labels semantically correct.
- [ ] Chinese strings fit compact-width controls.
- [ ] German and Spanish strings fit wider labels.
- [ ] Large font mode keeps button text inside bounds.
- [ ] Roborazzi baselines are updated only when UI change is intentional.
- [ ] TalkBack announces buttons, switches, tabs, progress, and error messages.

## Test-Lab and Automation Tooling

- [ ] `test-lab/doctor.sh` reports host prerequisites.
- [ ] `test-lab/start.sh` starts deterministic local services.
- [ ] `test-lab/stop.sh` stops local services and cleans generated state.
- [ ] UDP echo service responds deterministically.
- [ ] Mock relay returns deterministic success.
- [ ] Mock relay can force deterministic failure.
- [ ] Toxiproxy delay scenario applies and clears.
- [ ] Toxiproxy reset scenario applies and clears.
- [ ] Linux netem packet-loss scenario applies and clears in a routed VM lab.
- [ ] VPN E2E runner verifies DNS, HTTP, HTTPS, TCP, UDP, and optional QUIC.
- [ ] Proxy E2E runner verifies DNS, HTTP, HTTPS, TCP, UDP, optional QUIC, mock relay readiness, and service cleanup after disconnect.
- [ ] E2E runner distinguishes passed, degraded, failed, and skipped evidence.
- [ ] Archive script collects logs, command output, scenario config, and device metadata.
- [ ] Archive script redacts local secrets.
- [ ] Local-network-lab GitHub Actions workflow runs lab checks.
- [ ] Test-lab documentation matches script names and environment variables.
- [ ] Generated test-lab certs, logs, and archives are not committed.
- [ ] Physical-device steps document required adb state.
- [ ] Emulator-only gaps are documented when physical network behavior is needed.

## CI, Release, and Supply Chain

- [ ] `./gradlew staticAnalysis` passes.
- [ ] Unit tests for changed Android modules pass.
- [ ] Roborazzi tests pass or are intentionally re-blessed.
- [ ] Native Rust formatting passes.
- [ ] Native Rust clippy passes for changed crates.
- [ ] Native Rust unit tests pass for changed crates.
- [ ] Packet smoke tests pass when packet strategy code changes.
- [ ] Turmoil or loom tests run when concurrency-sensitive native code changes.
- [ ] cargo-deny or supply-chain policy checks pass.
- [ ] Native bloat check passes or intentional size change is documented.
- [ ] Release verification build succeeds without debug-only assets.
- [ ] Release APK does not include generated lab certificates or logs.
- [ ] CodeQL workflow remains green.
- [ ] Offline diagnostics workflow remains compatible with checked-in sample corpus.
- [ ] Mutation testing scope is updated when new native critical logic is added.
- [ ] Baselines are not expanded to hide new violations.

## Combination Matrices

### Runtime Mode by DNS by Relay

| Runtime mode | System DNS | Plain override | Encrypted DNS | Relay off | Relay on | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| Proxy | Required | Required | Required | Required | Required | Unit test plus local curl/proxy smoke |
| VPN | Required | Required | Required | Required | Required | Device smoke plus debug probe |
| Diagnostics-only | Required | Required | Required | Not applicable | Where supported | Diagnostics report |
| Test-lab VPN E2E | Required | Optional | Required | Required | Mock relay required | Lab archive |
| Test-lab proxy E2E | Required | Optional | Required | Required | Mock relay required | Lab archive plus service-leak check |

### Runtime Mode by Packet Strategy

| Runtime mode | None | Basic split | Fake/TLS record | QUIC/UDP | Root-only | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| Proxy | Required | Required | Required | Where compatible | Degrade if unavailable | Packet smoke plus proxy smoke |
| VPN | Required | Required | Required | Required | Rooted and non-rooted pass | Packet smoke plus device run |
| Diagnostics | Required | Required | Required | Required | Capability-gated | Diagnostics report |
| Test-lab | Required | Required | One representative | Optional QUIC | Not required unless rooted lab exists | Lab archive |

### Relay by Runtime Mode

| Relay path | Proxy | VPN | Diagnostics | Restart | Failure injection |
| --- | --- | --- | --- | --- | --- |
| Mock relay | Required | Required | Required | Required | Required |
| VLESS Reality | Required | Required | Recommended | Required | Required |
| VLESS xHTTP | Required | Required | Recommended | Required | Required |
| Chain relay | Required | Required | Recommended | Required | Required |
| WARP | Where supported | Required | Recommended | Required | Required |
| Cloudflare Tunnel | Required | Required | Recommended | Required | Required |
| MASQUE | Where supported | Required | Recommended | Required | Required |
| Hysteria2 | Where supported | Required | Recommended | Required | Required |
| TUIC v5 | Where supported | Required | Recommended | Required | Required |
| ShadowTLS v3 | Required | Required | Recommended | Required | Required |
| Trojan | Required | Required | Recommended | Required | Required |
| AnyTLS | Where supported | Required | Recommended | Required | Required |
| Shadowsocks | Required | Required | Recommended | Required | Required |
| Tor | Required | Required | Recommended | Required | Required |
| NaiveProxy | Required | Required | Recommended | Required | Required |
| WebTunnel | Required | Required | Recommended | Required | Required |
| obfs4 | Required | Required | Recommended | Required | Required |
| Snowflake | Required | Required | Recommended | Required | Required |
| Google Apps Script path | Required | Required | Recommended | Required | Required |

### Network Fault Matrix

| Fault | Proxy | VPN | Diagnostics | Relay | DNS | Expected result |
| --- | --- | --- | --- | --- | --- | --- |
| DNS timeout | Required | Required | Required | Required | Required | Classified timeout, failover where eligible |
| DNS reset/refused | Required | Required | Required | Required | Required | Eager resolver switch |
| TCP connect timeout | Required | Required | Required | Required | Optional | Classified timeout |
| TCP reset | Required | Required | Required | Required | Optional | Classified reset, adaptive timeout where relevant |
| TLS alert | Required | Required | Required | Required | Optional | TLS metadata captured, no crash |
| HTTP 500 | Required | Required | Required | Required | Optional | HTTP error classified |
| Malformed HTTP | Required | Required | Required | Required | Optional | Parser error isolated |
| UDP packet loss | Optional | Required | Required | Required for UDP relay | Optional | Degraded or timeout, no stale success |
| QUIC unavailable | Optional | Required | Required | Required for QUIC relay | Optional | QUIC path skipped or degraded |
| Network handover | Required | Required | Recommended | Required | Required | Reconnect or clear failure |
| Process kill | Required | Required | Optional | Required | Optional | Relaunch shows accurate state |

## Feature Definition of Done

A feature is ready for review when all applicable items are true:

- [ ] Direct unit tests cover config validation and state transitions.
- [ ] Integration tests cover the owning service or native boundary.
- [ ] At least one runtime smoke covers the feature on emulator or device.
- [ ] At least one cross-feature combination covers the most likely production interaction.
- [ ] Failure behavior is covered with timeout, malformed input, or unavailable dependency.
- [ ] Logs and exported artifacts are redacted.
- [ ] UI text is localized in all supported locales when new strings are added.
- [ ] Support settings links, if affected, preview every changed setting and reject malformed or partially unsupported packages without writing settings.
- [ ] Documentation describes any new user-visible setting, workflow, or test-lab command.
- [ ] CI gates that should catch regressions are wired or updated.
- [ ] Manual test gaps are recorded when automation is not yet practical.
