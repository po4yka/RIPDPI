# Localization glossary

Canonical terminology guidance for RIPDPI translators. The app ships **7 locales**
(en, ru, es, de, fr, fa, zh-CN). This glossary exists so that terminology stays
consistent across every locale and so translators know which tokens must **never**
be translated.

Source strings live in `app/src/main/res/values/strings.xml` and
`core/service/src/main/res/values/strings.xml`. Every key must land in all seven
matching `values-XX/strings.xml` files in the same commit — `lint.xml` sets
`MissingTranslation severity="error"`, so a missing key fails CI.

> Scope note: this glossary is reference documentation only. It does **not** add,
> remove, or edit any `strings.xml`, locale file, or README selector block.

---

## Do not translate (translatable=false candidates)

These are pure technical tokens — protocol names, transport acronyms, and wire
identifiers. They MUST stay **verbatim** in every locale (same casing, same
hyphenation). They are brand/protocol identifiers, not prose; translating them
breaks recognizability and, for the wire identifiers, breaks the user's mental
mapping to documentation and server configs. None of these should ever be
inflected, transliterated, or localized — not even into Cyrillic, Persian, or
Han script.

| Term | Keep verbatim | Notes |
| --- | --- | --- |
| VLESS | yes | Relay protocol. `RelayKind::VlessReality` (a). VLESS Reality does not use real ECH per `docs/adr/0001-reality-ech.md`. |
| Trojan | yes | Relay protocol. `RelayKind::Trojan` (a). |
| Shadowsocks | yes | Relay protocol. `RelayKind::Shadowsocks` (a). |
| Hysteria | yes | Relay protocol family. |
| Hysteria2 | yes | Relay protocol. `RelayKind::Hysteria2` (a); wire id `hysteria2`. Keep the trailing `2` (no space). |
| TUIC | yes | Relay protocol. `RelayKind::TuicV5` (a); wire id `tuic_v5`. Always upper-case. |
| Mieru | yes | Proxy protocol identifier. |
| SSH | yes | Transport acronym. |
| AnyTLS | yes | Relay protocol. `RelayKind::AnyTls` (a); wire id `anytls`. Keep `TLS` upper-case. |
| ShadowTLS | yes | Relay protocol. `RelayKind::ShadowTlsV3` (a); wire id `shadowtls_v3`. Keep `TLS` upper-case. |
| Snowflake | yes | Pluggable transport; still the external Go PT binary per `docs/architecture/snowflake-native-rust-decision.md`. Do not translate to the literal weather word. |
| WireGuard | yes | VPN protocol. Detected by `ripdpi-protocol-detect` (b). Keep the camel-case `WireGuard`. |
| AmneziaWG | yes | WireGuard obfuscation variant; surfaces in the WARP editor (`awg_*`, `warp_amnezia_*` strings). Keep `WG` upper-case. |
| Reality | yes | VLESS sub-protocol (TLS camouflage). Keep capitalized as a protocol name, not the common noun. |
| ECH | yes | Encrypted Client Hello. Acronym only. |
| XHTTP | yes | VLESS transport (`xhttp`). Keep upper-case. |
| gRPC | yes | Transport; keep lower-case `g` + upper-case `RPC`. |
| WebSocket | yes | Transport; keep camel-case (one word, capital `S`). |
| QUIC | yes | Transport protocol acronym. Always upper-case. |
| MASQUE | yes | Relay protocol. `RelayKind::Masque` (a); wire id `masque`. Always upper-case. |
| NaiveProxy | yes | Relay protocol. `RelayKind::NaiveProxy` (a); wire id `naiveproxy`. Keep camel-case. |
| SOCKS5 | yes | Proxy protocol; keep the trailing `5` (no space). |
| DNS | yes | Acronym. |
| DoH | yes | DNS over HTTPS. Keep mixed-case `DoH`. |
| DoT | yes | DNS over TLS. Keep mixed-case `DoT`. |
| DPI | yes | Deep Packet Inspection. Acronym. |
| TUN | yes | TUN virtual network device. Always upper-case. |
| MTU | yes | Maximum Transmission Unit. Acronym. |
| SNI | yes | Server Name Indication. Acronym. |
| TLS | yes | Transport Layer Security. Acronym. |
| UUID | yes | Wire/config identifier. Acronym. |
| BSSID | yes | Network identifier. Acronym; appears only in privacy-bounded contexts. |

> Derivation note: the relay-protocol set is the ground-truth `RelayKind` /
> `RelayBackendConfig` enum in
> `native/rust/crates/ripdpi-relay-core/src/config/kind.rs` and
> `…/src/config/backend.rs` (a). WireGuard / AmneziaWG detection is in
> `native/rust/crates/ripdpi-protocol-detect/src/lib.rs` (b). Casing for the
> remaining acronyms (QUIC, SNI, ECH, MTU, DoH, DoT, XHTTP, gRPC, WebSocket,
> SOCKS5) was taken verbatim from `app/src/main/res/values/strings.xml`.

---

## Service-mode names

User-facing run/service modes. The **English** column is the exact source string;
the **Guidance** column tells translators how to render the user-facing label.
The bracketed key shows where the string lives so the translation stays
attached to the right surface.

| English | Guidance |
| --- | --- |
| Local VPN (`home_mode_vpn`) | Translate. The on-device VPN mode that routes this device's traffic through the local data plane. "Local" = on this device, not a remote server. |
| Local proxy (`home_mode_proxy`) | Translate, but keep **proxy** rendered with the locale's standard proxy term (zh-CN 代理, ru прокси). The local SOCKS/HTTP listener mode. |
| Local DPI Bypass (`home_mode_local_dpi_bypass`) | Translate "Local … Bypass"; keep **DPI** verbatim (see Do-not-translate table). The on-device anti-DPI mode with no relay server. |
| VPN with Remote Server (`home_mode_remote_vpn`) | Translate. VPN mode where traffic egresses through a configured remote relay server. |
| Diagnostic Scan (`home_mode_diagnostic_scan`) | Translate. The analysis/audit mode (not a traffic-carrying mode). Keep consistent with the "Diagnostic verdict names" section below. |
| Direct VPN (`service_mode_native_direct_title`) | Translate. Built-in VPN connecting directly (no relay). "Direct" must read as "no intermediary server," consistent with the Direct-mode verdicts below. |
| Relayed VPN (`service_mode_native_proxy_title`) | Translate. Built-in VPN routed through the user's relay. Contrast with "Direct VPN." |
| Advanced VPN (`service_mode_xray_vpn_title`) | Translate. Uses an imported advanced (Xray provider) profile for stronger censorship resistance. "Advanced" = imported-profile / Xray-provider mode. |

> Derivation note: `home_mode_*` and `service_mode_*` values quoted from
> `app/src/main/res/values/strings.xml`. The underlying provider/run-mode split
> (native-direct vs native-proxy/relayed vs Xray-provider) matches the
> `RelayBackendConfig` / `XrayProviderOrchestrator` boundary in `core/`. The
> SOCKS5 / HTTP proxy listeners surface under **Local proxy**; the proxy
> protocol token `SOCKS5` itself stays verbatim per the table above.

---

## Diagnostic verdict names

The Direct-mode diagnostic verdict result classes are a closed enum,
`DirectModeVerdictResult` in
`core/data/model/src/main/kotlin/com/poyka/ripdpi/data/TransportPolicy.kt`.
These are **the** authoritative verdict names — every locale must render them
consistently with the remediation strings that reference them
(`home_remediation_*`, `diagnostics_remediation_*`). The **Must stay consistent**
column flags terms that must read identically across all surfaces in the same
locale.

| English | Guidance | Must stay consistent |
| --- | --- | --- |
| Transparent works (`TRANSPARENT_WORKS`) | "Direct/transparent connectivity succeeds." Render "transparent" as the locale's term for direct/no-intervention connectivity. | "transparent" vs "direct" wording — pick one per locale and reuse. |
| Owned-stack-only (`OWNED_STACK_ONLY`) | The authority works **only** through RIPDPI's owned request stack (the built-in RIPDPI Browser path). Keep "owned-stack" rendered the same way everywhere it appears (banner, remediation, home summary). | **Owned-stack** wording — must match `owned_stack_browser_*`, `*_owned_stack_*` remediation strings. |
| No-direct-solution (`NO_DIRECT_SOLUTION`) | No direct arm reached stable success (includes budget-exhausted runs); a relay/remediation is needed. | "No direct solution" phrasing — must match `home_remediation_no_hint_summary` ("found no direct solution"). |
| RIPDPI Browser / Owned-stack path (`title_owned_stack_browser`, `owned_stack_browser_banner_title`) | "RIPDPI Browser" is a product feature name — keep **RIPDPI** verbatim; translate "Browser". "Owned-stack path" must match the verdict wording above. | "Owned-stack" wording across browser + verdict surfaces. |

Supporting reason codes (`DirectModeReasonCode`, same file) — translate the
human-readable remediation copy, but keep the embedded protocol/transport tokens
verbatim:

| Reason code | Guidance |
| --- | --- |
| `QUIC_BLOCKED` | Keep **QUIC** verbatim. |
| `TCP_POST_CLIENT_HELLO_FAILURE` | Keep **TCP** / **Client Hello** wording aligned with TLS terminology. |
| `IP_BLOCKED` | Translate "blocked"; keep **IP** verbatim. |
| `NO_TCP_FALLBACK` | Keep **TCP** verbatim. |
| `OWNED_STACK_REQUIRED` | Use the same "owned-stack" wording as the verdict above. |
| `UNKNOWN_DIRECT_FAILURE` | Translate freely; consistent with "direct" wording chosen above. |

> Derivation note: verdict classes from `DirectModeVerdictResult` and reason
> codes from `DirectModeReasonCode` in
> `core/data/model/src/main/kotlin/com/poyka/ripdpi/data/TransportPolicy.kt`;
> the verdict→reason mapping is in
> `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/orchestrator/OrchestratorTypes.kt`.
> Adjacent result taxonomies that should reuse the same locale vocabulary:
> `DiagnosticsOutcomeBucket` (Healthy / Attention / Failed / Inconclusive) and
> `Verdict` (NOT_DETECTED / NEEDS_REVIEW / DETECTED) in
> `core/diagnostics/.../DiagnosticsOutcomeTaxonomy.kt` and
> `core/detection/.../DetectionModels.kt`.

---

## Reference term translations

High-frequency product nouns with a **canonical** zh-CN and ru rendering, taken
from existing translations in the locale files. These are a **consistency
anchor** — reference, **not** mandatory. Translators should prefer these unless
a surface needs a different register; if you diverge, do so consistently across
the locale.

| English noun | zh-CN (reference) | ru (reference) | Source key |
| --- | --- | --- | --- |
| Subscription | 订阅 | Подписка | `import_subscription_confirm_title` ("Add subscription" → "添加订阅" / "Добавить подписку") |
| Node / Server | 服务器 | Сервер | `awg_field_server` ("Server") |
| Routing / Split | 分流 (routing) / 分应用代理 (split-tunnel) | Маршрут (routing) / Раздельное туннелирование (split-tunnel) | `title_split_tunnel`; routing copy in `home_status_*` (zh) / `diagnostics_*` (ru) |
| Profile | 配置 | Профиль | `diagnostics_field_profile` ("Profile") |
| Relay | 中继 | Реле | `config_relay_section` ("Relay") |
| Bypass | 绕过 | Обход | `detection_check_category_bypass` ("Bypass") |

Notes:
- **Node vs Server**: RIPDPI's UI uses **Server** (服务器 / Сервер), not "Node".
  If a subscription's upstream uses "node" terminology, render it as the local
  "server" term to stay consistent with the app.
- **Routing vs Split**: "Routing" (分流 / Маршрут) is the general traffic-routing
  concept; **Split tunneling** is the per-app feature (分应用代理 / Раздельное
  туннелирование) and has its own established translation — do not collapse the two.
- Where a noun appears next to a verbatim protocol token (e.g. "VLESS server"),
  translate only the noun and keep the protocol token verbatim per the
  Do-not-translate table.

> Derivation note: zh-CN renderings from
> `app/src/main/res/values-zh-rCN/strings.xml`; ru renderings from
> `app/src/main/res/values-ru/strings.xml`. English source keys from
> `app/src/main/res/values/strings.xml`.
