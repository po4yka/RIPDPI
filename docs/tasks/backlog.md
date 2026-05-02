# Backlog — RIPDPI

> Source of truth for `#status/backlog` tasks. Syntax: `- [ ] #task <title> #repo/RIPDPI #area/<area> #status/backlog <priority> [paperclip:POY-N]`


## advanced-routing-rules-and

- [ ] #task Add RuleEntity Room table and repository #repo/RIPDPI #area/advanced-routing-rules-and #status/backlog ⏫ [paperclip:POY-86]
  - Paperclip: POY-86 · assigned to: unassigned
  - Parent: POY-36 (Epic - Advanced routing rules and geoip enforcement)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, routing, data
  - **source:** `TaskNotes/Tasks/Add RuleEntity Room table and repository.md`
  - **epic:** Epic - Advanced routing rules and geoip enforcement

  ## Summary

  Add a `RuleEntity` Room table and repository that models user-editable
  routing rules: domain / CIDR / port / process / package matchers and
  proxy / bypass / block / specific-profile outbound actions.

  ## Context

  Schema should mirror NekoBox's RuleEntity for subscription portability
  hopes, but without sing-box-only fields (e.g. `network`/`protocol` that
  sing-box uses internally). Store matcher lists as newline-delimited
  strings (Kotlin), parsed on load; matcher semantics live in the Rust
  engine task.

  ## Acceptance criteria

  - [ ] Entity fields: id, name, userOrder, enabled, domains, ipCidrs,
        ports, sourcePorts, network (tcp|udp|both), processName,
        packages (Set<String>), outboundTag (enum: PROXY | BYPASS |
        BLOCK | PROFILE(profileId) | GROUP(groupId)).
  - [ ] Repository exposes CRUD and a reorder operation; returns rules
        as a `Flow<List<RuleEntity>>`.
  - [ ] Constraint: deletion of a profile/group referenced by any rule
        either cascades (bypassing the reference) or prompts the user —
        decide once and document; never silent-corrupt.
  - [ ] Seeded default rules: one "bypass LAN" rule, one "bypass
        loopback" rule; user can delete them.
  - [ ] Schema is exported from Room and covered by a migration test.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/database/RuleEntity.kt` — the full `@Entity`. Field-for-field port target: `id`, `name`, `userOrder`, `enabled`, `config` (raw JSON override), `domains`, `ip` (CIDR), `port`, `sourcePort`, `network`, `source`, `protocol`, `outbound` (Long with sentinel values: `0` proxy, `-1` bypass, `-2` block, `>0` specific profile), `packages: Set<String>`.
  - `app/src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt` — the DAO: `allRules()`, `enabledRules()`, `checkVpnNeeded()`, CRUD methods. Port the method set.
  - `app/src/main/java/io/nekohasekai/sagernet/database/StringCollectionConverter.java` — Room type converter for `Set<String>` (packages list). Port.

  **Adapt:** Entity fields, DAO method set, Set<String> converter. **Skip:** NekoBox's raw-JSON `config` override field (RIPDPI should prefer a stricter typed model; if passthrough is needed, add as a late follow-up).

  ## Links

  - [[Epic - Advanced routing rules and geoip enforcement]]

- [ ] #task Add Rust rule matcher with domain ip port process matchers #repo/RIPDPI #area/advanced-routing-rules-and #status/backlog ⏫ [paperclip:POY-87]
  - Paperclip: POY-87 · assigned to: unassigned
  - Parent: POY-36 (Epic - Advanced routing rules and geoip enforcement)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, routing, rust, runtime
  - **source:** `TaskNotes/Tasks/Add Rust rule matcher with domain ip port process matchers.md`
  - **epic:** Epic - Advanced routing rules and geoip enforcement

  ## Summary

  Add a Rust rule matcher crate that evaluates user rules at flow-dispatch
  time, in first-match-wins order, producing an outbound action.

  ## Context

  Matcher lives in Rust for the same reason the rest of the fast path does:
  allocation-free hot loop, predictable p99. Domain matching uses a suffix
  trie; IP CIDR uses a ranged-tree. Process name comes from the existing
  package→UID lookup; package set is pre-hashed.

  ## Acceptance criteria

  - [ ] `ripdpi-routing` crate with `RuleMatcher` type; FFI surface
        exposed to JNI.
  - [ ] Suffix-trie domain matcher; benchmark beats linear scan by 10×
        at 10K domain entries.
  - [ ] IP CIDR matcher supports IPv4 and IPv6; uses a trie or interval
        tree, not linear scan.
  - [ ] Port matcher supports single port and range (`80-90`); source
        ports handled symmetrically.
  - [ ] Package matcher uses the existing package→UID cache; cold lookup
        does not stall the flow dispatch.
  - [ ] Matcher allocation on the hot path is zero in steady state;
        benchmark proves it.
  - [ ] Unit tests cover: first-match-wins order, disabled rules
        skipped, no-rule default action configurable.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt` — the rule-translation pass (search for `Rule_DefaultOptions` and `makeSingBoxRule`). Shows how domain strings get classified into `domain` / `domain_suffix` / `domain_regex` / `geosite` prefix categories. **Port this classification logic.**
  - `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt` — `applyRouteRules()` shows the built-in rule set (DNS hijack on port 53, LAN bypass, multicast block) appended to user rules.

  **Upstream sing-box** ([repo](https://github.com/SagerNet/sing-box)) — the actual rule-matching Go code lives in `route/rule_default.go`. RIPDPI implements in Rust but the algorithm is simple: first-match-wins, each rule a boolean conjunction of matchers. Not a port, just a reference for correctness.

  **Adapt:** Domain-string classification (prefixes like `domain:`, `geosite:`, `ip_cidr:`), first-match-wins semantic, built-in rule set (LAN bypass, multicast block). **Skip:** sing-box's Go implementation; allocation-free Rust is a separate engineering concern.

  ## Links

  - [[Epic - Advanced routing rules and geoip enforcement]]
  - [[Add RuleEntity Room table and repository]]

- [ ] #task Add configurable asset provider picker with four presets #repo/RIPDPI #area/advanced-routing-rules-and #status/backlog 🔼 [paperclip:POY-110]
  - Paperclip: POY-110 · assigned to: unassigned
  - Parent: POY-36 (Epic - Advanced routing rules and geoip enforcement)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, assets
  - **source:** `TaskNotes/Tasks/Add configurable asset provider picker with four presets.md`
  - **epic:** Epic - Advanced routing rules and geoip enforcement

  ## Summary

  Surface an asset-provider picker that lets users choose the source of
  `geoip.db` / `geosite.db`, mirroring NekoBox's four built-in presets:
  SagerNet, soffchen, Chocolate4U Iran rules, L11R antizapret.

  ## Context

  Different regions have different "good" asset providers. Chocolate4U is
  tuned for Iran; antizapret is Russia-centric; SagerNet and soffchen are
  generalist. Picker is in Advanced Settings; updates are user-triggered
  via a button, not background fetch.

  ## Acceptance criteria

  - [ ] Four built-in providers with labels, descriptions, and
        repository URLs (GitHub Releases).
  - [ ] "Custom URL" option for a user-supplied GitHub-Releases-compatible
        provider.
  - [ ] "Check for updates" button compares local version tag to latest
        release; downloads only if newer.
  - [ ] Download uses the existing in-proxy HTTP client so the update
        works from inside a bypass tunnel.
  - [ ] Imported DBs land in external files dir; runtime reload signal
        fires the geo-loader swap without restart.
  - [ ] SAF import path for local `.db` files as a final fallback.
  - [ ] Post-update, surface new version tag inline.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/ui/AssetsActivity.kt` — the full provider picker + update-from-GitHub-Releases flow. Four built-in providers (SagerNet, soffchen, Chocolate4U Iran rules, L11R antizapret) listed here verbatim. **Port the provider list** and the "check for updates" logic (compares local tag file to GitHub Releases API `/latest` tag).
  - `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt` — `rulesProvider` preference.
  - `app/src/main/res/xml/assets_preferences.xml` — the preference layout for reference.

  **Provider URLs** (same four NekoBox ships):
  - `https://github.com/SagerNet/sing-geoip` + `sing-geosite`
  - `https://github.com/soffchen/sing-geoip` + `sing-geosite`
  - `https://github.com/Chocolate4U/Iran-sing-box-rules`
  - `https://github.com/L11R/antizapret-sing-box-geo`

  **Adapt:** Provider list verbatim, GitHub Releases `/latest` tag comparison, SAF import path for custom files, swipe-delete + undo. **Skip:** NekoBox's PreferenceFragment XML (use Compose).

  ## Links

  - [[Epic - Advanced routing rules and geoip enforcement]]
  - [[Add geoip.db and geosite.db runtime loader and lookup]]

- [ ] #task Add custom domain bypass list screen #repo/RIPDPI #area/advanced-routing-rules-and #status/backlog 🔼 [paperclip:POY-112]
  - Paperclip: POY-112 · assigned to: unassigned
  - Parent: POY-36 (Epic - Advanced routing rules and geoip enforcement)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, routing, ui
  - **source:** `TaskNotes/Tasks/Add custom domain bypass list screen.md`
  - **epic:** Epic - Advanced routing rules and geoip enforcement

  ## Summary

  Add a simple "Domain bypass list" screen where users paste domains they
  want kept on direct (out-of-proxy), without entering the full rule
  editor. The list compiles to a single high-priority routing rule.

  ## Context

  Most users do not need the full rule editor; they just need to keep a
  handful of domestic services off the tunnel (banking, government, local
  maps). Giving this a dedicated, simpler surface separates the 90% case
  from the power-user rule editor.

  ## Acceptance criteria

  - [ ] Screen under Settings or Routes; multi-line text-edit accepting
        newline-delimited domains.
  - [ ] Accepts plain domains (`example.com`), suffixes (`.example.com`),
        and `domain:` / `domain_suffix:` / `domain_regex:` prefixes.
  - [ ] Entries compile to a single internal rule with outbound=BYPASS
        and the highest user-configurable priority.
  - [ ] Editing the list does not reorder other user rules.
  - [ ] Import from clipboard and export to clipboard actions.
  - [ ] Validation: malformed regex surfaces inline, the list saves only
        clean entries.

  ## Source references

  **NekoBoxForAndroid** — no direct analog. NekoBox exposes only the full rule editor (`RouteSettingsActivity`). A simple bypass-list is NOT in NekoBox — this is an RIPDPI-original simplification for the common case.

  **Adapt:** The domain-string classification prefixes (`domain:`, `domain_suffix:`, `domain_regex:`) from NekoBox's `ConfigBuilder.kt` — see [[Add Rust rule matcher with domain ip port process matchers]] for that reference.

  **Invent:** The single-rule compile strategy (all entries → one high-priority BYPASS rule), the "move into full rule editor" migration action.

  ## Links

  - [[Epic - Advanced routing rules and geoip enforcement]]

- [ ] #task Add full routing rule editor screen #repo/RIPDPI #area/advanced-routing-rules-and #status/backlog ⏫ [paperclip:POY-119]
  - Paperclip: POY-119 · assigned to: unassigned
  - Parent: POY-36 (Epic - Advanced routing rules and geoip enforcement)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, routing, ui
  - **source:** `TaskNotes/Tasks/Add full routing rule editor screen.md`
  - **epic:** Epic - Advanced routing rules and geoip enforcement

  ## Summary

  Add the full rule editor: list of rules (drag-to-reorder), per-rule
  editor with all matcher types, outbound-action picker including
  specific-profile and specific-group targets.

  ## Context

  The editor is the power-user surface. It lives on a dedicated Routes
  screen in the main nav drawer. Matchers are the superset: domain,
  domain_suffix, domain_regex, geosite, ip_cidr, geoip, port, source,
  network, process, package. Outbound actions pick from the enum plus
  existing profiles and groups.

  ## Acceptance criteria

  - [ ] Routes screen in main nav shows the rule list with
        drag-to-reorder, enable-toggle per rule, name + summary line.
  - [ ] Rule editor has collapsible sections per matcher type; empty
        matchers are absent from the compiled rule.
  - [ ] Geosite / geoip pickers surface the categories / country codes
        from the loaded DBs; autocomplete on type.
  - [ ] Package picker uses the existing `PackageCache` to show icon +
        label; multi-select.
  - [ ] Outbound picker: Proxy / Bypass / Block / specific profile /
        specific group.
  - [ ] Validation: empty rule cannot save; conflicting matchers (e.g.
        port 80 AND port 443 only) are not auto-corrected — first match
        wins at runtime.
  - [ ] Rule list honors the first-match-wins runtime semantic; reorder
        persists immediately.
  - [ ] Accessibility: drag-reorder has keyboard equivalents.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/ui/RouteSettingsActivity.kt` — the editor Activity: every matcher section (domains, IP CIDR, ports, source, network, protocol, process, packages, outbound). Port the section list and the ordering.
  - `app/src/main/java/io/nekohasekai/sagernet/ui/RouteFragment.kt` — the routing rule list with drag-to-reorder.
  - `app/src/main/res/xml/route_preferences.xml` — reference for the field ordering in the editor.
  - `app/src/main/java/io/nekohasekai/sagernet/ui/AppListActivity.kt` — the package-picker sub-screen. Port the icon+label multi-select pattern.

  **Adapt:** Matcher section set, drag-reorder, outbound picker (Proxy/Bypass/Block/specific profile), package multi-select. **Skip:** NekoBox's XML-Preference rendering (build Compose). **Improve over NekoBox:** add outbound-picker option "specific group" in addition to "specific profile" (NekoBox's group-selector outbound already supports this via ProxyGroup.isSelector; surface it explicitly in the rule outbound picker).

  ## Links

  - [[Epic - Advanced routing rules and geoip enforcement]]
  - [[Add RuleEntity Room table and repository]]

- [ ] #task Add geoip.db and geosite.db runtime loader and lookup #repo/RIPDPI #area/advanced-routing-rules-and #status/backlog ⏫ [paperclip:POY-121]
  - Paperclip: POY-121 · assigned to: unassigned
  - Parent: POY-36 (Epic - Advanced routing rules and geoip enforcement)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, routing, geoip
  - **source:** `TaskNotes/Tasks/Add geoip.db and geosite.db runtime loader and lookup.md`
  - **epic:** Epic - Advanced routing rules and geoip enforcement

  ## Summary

  Load `geoip.db` (MaxMind format) and `geosite.db` (SagerNet sing-geosite
  binary) at service start, expose lookup APIs to the Rust rule matcher
  for `geoip:<code>` and `geosite:<category>` rule entries.

  ## Context

  The geosite Protobuf schema already exists; this task is the runtime:
  memory-mapped load, indexed lookup, version-tracked. Files live in
  external files dir and can be replaced by the asset-provider task
  without reboot (reload signal).

  ## Acceptance criteria

  - [ ] `ripdpi-geo` crate opens `geoip.db` via `maxminddb-golang`
        equivalent Rust crate and `geosite.db` via a native parser.
  - [ ] Lookup is O(log n) or better for geoip (CIDR lookup), O(1)
        amortized for geosite (category pre-compiled).
  - [ ] Memory-map both DBs; on reload, atomically swap the mapping so
        in-flight lookups see either the old or new version consistently.
  - [ ] JNI exposes the version string of each loaded DB for UI
        surfacing.
  - [ ] Missing DB files log a typed warning and disable geo rules
        cleanly; no runtime panic.
  - [ ] Unit tests cover: valid lookup, missing category, missing
        geoip file, version read.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`) — the loader lives in the Go `libcore` module, not the app:

  - `libcore/geoip.go` — opens `geoip.db` (MaxMind format), iterates all networks, filters by country code, produces `[]option.HeadlessRule`. Uses `github.com/oschwald/maxminddb-golang`.
  - `libcore/geosite.go` — opens `geosite.db` (sing-box native binary), reads a category, compiles domain/suffix/keyword/regex sets.
  - `libcore/assets_android.go` — APK-asset-extraction path (pull `geoip.dat`/`geosite.dat` from assets on first run).

  **Rust port path:**
  - Use the [`maxminddb`](https://crates.io/crates/maxminddb) crate (Rust MaxMind DB reader) for geoip.
  - Geosite binary format: port the sing-box Go reader (~200 lines) — no Rust crate exists. The format is a simple length-prefixed series of categories + domain entries with type tags (PLAIN/REGEX/DOMAIN/FULL).

  **Adapt:** Both file formats end-to-end, category lookup API, memory-map + atomic-swap pattern (for reload without restart). **Skip:** Go implementation's headless-rule output (RIPDPI's rule matcher has its own match API).

  ## Links

  - [[Epic - Advanced routing rules and geoip enforcement]]
  - [[Add Rust rule matcher with domain ip port process matchers]]

- [ ] #task Adopt Android 17 system split-tunnel UI via ACTION_VPN_APP_EXCLUSION_SETTINGS #repo/RIPDPI #area/advanced-routing-rules-and #status/backlog 🔼 [paperclip:POY-155]
  - Paperclip: POY-155 · assigned to: unassigned
  - Parent: POY-36 (Epic - Advanced routing rules and geoip enforcement)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-25
  - **dateModified:** 2026-04-25
  - **area:** android
  - **tags:** task, feature, ripdpi, android-17, vpnservice, split-tunnel
  - **source:** `TaskNotes/Tasks/Adopt Android 17 system split-tunnel UI via ACTION_VPN_APP_EXCLUSION_SETTINGS.md`
  - **epic:** Epic - Advanced routing rules and geoip enforcement

  ## Summary

  Android 17 added a system-owned split-tunnel UI: VPN apps fire `ACTION_VPN_APP_EXCLUSION_SETTINGS` and the OS persists user exclusions across reconnects. Wire this from RIPDPI settings so the per-app exclusion state lives in the OS instead of in-app, reducing the risk of exclusion loss on reconnect.

  ## Research citation

  [[ripdpi-android-research-2026-04-25]] §Android platform — Android 17 Beta 3 (2026-03) added the `ACTION_VPN_APP_EXCLUSION_SETTINGS` intent. Apps fire it to delegate per-app exclusion to a persistent OS-managed screen; exclusions survive reconnects. The underlying `VpnService.Builder` allowlist/blocklist API is unchanged — this is a UX standardisation layer on top.

  ## Acceptance criteria

  - [ ] Settings screen on Android 17+ fires `ACTION_VPN_APP_EXCLUSION_SETTINGS` to delegate to OS UI
  - [ ] Android < 17 fallback retains in-app exclusion UI
  - [ ] Exclusions verified to persist across VPN reconnects (OS-managed state)
  - [ ] Manifest declares supported intent for system discovery

  ## Links

  - Project: [[ripdpi-android]]
  - Epic: [[Epic - Advanced routing rules and geoip enforcement]]
  - Research: [[ripdpi-android-research-2026-04-25]] §Android platform

- [ ] #task Adopt process-based per-package routing via Xray TUN routeOnly #repo/RIPDPI #area/advanced-routing-rules-and #status/backlog 🔼 [paperclip:POY-156]
  - Paperclip: POY-156 · assigned to: unassigned
  - Parent: POY-36 (Epic - Advanced routing rules and geoip enforcement)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-25
  - **dateModified:** 2026-04-25
  - **area:** android
  - **tags:** task, feature, ripdpi, routing, per-package, vpn-detection
  - **source:** `TaskNotes/Tasks/Adopt process-based per-package routing via Xray TUN routeOnly.md`
  - **epic:** Epic - Advanced routing rules and geoip enforcement

  ## Summary

  v2rayNG 2.1.0 (2026-04-17) shipped per-package routing via Xray TUN with `routeOnly` enabled. Adopt the same pattern so RIPDPI users can route VPN-detection-positive Russian apps (Sber, RuStore, Wildberries, T-Bank, etc.) directly while everything else goes through VLESS — addressing the platform-VPN-detection regime active since 2026-04-15.

  ## Research citation

  [[ripdpi-android-research-2026-04-25]] §Peer mobile clients — v2rayNG 2.1.0 added process/package-name-based routing (Android 10+, requires Xray TUN with `routeOnly` enabled) and outbound alias support for traffic-splitting to different servers via Xray TUN. The pattern complements the platform-VPN-detection regime that began enforcement on 2026-04-15 (RKS Global: 22/30 top Russian apps detect VPN, 19/30 report VPN status server-side; see `[[platform-vpn-detection-april-2026]]`).

  ## Acceptance criteria

  - [ ] TUN bridge enables `routeOnly` mode per v2rayNG 2.1.0 reference
  - [ ] UI exposes per-package allowlist (route through tunnel) and blocklist (route direct)
  - [ ] Default blocklist seeds with VPN-detection-positive apps (RuStore, Sber, Wildberries) per [[platform-vpn-detection-april-2026]]
  - [ ] Integration test verifies blocklisted apps egress with non-tunnel IP while allowed apps go through VLESS

  ## Links

  - Project: [[ripdpi-android]]
  - Epic: [[Epic - Advanced routing rules and geoip enforcement]]
  - Research: [[ripdpi-android-research-2026-04-25]] §Peer mobile clients


## amneziawg-outbound-support

- [ ] #task Add AmneziaWG Kotlin config model and dot-conf parser extensions #repo/RIPDPI #area/amneziawg-outbound-support #status/backlog ⏫ [paperclip:POY-63]
  - Paperclip: POY-63 · assigned to: unassigned
  - Parent: POY-37 (Epic - AmneziaWG outbound support)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, protocol, amneziawg, config
  - **source:** `TaskNotes/Tasks/Add AmneziaWG Kotlin config model and dot-conf parser extensions.md`
  - **epic:** Epic - AmneziaWG outbound support

  ## Summary

  Extend the Kotlin config model with an `AmneziaWGBean` (or extend
  `WireGuardBean`) holding all AWG obfuscation fields, and extend the
  `.conf` INI parser so both vanilla WireGuard and AmneziaWG files parse
  into the correct bean type.

  ## Context

  The `.conf` file format is the WireGuard INI format. AmneziaWG adds
  keys on the `[Interface]` block: `Jc`, `Jmin`, `Jmax`, `S1`, `S2`,
  `S3`, `S4`, `H1`, `H2`, `H3`, `H4`, `I1`, `I2`, `I3`, `I4`, `I5`. A
  file with none of these parses as vanilla WireGuard; a file with any
  of them parses as AmneziaWG. Router behavior at the subscription
  import layer is covered by the companion task.

  ## Acceptance criteria

  - [ ] `AmneziaWGBean` class with all AWG obfuscation fields, inheriting
        the WireGuard field set (private key, address, DNS, MTU, peers).
  - [ ] Field validation: `Jc`, `Jmin`, `Jmax`, `S1`..`S4` are
        non-negative integers; `H1`..`H4` are 4-byte unsigned values
        (stored as UInt or hex string); `I1`..`I5` are hex strings.
  - [ ] `.conf` parser detects AWG keys in the `[Interface]` block and
        returns an `AmneziaWGBean`; absence of every AWG key returns a
        `WireGuardBean`.
  - [ ] Round-trip: `parse(string) → toConfString()` produces
        byte-equivalent output (modulo key ordering and whitespace).
  - [ ] Unit tests: vanilla WG config, AWG config with all fields,
        AWG config with partial fields, AWG config with only `Jc`,
        malformed fields (non-numeric, wrong byte count), unknown keys
        (should be ignored with a warning, not a hard error).
  - [ ] Kryo equality on `AmneziaWGBean` is byte-stable for dedup.

  ## Source references

  **amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`) — this is the definitive reference; port the logic essentially verbatim:

  - `tunnel/src/main/java/org/amnezia/awg/config/Interface.java`
    - **Lines 49–64:** all 16 obfuscation field declarations (`junkPacketCount`, `junkPacketMinSize`, `junkPacketMaxSize`, `initPacketJunkSize`, `responsePacketJunkSize`, `cookieReplyPacketJunkSize`, `transportPacketJunkSize`, `initPacketMagicHeader`, `responsePacketMagicHeader`, `underloadPacketMagicHeader`, `transportPacketMagicHeader`, `specialJunkI1`..`specialJunkI5`).
    - **Lines 101–184:** the `switch` in `parse(lines)` that recognizes every AWG key (`jc`, `jmin`, `jmax`, `s1`..`s4`, `h1`..`h4`, `i1`..`i5`). **Port this switch verbatim** including the lower-casing of keys and the `Integer.parseUnsignedInt` / hex-string parse rules.
    - **Lines 504–519:** `toAwgQuickString()` — emits capitalized keys with spaces (`Jc = 4
  `).
    - **Lines 534–549:** `toAwgUserspaceString()` — emits lowercase keys without spaces (`jc=4
  `).
  - `tunnel/src/main/java/org/amnezia/awg/config/Config.java` — the top-level `parse(InputStream)` that dispatches lines to `Interface` or `Peer` by section header. Port the section dispatch.
  - `tunnel/src/main/java/org/amnezia/awg/config/Peer.java` — standard WG peer; no AWG extensions. Port verbatim or reuse RIPDPI's existing WireGuard peer model.

  **License:** amneziawg-android is Apache 2.0 — compatible with whatever license RIPDPI uses. Include SPDX header per file when porting.

  **Adapt:** The 16-field set, the parse switch, both serializer variants. **Skip:** Java Optional-wrapped fields (use Kotlin nullables).

  ## Links

  - [[Epic - AmneziaWG outbound support]]
  - [[Add WireGuard INI subscription parser]]

- [ ] #task Add AmneziaWG profile editor screen with obfuscation fields #repo/RIPDPI #area/amneziawg-outbound-support #status/backlog 🔼 [paperclip:POY-64]
  - Paperclip: POY-64 · assigned to: unassigned
  - Parent: POY-37 (Epic - AmneziaWG outbound support)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, protocol, amneziawg, ui
  - **source:** `TaskNotes/Tasks/Add AmneziaWG profile editor screen with obfuscation fields.md`
  - **epic:** Epic - AmneziaWG outbound support

  ## Summary

  Add an `AmneziaWGProfileScreen` Compose editor that reuses the existing
  WireGuard profile layout and adds inline fields for every AWG
  obfuscation parameter.

  ## Context

  Follow the reference client's UX: obfuscation fields are **inline in
  the main editor**, not hidden behind an "Advanced" toggle, because
  these values are server-coordinated and the user is expected to paste
  them verbatim from their provider. Group the AWG fields into one
  labeled section beneath the standard Interface/Peer fields.

  ## Acceptance criteria

  - [ ] New Compose screen `AmneziaWGProfileScreen` in the app module's
        profile-editor navigation.
  - [ ] All standard WireGuard fields (private key, address, DNS, MTU,
        peer public key, peer endpoint, allowed IPs, preshared key,
        persistent keepalive) surface and behave identically to the
        existing WireGuard editor.
  - [ ] New "Obfuscation" section with one `OutlinedTextField` per AWG
        parameter: Jc, Jmin, Jmax, S1, S2, S3, S4, H1, H2, H3, H4, I1,
        I2, I3, I4, I5.
  - [ ] Per-field validation mirrors the parser: integer ranges for
        Jc/Jmin/Jmax/S1–S4; 4-byte unsigned for H1–H4; hex strings for
        I1–I5.
  - [ ] Paste-from-clipboard button on the section header: if the
        clipboard contains a full AWG `.conf`, parse it and populate
        all fields.
  - [ ] Private key + preshared key fields use the existing biometric-
        gated reveal pattern from other profile editors.
  - [ ] Screen layout works in RTL locales; Roborazzi screenshot test
        covers en / ar / fa / zh-CN.
  - [ ] No secret material renders in logs during editing; standard
        redaction applies to all diagnostic surfaces.

  ## Source references

  **amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`) — port UX + field ordering:

  - `ui/src/main/java/org/amnezia/awg/viewmodel/InterfaceProxy.kt:57-159` — all 16 `@Bindable` obfuscation properties. Port the property set and observable-binding approach (RIPDPI's Compose equivalent is `mutableStateOf` + `ViewModel`).
  - `ui/src/main/java/org/amnezia/awg/fragment/TunnelEditorFragment.kt` — editor host. Biometric-gated private-key reveal pattern (lines around `BiometricAuthenticator` invocation). **Adopt this pattern** for RIPDPI's private-key reveal.
  - `ui/src/main/res/layout/tunnel_editor_fragment.xml:244-594` — the XML layout for all AWG obfuscation fields. This is the definitive ordering and field-grouping reference. Translate to Compose but preserve the section order: standard WG fields → `DNS` → `MTU` → obfuscation fields inline.
  - `ui/src/main/res/values/strings.xml` — AWG field labels (`junk_packet_count`, `init_packet_magic_header`, etc.) in English. Use as baseline for RIPDPI string resource keys.

  **License:** Apache 2.0 — compatible.

  **Adapt:** Field set, field order (inline, not hidden), biometric gate, `inputType="number"` vs `textNoSuggestions` per field type. **Skip:** XML layout and Data Binding (Compose).

  ## Links

  - [[Epic - AmneziaWG outbound support]]
  - [[Add AmneziaWG Kotlin config model and dot-conf parser extensions]]

- [ ] #task Add amneziawg URI codec for profile share and import #repo/RIPDPI #area/amneziawg-outbound-support #status/backlog 🔼 [paperclip:POY-99]
  - Paperclip: POY-99 · assigned to: unassigned
  - Parent: POY-37 (Epic - AmneziaWG outbound support)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, protocol, amneziawg, uri
  - **source:** `TaskNotes/Tasks/Add amneziawg URI codec for profile share and import.md`
  - **epic:** Epic - AmneziaWG outbound support

  ## Summary

  Define and implement an `amneziawg://` URI scheme for single-profile
  sharing, plus integrate it into the share-sheet intent filters, QR
  scanner dispatcher, and clipboard-import flow.

  ## Context

  There is no standardized AmneziaWG share-URI scheme in the upstream
  ecosystem (the reference client uses `.conf` files and QR-of-`.conf`).
  Define one locally and document it. Structure: scheme + base64url-
  encoded AWG config fragment (or query-param layout). Pick the simpler
  format. Share-sheet registration extends the filter list from the
  subscription/QR epics.

  ## Acceptance criteria

  - [ ] Format documented in `docs/` with rationale and example:
        likely `amneziawg://base64url-encoded-conf` or
        `amneziawg://host:port?<params>`.
  - [ ] Codec: `AmneziaWGBean → URI` and `URI → AmneziaWGBean` round-trip
        losslessly; unit-tested.
  - [ ] Share-sheet filter registered in AndroidManifest so the app
        appears as a handler when users tap `amneziawg://…` links.
  - [ ] QR scanner recognizes the scheme and dispatches to profile-edit.
  - [ ] Clipboard-import menu recognizes the scheme.
  - [ ] Profile-detail "Share" action emits both `amneziawg://` URI and
        a QR code containing it (alongside the existing `.conf` share).
  - [ ] Secrets-in-URI warning is shown once before sharing, same
        pattern as standard profile share.

  ## Source references

  **No direct upstream analog.** Neither amneziawg-android nor amneziawg-go defines a URI scheme; sharing is `.conf`-file or QR-of-`.conf` only. RIPDPI invents `amneziawg://` for ergonomic single-profile sharing.

  **Pattern references** (all NekoBox paths rooted at `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - NekoBoxForAndroid `app/src/main/java/io/nekohasekai/sagernet/fmt/hysteria/HysteriaFmt.kt` — `hysteria2://` URI codec is a good template (UDP-based protocol with query-param auxiliary fields). Follow the same shape: `amneziawg://<base64-private-key>@<host>:<port>?public_key=...&allowed_ips=...&jc=...&h1=...&s1=...`.
  - NekoBoxForAndroid `app/src/main/java/io/nekohasekai/sagernet/fmt/wireguard/WireGuardFmt.kt` — the WG-URI codec (`wireguard://`) shows how to serialize a WG-shaped profile. Extend with AWG query params.

  **Reference URI layout** (proposed, documented in `docs/`):
  ```
  amneziawg://<base64url(private-key)>@<host>:<port>
    ?public_key=<base64url>
    &allowed_ips=<cidr,cidr>
    &mtu=<n>
    &preshared_key=<base64url>
    &jc=4&jmin=40&jmax=70
    &s1=0&s2=0&s3=0&s4=0
    &h1=<hex>&h2=<hex>&h3=<hex>&h4=<hex>
    &i1=<hex>&i2=<hex>&i3=<hex>&i4=<hex>&i5=<hex>
  #<name>
  ```

  **Adapt:** Hysteria2-style URI shape from NekoBox. **Invent:** All AWG-specific query-param names (this task defines them).

  ## Links

  - [[Epic - AmneziaWG outbound support]]
  - [[Add share-sheet handler for proxy URI schemes]]
  - [[Add QR scanner screen with CameraX and ML Kit]]

- [ ] #task Add strategy-pack compatibility hints for AmneziaWG servers #repo/RIPDPI #area/amneziawg-outbound-support #status/backlog 🔽 [paperclip:POY-148]
  - Paperclip: POY-148 · assigned to: unassigned
  - Parent: POY-37 (Epic - AmneziaWG outbound support)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, protocol, amneziawg, strategy
  - **source:** `TaskNotes/Tasks/Add strategy-pack compatibility hints for AmneziaWG servers.md`
  - **epic:** Epic - AmneziaWG outbound support

  ## Summary

  Teach the strategy-pack metadata schema that AmneziaWG profiles are
  "server-coordinated fixed config": the obfuscation params must match
  the server exactly, and the strategy learner must not vary them.

  ## Context

  RIPDPI's strategy learner rotates TLS arms, QUIC variants, direct-mode
  verdicts, etc. AmneziaWG's obfuscation params are part of the server's
  config; varying them client-side would break every handshake. The
  learner should treat AWG profiles as opaque and not emit candidate
  arms that touch `Jc/Jmin/Jmax/S1–S4/H1–H4/I1–I5`.

  ## Acceptance criteria

  - [ ] Strategy-pack schema (`StrategyPackCatalog`) gains a
        `fixed_config_protocols` field listing protocol types whose
        params must not be varied.
  - [ ] `amneziawg` is included in that list in the default pack.
  - [ ] Strategy learner / candidate generator honors the field: no
        generated arm mutates an AWG profile's obfuscation params.
  - [ ] Runtime selector respects the hint: it still picks between
        AWG profiles within a group, but never rewrites an individual
        AWG profile's params.
  - [ ] Documented in `docs/strategy-packs.md` so offline pack authors
        know the constraint.
  - [ ] Unit test: an attempt to vary an AWG profile's `Jc` in a
        generated candidate is rejected in the pack-validation pass.

  ## Links

  - [[Epic - AmneziaWG outbound support]]

- [ ] #task Fork boringtun and add AmneziaWG handshake obfuscation #repo/RIPDPI #area/amneziawg-outbound-support #status/backlog ⏫ [paperclip:POY-193]
  - Paperclip: POY-193 · assigned to: unassigned
  - Parent: POY-37 (Epic - AmneziaWG outbound support)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, protocol, amneziawg, rust, boringtun
  - **source:** `TaskNotes/Tasks/Fork boringtun and add AmneziaWG handshake obfuscation.md`
  - **epic:** Epic - AmneziaWG outbound support

  ## Summary

  Fork `boringtun` into an internal `ripdpi-amneziawg-core` crate and
  add the full set of AmneziaWG handshake modifications: Jc/Jmin/Jmax
  junk packets before initiation, H1–H4 magic header substitution for
  all four packet types, S1–S4 size padding, and AWG 2.0 I1–I5 special
  junk intervals.

  ## Context

  The canonical implementation lives in `amnezia-vpn/amneziawg-go`. That
  Go code is small and well-structured; porting the deltas to a boringtun
  fork is realistic. The alternative of wrapping amneziawg-go via CGo
  conflicts with RIPDPI's Rust-first architecture. Reuse `ripdpi-warp-
  core`'s smoltcp virtual stack for in-app TCP/UDP; AWG only changes the
  WireGuard wire protocol, not the upper stack.

  ## Acceptance criteria

  - [ ] `ripdpi-amneziawg-core` crate exists in the workspace with a
        clear BSD-3 (boringtun inheritance) + MIT (amneziawg-go ports)
        dual-license file header on each file.
  - [ ] Handshake prelude sends `Jc` random packets, each of size drawn
        uniformly from `[Jmin, Jmax]`, before the real initiation.
  - [ ] Initiation packet type byte `0x01` is replaced with a 4-byte
        `H1` magic header; `S1` bytes of junk appended before the MAC.
  - [ ] Response packet: `0x02` → `H2`, `S2` bytes padding.
  - [ ] Cookie-reply: `0x03` → `H3`, `S3` bytes padding.
  - [ ] Transport: `0x04` → `H4`, `S4` bytes padding.
  - [ ] AWG 2.0 I1–I5 special junk intervals: handshake inserts fixed
        hex-encoded junk frames at the specified positions in the flow,
        matching amneziawg-go v0.2.16 reference behavior.
  - [ ] Defaults: when `Jc=0` and S1..S4=0 and H1..H4 are unset, the
        crate wire-output is byte-identical to upstream WireGuard. This
        invariant is unit-tested against a WireGuard test vector.
  - [ ] Reference test vectors ported from amneziawg-go cover each
        obfuscation param independently and in combination.
  - [ ] Constant-time crypto preserved; no timing side-channels
        introduced by the header-substitution paths.
  - [ ] Shutdown joins bounded handler work; same invariants as
        `ripdpi-warp-core`.

  ## Source references

  **Primary spec — amneziawg-go** ([repo](https://github.com/amnezia-vpn/amneziawg-go), pin `v0.2.16`). The entire protocol delta is here:

  - `device/peer.go` and `device/send.go` — Jc junk-packet generation (search for `junkPacketCount`). Packets sized uniformly in `[Jmin, Jmax]` are sent before the real initiation.
  - `device/noise-protocol.go` — `H1`–`H4` magic-header substitution. Search for references to `InitiationPacketMagicHeader`, `ResponsePacketMagicHeader`, `UnderloadPacketMagicHeader`, `TransportPacketMagicHeader`. The original WireGuard type bytes `0x01..0x04` are replaced with these 4-byte values.
  - `device/noise-protocol.go` — `S1`..`S4` size padding inserted between the protocol payload and the MAC.
  - `device/device.go` — AWG 2.0 `I1`..`I5` "special junk" intervals (look for `SpecialJunk*` fields). Port these verbatim.
  - `device/uapi.go` — UAPI key handlers for `jc`, `jmin`, `jmax`, `s1`..`s4`, `h1`..`h4`, `i1`..`i5`. Shows the full config-to-runtime plumbing.

  **amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`):

  - `tunnel/tools/libwg-go/api-android.go` — the JNI↔Go bridge (`awgTurnOn`, `awgGetConfig`, etc.). Reference for how Android hands the config string to Go. RIPDPI equivalent is JNI↔Rust; the boundary shape is the same.

  **Rust starting point — boringtun** ([repo](https://github.com/cloudflare/boringtun)):
  - `boringtun/src/noise/` — hand-rolled Noise_IK handshake. The files to patch:
    - `boringtun/src/noise/handshake.rs` — inject Jc-count junk packets before `first_time.send()`; swap type bytes for H1/H2/H3/H4.
    - `boringtun/src/noise/mod.rs` — protocol constants; add AWG packet-type aliases.
  - License: BSD-3-Clause (copyable with attribution).

  **License note:** boringtun is BSD-3; amneziawg-go is MIT. Ported amneziawg-go code must carry MIT attribution at the file level. Do not mix inside a single source file; separate the Noise primitives (BSD-3) from the AWG patches (MIT).

  **Adapt:** amneziawg-go's full protocol delta, boringtun's Noise skeleton. **Skip:** amneziawg-go's IPC layer (RIPDPI uses direct FFI, not UAPI socket).

  ## Links

  - [[Epic - AmneziaWG outbound support]]
  - https://github.com/amnezia-vpn/amneziawg-go

- [ ] #task Wire AmneziaWG into the subscription WireGuard-INI parser #repo/RIPDPI #area/amneziawg-outbound-support #status/backlog 🔼 [paperclip:POY-252]
  - Paperclip: POY-252 · assigned to: unassigned
  - Parent: POY-37 (Epic - AmneziaWG outbound support)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, protocol, amneziawg, subscription
  - **source:** `TaskNotes/Tasks/Wire AmneziaWG into the subscription WireGuard-INI parser.md`
  - **epic:** Epic - AmneziaWG outbound support

  ## Summary

  Extend the WireGuard-INI subscription parser so a subscription
  containing an AWG-flavored `[Interface]` block produces an
  `AmneziaWGBean`, not a vanilla `WireGuardBean`.

  ## Context

  Depends on the `AmneziaWGBean` + parser extension task landing first.
  Detection is by presence of any AWG key in the `[Interface]` block;
  zero AWG keys → vanilla WG bean; any AWG key → AWG bean. Multi-peer
  INI files follow the same per-peer semantics as the existing parser.
  No new subscription format is added.

  ## Acceptance criteria

  - [ ] `RawUpdater` (or equivalent) WireGuard-INI parser routes
        `[Interface]` blocks to the right bean type based on AWG-key
        presence.
  - [ ] Multi-peer INI files work: interface-scope AWG fields apply to
        all peer profiles derived from the file.
  - [ ] Mixed subscription: an INI file with both an AWG interface and
        a vanilla interface (unusual but possible) produces the right
        bean for each.
  - [ ] Subscription refresh preserves user-edited override fields on
        AWG beans just as on vanilla WG beans.
  - [ ] Unit tests cover: AWG INI, vanilla INI, AWG with partial fields,
        malformed AWG fields (warning, skip line, continue).

  ## Source references

  **amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`):

  - `tunnel/src/main/java/org/amnezia/awg/config/Interface.java:101-184` — the INI-key `switch` is already the canonical implementation of routing AWG keys to the right fields. Shared with the `.conf` parser task; this task plugs the same shape into the subscription path.
  - `tunnel/src/main/java/org/amnezia/awg/config/Config.java` — `parse(InputStream)` — section dispatch already ignores whitespace-surrounded keys and is tolerant of blank lines. Port directly.

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt` — `parseWireGuard()` — the existing subscription WG-INI parser. This task extends it with the AWG-key detection branch: if `[Interface]` contains any AWG key, emit an `AmneziaWGBean`; else emit `WireGuardBean`.

  **Adapt:** Detection logic (any of `jc`/`jmin`/`jmax`/`s1..s4`/`h1..h4`/`i1..i5` → AWG bean), graceful degradation if AWG fields are malformed. **Skip:** nothing meaningful — this is a small targeted extension.

  ## Links

  - [[Epic - AmneziaWG outbound support]]
  - [[Add WireGuard INI subscription parser]]
  - [[Add AmneziaWG Kotlin config model and dot-conf parser extensions]]


## boot-autostart-and-session

- [ ] #task Add boot-completed receiver with dynamic enable #repo/RIPDPI #area/boot-autostart-and-session #status/backlog 🔼 [paperclip:POY-103]
  - Paperclip: POY-103 · assigned to: unassigned
  - Parent: POY-38 (Epic - Boot autostart and session persistence)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, boot, receiver
  - **source:** `TaskNotes/Tasks/Add boot-completed receiver with dynamic enable.md`
  - **epic:** Epic - Boot autostart and session persistence

  ## Summary

  Add a `BootReceiver` that handles `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`,
  and `MY_PACKAGE_REPLACED`, toggled on only when the user has enabled
  "Start on boot".

  ## Context

  NekoBox enables the receiver component dynamically via
  `PackageManager.setComponentEnabledSetting` so the broadcast filter only
  exists while needed. Default state must be `DISABLED`; enabling it without
  user opt-in is both a battery concern and a surprise behavior.

  ## Acceptance criteria

  - [ ] `BootReceiver` declared in manifest with
        `android:enabled="false"` and filters for all three actions.
  - [ ] Runtime enable/disable driven by a single repository method wired
        to the Settings toggle.
  - [ ] `RECEIVE_BOOT_COMPLETED` permission declared.
  - [ ] On fire, the receiver re-schedules subscription auto-update
        WorkManager job and, if active-profile exists + "start on boot" is
        on, starts the appropriate service mode.
  - [ ] `MY_PACKAGE_REPLACED` path is gated by the "was running before
        update" flag (see companion task).
  - [ ] Receiver work is short and offloads to a WorkManager one-shot;
        no heavy work in `onReceive`.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/BootReceiver.kt` — the full receiver. Handles `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`. Dynamic enable via `PackageManager.setComponentEnabledSetting(ComponentName, COMPONENT_ENABLED_STATE_{ENABLED,DISABLED}, DONT_KILL_APP)`.
  - `app/src/main/AndroidManifest.xml` — receiver declaration with `android:enabled="false"` initially and the three intent-action filters.
  - Companion object `setEnabled(enabled: Boolean)` — the API wired to the Settings toggle.

  **amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`) — cross-reference for the WireGuard-ecosystem pattern:

  - `ui/src/main/java/org/amnezia/awg/BootShutdownReceiver.kt` — handles both `BOOT_COMPLETED` and `ACTION_SHUTDOWN` (save-state-on-shutdown is a WireGuard pattern absent from NekoBox; consider adopting).

  **Adapt:** Dynamic-enable pattern, three-action filter set. **Consider:** adding `ACTION_SHUTDOWN` handler from AWG pattern to persist clean-shutdown flag. **Skip:** NekoBox's subscription-updater re-registration (handled by WorkManager persistence in RIPDPI).

  ## Links

  - [[Epic - Boot autostart and session persistence]]

- [ ] #task Add last-active-profile persistence in direct-boot storage #repo/RIPDPI #area/boot-autostart-and-session #status/backlog 🔼 [paperclip:POY-123]
  - Paperclip: POY-123 · assigned to: unassigned
  - Parent: POY-38 (Epic - Boot autostart and session persistence)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, boot, storage
  - **source:** `TaskNotes/Tasks/Add last-active-profile persistence in direct-boot storage.md`
  - **epic:** Epic - Boot autostart and session persistence

  ## Summary

  Persist a non-sensitive pointer to the last-active profile (id + service
  mode) in a device-protected (direct-boot-aware) storage location so
  `LOCKED_BOOT_COMPLETED` can resume before the user unlocks.

  ## Context

  The profile bean itself (which contains secrets) must stay in user-
  protected Credential Encrypted storage. Only a stable id and the service
  mode go into the direct-boot path. The service resumes with the pointer;
  secret-bearing fields are read after unlock completes, and the tunnel
  is refreshed at that point if anything had to hold.

  ## Acceptance criteria

  - [ ] A `DeviceProtectedSettings` store holds `{ profileId,
        serviceMode }` — no secrets.
  - [ ] The full profile bean never lands in device-protected storage.
  - [ ] Resume logic: at `LOCKED_BOOT_COMPLETED`, start the service with
        the pointer only; at user unlock, re-materialize the full profile
        and trigger the supervisor's existing reload path.
  - [ ] If the referenced profile is deleted, the pointer clears silently
        and the service does not attempt to start.
  - [ ] Unit tests cover the before/after-unlock transition.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt` — `selectedProxy: Long` property, persisted in the Room-backed PreferenceDataStore.
  - `app/src/main/java/io/nekohasekai/sagernet/BootReceiver.kt` — on `LOCKED_BOOT_COMPLETED`, reads the last-active profile and starts the service before user unlock. NekoBox does NOT split storage into device-protected vs user-protected — **this is a deviation point** for RIPDPI.

  **Android reference** — for direct-boot storage split, follow Android docs on `createDeviceProtectedStorageContext()`. The profile ID (Long) is non-sensitive; the profile bean (with keys) is sensitive. Split at that boundary.

  **Adapt:** The `selectedProxy` pointer concept. **Improve over NekoBox:** split device-protected (ID only) vs credential-protected (full bean). NekoBox stores the full Room DB in user-protected by default but does not surface the boundary.

  ## Links

  - [[Epic - Boot autostart and session persistence]]
  - [[Add boot-completed receiver with dynamic enable]]

- [ ] #task Add package-replaced restart gated on prior running state #repo/RIPDPI #area/boot-autostart-and-session #status/backlog 🔽 [paperclip:POY-130]
  - Paperclip: POY-130 · assigned to: unassigned
  - Parent: POY-38 (Epic - Boot autostart and session persistence)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, boot
  - **source:** `TaskNotes/Tasks/Add package-replaced restart gated on prior running state.md`
  - **epic:** Epic - Boot autostart and session persistence

  ## Summary

  When the app is updated (MY_PACKAGE_REPLACED), auto-restart the tunnel
  only if the session was running at the moment the update installed.

  ## Context

  Resuming the session after an update is expected behavior for always-on
  VPN use, but blanket resume on every update is wrong (a user may have
  stopped the tunnel deliberately before the update). Persist a "was
  running" flag on service stop-or-update; read and clear it on the
  receive path.

  ## Acceptance criteria

  - [ ] A persistent `wasRunningAtUpdate` flag is set when the session is
        active AND the user is not in the Settings → Stop flow; cleared
        on explicit user-initiated stop.
  - [ ] On `MY_PACKAGE_REPLACED`, the receiver reads and clears the flag;
        auto-start only when it was set.
  - [ ] Unit tests cover: updated-while-running, updated-while-stopped,
        stopped-then-updated.
  - [ ] Flag location is direct-boot aware so the check works even before
        user unlock.
  - [ ] No secret material or profile identity surfaces in the flag
        itself.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/BootReceiver.kt` — the `MY_PACKAGE_REPLACED` branch is combined with `BOOT_COMPLETED` and re-reads `DataStore.persistAcrossReboot` without distinguishing "was running at update". **Do not copy this behavior.** NekoBox's approach auto-restarts on every update even if the user had deliberately stopped the tunnel — a correctness bug.
  - `app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt` — `DataStore.currentProfile` is cleared on explicit stop, so NekoBox does have the signal, but BootReceiver ignores it.

  **Adapt:** The receiver-branch structure. **Improve over NekoBox:** add a `wasRunningAtUpdate: Boolean` flag set when the service is torn down for an update (vs user-initiated stop), and gate the restart on it. This is an explicit correctness improvement documented in the acceptance criteria.

  ## Links

  - [[Epic - Boot autostart and session persistence]]

- [ ] #task Add start-on-boot user toggle and permission guard #repo/RIPDPI #area/boot-autostart-and-session #status/backlog 🔼 [paperclip:POY-147]
  - Paperclip: POY-147 · assigned to: unassigned
  - Parent: POY-38 (Epic - Boot autostart and session persistence)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, settings
  - **source:** `TaskNotes/Tasks/Add start-on-boot user toggle and permission guard.md`
  - **epic:** Epic - Boot autostart and session persistence

  ## Summary

  Add a "Start on boot" toggle in Settings that controls boot-receiver
  enable state, with a one-time prompt to whitelist from battery-saver /
  doze / vendor background-kill policies.

  ## Context

  On stock Android the toggle is enough. On vendor ROMs (MIUI, EMUI,
  OneUI, ColorOS, FuntouchOS), auto-start is gated by a separate vendor
  setting. The prompt should link out to the vendor setting on detection
  and not nag on subsequent launches.

  ## Acceptance criteria

  - [ ] Toggle in Settings labeled "Start on boot" with an explanatory
        caption.
  - [ ] First time enabling invokes `PowerManager.isIgnoringBattery
        Optimizations` check; if false, show rationale and launch the
        system intent.
  - [ ] Vendor-specific intent routing for at least: Xiaomi, Huawei,
        Oppo, Vivo, Samsung — each wrapped in a try/fallback to the
        generic settings intent.
  - [ ] Rejection of the battery whitelist does NOT reset the toggle;
        the user can still proceed with a warning banner showing expected
        reliability impact.
  - [ ] Toggle state persists; companion task handles the component
        enable/disable wiring.
  - [ ] Accessibility: toggle has description, vendor-setting link is
        keyboard-reachable.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt` — `persistAcrossReboot: Boolean` property (the NekoBox equivalent of "Start on boot").
  - `app/src/main/java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt` — the `SwitchPreference` bound to `persistAcrossReboot`. On toggle, calls `BootReceiver.setEnabled()`.

  **amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`):

  - `ui/src/main/java/org/amnezia/awg/activity/SettingsActivity.kt` — simpler toggle model; always-on is driven by the system VPN always-on setting, not an in-app toggle. RIPDPI should follow NekoBox's explicit toggle.

  **Adapt:** Named preference, on-toggle call to enable/disable receiver component. **Add (neither project has this):** vendor-ROM redirect intents (MIUI/EMUI/OneUI/ColorOS/FuntouchOS) and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` first-time prompt. These are a frequent support issue for both upstreams.

  ## Links

  - [[Epic - Boot autostart and session persistence]]
  - [[Add boot-completed receiver with dynamic enable]]


## cloudflare-publish-hardening

- [ ] #task Clean up Cloudflare credential artifacts on stop #repo/RIPDPI #area/cloudflare-publish-hardening #status/backlog ⏫ [paperclip:POY-168]
  - Paperclip: POY-168 · assigned to: unassigned
  - Parent: POY-39 (Epic - Cloudflare publish hardening)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, bug, ripdpi, cloudflare, privacy
  - **source:** `TaskNotes/Tasks/Clean up Cloudflare credential artifacts on stop.md`
  - **epic:** Epic - Cloudflare publish hardening

  ## Summary

  Named-tunnel credentials and config are written to persistent `filesDir`
  state and survive the session. `allowBackup="false"` prevents backup leak,
  but the files still persist unnecessarily.

  ## Audit citation

  - `core/service/.../CloudflarePublishRuntime.kt:673-680`

  ## Acceptance criteria

  - [ ] Ephemeral working directory used where possible (e.g. `cacheDir` or
        a session-scoped subdir).
  - [ ] Credential files deleted on session stop (success or error).
  - [ ] Stale credential files cleaned up at startup if a previous run
        crashed without cleanup.

  ## Links

  - [[Epic - Cloudflare publish hardening]]
  - [[ripdpi-android-audit-2026-04-20]]

- [ ] #task Install Cloudflare binaries once per ABI and version #repo/RIPDPI #area/cloudflare-publish-hardening #status/backlog 🔼 [paperclip:POY-209]
  - Paperclip: POY-209 · assigned to: unassigned
  - Parent: POY-39 (Epic - Cloudflare publish hardening)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, feature, ripdpi, cloudflare, performance
  - **source:** `TaskNotes/Tasks/Install Cloudflare binaries once per ABI and version.md`
  - **epic:** Epic - Cloudflare publish hardening

  ## Summary

  Binaries are copied from assets on every start — slow startup and extra
  flash churn.

  ## Audit citation

  - `core/service/.../CloudflarePublishRuntime.kt:529-545`

  ## Acceptance criteria

  - [ ] Install happens once, keyed by `(ABI, binary version hash)`.
  - [ ] Subsequent starts validate hash and skip copy.
  - [ ] Asset version change invalidates the install cache.
  - [ ] Startup latency measured before/after.

  ## Links

  - [[Epic - Cloudflare publish hardening]]
  - [[ripdpi-android-audit-2026-04-20]]

- [ ] #task Per-session CloudflarePublishRuntime instances #repo/RIPDPI #area/cloudflare-publish-hardening #status/backlog 🔼 [paperclip:POY-218]
  - Paperclip: POY-218 · assigned to: unassigned
  - Parent: POY-39 (Epic - Cloudflare publish hardening)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, feature, ripdpi, cloudflare
  - **source:** `TaskNotes/Tasks/Per-session CloudflarePublishRuntime instances.md`
  - **epic:** Epic - Cloudflare publish hardening

  ## Summary

  `DefaultCloudflarePublishRuntimeFactory` returns a singleton runtime — state
  leaks across sessions.

  ## Audit citation

  - `core/service/.../CloudflarePublishRuntime.kt:442-464`

  ## Acceptance criteria

  - [ ] Factory creates a fresh `CloudflarePublishRuntime` per session.
  - [ ] No mutable state survives between sessions unless explicitly persisted
        and audited (install cache is the one documented exception — see
        [[Install Cloudflare binaries once per ABI and version]]).
  - [ ] Old singleton path removed.

  ## Links

  - [[Epic - Cloudflare publish hardening]]
  - [[Install Cloudflare binaries once per ABI and version]]
  - [[ripdpi-android-audit-2026-04-20]]

- [ ] #task Reject concurrent CloudflarePublishManager sessions #repo/RIPDPI #area/cloudflare-publish-hardening #status/backlog ⏫ [paperclip:POY-225]
  - Paperclip: POY-225 · assigned to: unassigned
  - Parent: POY-39 (Epic - Cloudflare publish hardening)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, bug, ripdpi, cloudflare, reentrancy
  - **source:** `TaskNotes/Tasks/Reject concurrent CloudflarePublishManager sessions.md`
  - **epic:** Epic - Cloudflare publish hardening

  ## Summary

  `CloudflarePublishManager.start()` does not clearly reject an already-running
  session — overlap / reentry is possible.

  ## Audit citation

  - `core/service/.../CloudflarePublishRuntime.kt:175-181,183-247`

  ## Acceptance criteria

  - [ ] `start()` returns a typed error (`AlreadyRunning`) when invoked on a
        running session.
  - [ ] State transitions are covered by a state machine or explicit guard.
  - [ ] Unit test exercises concurrent `start()` calls.

  ## Links

  - [[Epic - Cloudflare publish hardening]]
  - [[ripdpi-android-audit-2026-04-20]]


## composable-transport-layer-parity

- [ ] #task Add HTTPUpgrade transport crate #repo/RIPDPI #area/composable-transport-layer-parity #status/backlog 🔼 [paperclip:POY-78]
  - Paperclip: POY-78 · assigned to: unassigned
  - Parent: POY-40 (Epic - Composable transport layer parity)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, transport, httpupgrade
  - **source:** `TaskNotes/Tasks/Add HTTPUpgrade transport crate.md`
  - **epic:** Epic - Composable transport layer parity

  ## Summary

  Add `ripdpi-transport-httpupgrade` implementing the Xray/V2Fly
  `httpupgrade` transport: a minimal HTTP/1.1 Upgrade handshake followed
  by a raw bytestream. Used by subscriptions that want HTTP/1.1-looking
  traffic without the WebSocket framing overhead.

  ## Context

  HTTPUpgrade is a newer carrier in the sing-box ecosystem — simpler
  than WebSocket (no binary framing), cheaper than gRPC (no H2
  overhead). Upstream behavior: client sends an HTTP/1.1 `Upgrade:
  websocket` (or custom protocol name) with configurable path and
  headers; server responds `101 Switching Protocols`; the socket
  becomes raw bytes in both directions.

  ## Acceptance criteria

  - [ ] Crate exposes `HttpUpgradeTransport` with `AsyncRead +
        AsyncWrite` on a raw stream after the upgrade completes.
  - [ ] Request supports configurable path, host header, extra
        headers, upgrade protocol name.
  - [ ] Response validation rejects non-`101` codes with a typed
        error.
  - [ ] Composable over any inner stream (raw TCP, TLS, uTLS).
  - [ ] Wire format validated against a live Xray server fixture or
        upstream test bench.
  - [ ] Subscription parsers populate httpupgrade fields.

  ## Links

  - [[Epic - Composable transport layer parity]]

- [ ] #task Add gRPC transport crate with tonic and Xray-compatible framing #repo/RIPDPI #area/composable-transport-layer-parity #status/backlog ⏫ [paperclip:POY-120]
  - Paperclip: POY-120 · assigned to: unassigned
  - Parent: POY-40 (Epic - Composable transport layer parity)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, transport, grpc
  - **source:** `TaskNotes/Tasks/Add gRPC transport crate with tonic and Xray-compatible framing.md`
  - **epic:** Epic - Composable transport layer parity

  ## Summary

  Add `ripdpi-transport-grpc` implementing Xray/V2Fly-compatible gRPC as
  an outbound transport, using `tonic` for protobuf framing. Today the
  only `grpc` reference in the codebase is the string
  `"application/grpc"` in `ripdpi-xhttp`'s Content-Type header — not an
  actual gRPC implementation.

  ## Context

  Xray's gRPC transport uses a service named `proxy.v2ray.com.Service`
  (or `GunService` in some forks) with a single bidirectional-streaming
  method `Tun` carrying `Hunk` protobuf messages. Every frame is a
  length-prefixed protobuf on top of HTTP/2. The tricky bit is layering
  this under a uTLS-spoofed TLS rather than the default `rustls`
  connector `tonic` wants.

  ## Acceptance criteria

  - [ ] Crate exposes `GrpcTransport` with a composable `AsyncRead +
        AsyncWrite` surface.
  - [ ] Service name is configurable (Xray default, Gun-style fork,
        custom).
  - [ ] Protobuf framing uses `prost`; wire format validated against
        an Xray server fixture.
  - [ ] Composable over a uTLS-spoofed TLS connector from
        `ripdpi-tls-profiles` (key integration risk — see epic notes).
  - [ ] Per-stream multiplexing via HTTP/2 streams, not a single
        bidirectional stream per connection.
  - [ ] Health-check frames respected per Xray spec.
  - [ ] Subscription parsers (Clash / sing-box JSON) populate gRPC
        fields for applicable profiles.

  ## Links

  - [[Epic - Composable transport layer parity]]

- [ ] #task Add randomized port-hopping window to Hysteria2 outbound #repo/RIPDPI #area/composable-transport-layer-parity #status/backlog 🔼 [paperclip:POY-136]
  - Paperclip: POY-136 · assigned to: unassigned
  - Parent: POY-40 (Epic - Composable transport layer parity)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-25
  - **dateModified:** 2026-04-25
  - **area:** android
  - **tags:** task, feature, ripdpi, hysteria2, port-hopping, dpi-evasion
  - **source:** `TaskNotes/Tasks/Add randomized port-hopping window to Hysteria2 outbound.md`
  - **epic:** Epic - Composable transport layer parity

  ## Summary

  Hysteria 2.8.0 introduced `minHopInterval` / `maxHopInterval` so port-hopping intervals can be randomized within a configured window instead of using a fixed cadence. Adopt the same fields in RIPDPI's Hysteria2 outbound config so the per-session hopping interval becomes unpredictable to interval-based DPI traffic classifiers.

  ## Research citation

  [[ripdpi-android-research-2026-04-25]] §Upstream transport engines — Hysteria app/v2.8.0 (2026-03-30) added `minHopInterval` / `maxHopInterval` for randomized port-hopping (vs the previous fixed interval), reducing predictability for DPI traffic classifiers. Same release added selectable congestion control (3 BBR profiles + Reno) and server-side UDP port-range listening with auto nftables/iptables rule injection.

  ## Acceptance criteria

  - [ ] Outbound config schema gains `minHopInterval` / `maxHopInterval` fields per Hysteria 2.8.0
  - [ ] Runtime randomizes hop interval per session within the configured window
  - [ ] Telemetry surfaces actual interval distribution per session for verification
  - [ ] Backward compatibility: omitted fields fall back to existing fixed-interval behavior

  ## Links

  - Project: [[ripdpi-android]]
  - Epic: [[Epic - Composable transport layer parity]]
  - Research: [[ripdpi-android-research-2026-04-25]] §Upstream transport engines

- [ ] #task Add sing-mux and yamux wire multiplexing #repo/RIPDPI #area/composable-transport-layer-parity #status/backlog 🔼 [paperclip:POY-146]
  - Paperclip: POY-146 · assigned to: unassigned
  - Parent: POY-40 (Epic - Composable transport layer parity)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, transport, mux
  - **source:** `TaskNotes/Tasks/Add sing-mux and yamux wire multiplexing.md`
  - **epic:** Epic - Composable transport layer parity

  ## Summary

  Add `ripdpi-transport-mux` implementing the sing-mux (sing-box) and
  yamux (hashicorp) wire multiplexing protocols, so multiple logical
  streams can share a single outbound connection.

  ## Context

  The existing `ripdpi-relay-mux` crate is session-pooling, not wire-
  level multiplexing. NekoBox/sing-box subscriptions frequently request
  `mux: sing-mux` or `mux: yamux` on VLESS/VMess/Trojan outbounds to
  reduce connection-establishment overhead. `smux` (Trojan-Go only) is
  a separate protocol and is out of scope here; add if real Trojan-Go
  subscriptions demand it.

  ## Acceptance criteria

  - [ ] Crate implements the sing-mux wire format (frame header, stream
        ID allocation, keepalive); passes upstream test vectors.
  - [ ] Crate implements the yamux wire format; passes hashicorp test
        vectors (or a port of them).
  - [ ] Common `MuxTransport` trait lets outbounds plug either
        protocol.
  - [ ] Configurable limits: max concurrent streams, per-connection
        KB/s target, padding mode (for sing-mux).
  - [ ] Backpressure semantics documented; a slow reader on one stream
        does not wedge the whole mux.
  - [ ] Benchmark establishing 100 parallel flows: verify the mux
        beats 100-independent-connections on latency and memory; regress
        if it doesn't (and revisit default enable-state).
  - [ ] VLESS and Trojan outbound crates gain `mux` config fields and
        compose the transport.

  ## Links

  - [[Epic - Composable transport layer parity]]

- [ ] #task Generalize WebSocket transport for outbound composition #repo/RIPDPI #area/composable-transport-layer-parity #status/backlog ⏫ [paperclip:POY-195]
  - Paperclip: POY-195 · assigned to: unassigned
  - Parent: POY-40 (Epic - Composable transport layer parity)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, transport, websocket
  - **source:** `TaskNotes/Tasks/Generalize WebSocket transport for outbound composition.md`
  - **epic:** Epic - Composable transport layer parity

  ## Summary

  Extract a generic `ripdpi-transport-ws` from the existing Telegram-only
  `ripdpi-ws-tunnel` crate so any outbound (Trojan, VLESS, VMess) can
  layer on top of WebSocket-over-TLS.

  ## Context

  Today `ripdpi-ws-tunnel` is hard-coded for MTProto-over-WSS against
  `kws{n}.web.telegram.org`, uses sync `tungstenite`, and owns its own TLS
  layer. The generic transport needs `tokio-tungstenite`, configurable
  host/path/headers, and composition above any TLS layer (incl. the uTLS
  connector from `ripdpi-tls-profiles`).

  ## Acceptance criteria

  - [ ] New crate `ripdpi-transport-ws` exposes `WsTransport` with
        `AsyncRead + AsyncWrite` bytewise surface over a binary-framed
        WebSocket.
  - [ ] Accepts configurable: host, path, extra headers (for early-data
        headers used by some providers), subprotocol string.
  - [ ] Composable over any inner stream type; TLS is outside the crate.
  - [ ] `tokio-tungstenite` used instead of sync `tungstenite`.
  - [ ] Early-data encoding (ed=N) and `Sec-WebSocket-Protocol` early-
        data support for VMess/VLESS WS profiles.
  - [ ] Existing Telegram call site is migrated to consume the generic
        crate; no regression on Telegram path.
  - [ ] Trojan + VLESS + VMess outbound crates can compose WS via the
        new transport in smoke tests.

  ## Links

  - [[Epic - Composable transport layer parity]]

- [ ] #task Refactor QUIC and H3 into a composable transport crate #repo/RIPDPI #area/composable-transport-layer-parity #status/backlog 🔼 [paperclip:POY-224]
  - Paperclip: POY-224 · assigned to: unassigned
  - Parent: POY-40 (Epic - Composable transport layer parity)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, transport, quic, http3
  - **source:** `TaskNotes/Tasks/Refactor QUIC and H3 into a composable transport crate.md`
  - **epic:** Epic - Composable transport layer parity

  ## Summary

  Extract a `ripdpi-transport-quic` crate (and optional H3-specific
  facade) so VLESS, VMess, and future outbounds can run over QUIC or
  HTTP/3 directly — today QUIC/H3 is protocol-locked inside
  `ripdpi-hysteria2` and `ripdpi-masque`.

  ## Context

  Hysteria2 and MASQUE each pull `quinn` + `h3` + `h3-quinn` directly
  and use them for their specific protocol needs. VLESS-QUIC, VMess-
  QUIC, and generic H3 CONNECT are sing-box-supported outbound shapes
  that RIPDPI cannot serve because there's no composable QUIC layer.
  Refactor rather than duplicate: move the shared `quinn` setup into a
  common crate, keep the Hysteria2 and MASQUE protocol-specific logic
  on top.

  ## Acceptance criteria

  - [ ] `ripdpi-transport-quic` exposes `QuicTransport` (bi-directional
        stream) and `QuicDatagramTransport` (CONNECT-UDP / datagram)
        surfaces.
  - [ ] Shared `quinn` + `rustls` config factory in the crate;
        Hysteria2 and MASQUE consume it instead of building their own.
  - [ ] `ripdpi-hysteria2` and `ripdpi-masque` continue passing all
        existing tests after migration.
  - [ ] H3 facade (`H3Transport`) exposes a CONNECT-capable HTTP/3
        surface composable under VLESS / VMess / generic outbounds.
  - [ ] ALPN, SNI, and per-profile uTLS-style fingerprinting are
        configurable at the transport boundary.
  - [ ] VLESS outbound gains a `transport: quic` mode in its profile
        editor and wire-tests against an Xray VLESS-QUIC server.

  ## Links

  - [[Epic - Composable transport layer parity]]


## control-plane-hardening

- [ ] #task Recurring upstream watch for xray-core REALITY ECH XHTTP changes #repo/RIPDPI #area/control-plane-hardening #status/backlog 🔼 [paperclip:POY-223]
  - Paperclip: POY-223 · assigned to: unassigned
  - Parent: POY-41 (Epic - Control-plane hardening)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, chore, ripdpi, control-plane, upstream
  - **source:** `TaskNotes/Tasks/Recurring upstream watch for xray-core REALITY ECH XHTTP changes.md`
  - **epic:** Epic - Control-plane hardening

  ## Summary

  Document a recurring xray-core release-watch cadence and extend the
  host-pack validator to reject deprecated configurations (e.g., VLESS
  without flow) before they ship to clients.

  ## Research citation

  [[ripdpi-android-research-2026-04-20]] §Upstream transport engines —
  xray-core is on a fast release cadence (v1.260206.0 most recent; VLESS-
  without-flow deprecation + `allowInsecure` auto-disable at 2026-06-01 +
  XHTTP+REALITY breakage at v26.1.18). A silent breakage here sinks the
  control plane; catching it at host-pack publish time is cheapest.

  ## Acceptance criteria

  - [ ] Cadence and source list for xray-core release watch documented
        (release page, changelog, discussion tracker).
  - [ ] Host-pack validator rejects deprecated flow values and any known
        broken combinations pre-publish.
  - [ ] Owner and review interval (weekly or per-release) set in the
        chore body and linked from [[Epic - Control-plane hardening]].

  ## Links

  - [[Epic - Control-plane hardening]]
  - [[Sign host-pack manifests with app-trusted keys]]
  - [[Add anti-rollback to strategy-pack updates]]
  - [[ripdpi-android-research-2026-04-20]]


## direct-mode-diagnostic-state

- [ ] #task Add HTTP injection blockpage diagnostic probe #repo/RIPDPI #area/direct-mode-diagnostic-state #status/backlog 🔼 [paperclip:POY-77]
  - Paperclip: POY-77 · assigned to: unassigned
  - Parent: POY-42 (Epic - Direct-mode diagnostic state machine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-25
  - **dateModified:** 2026-04-25
  - **area:** android
  - **tags:** task, feature, ripdpi, diagnostics, blockpage, http
  - **source:** `TaskNotes/Tasks/Add HTTP injection blockpage diagnostic probe.md`
  - **epic:** Epic - Direct-mode diagnostic state machine

  ## Summary

  A targeted plain-HTTP probe that detects ISP-injected blockpages on
  cleartext HTTP responses, distinct from RIPDPI's existing TLS-side
  classification and the runtime blockpage fingerprinter.

  ## Motivation

  `ripdpi-failure-classifier` already includes blockpage fingerprinting
  on the runtime path, but the active diagnostics suite has no
  equivalent of dpi-detector's `check_http_injection`: an explicit
  probe that issues a plain `GET http://<domain>/` and compares the
  response against known blockpage shapes (transparent proxy headers,
  HTML markers, redirects to operator portals). This produces a
  positive "HTTP injection observed on this network" verdict for
  inclusion in the direct-mode classifier and the diagnostics summary.

  ## Scope

  - **In scope:** new HTTP injection probe in `ripdpi-monitor`,
    pluggable into the diagnostic catalog and the per-domain reachability
    card; reuses the existing fingerprint set in
    `ripdpi-failure-classifier` rather than maintaining a parallel one;
    feeds `DiagnosticResult` reasons (specifically a new
    `HTTP_INJECTION` evidence flag).
  - **Out of scope:** new fingerprint authoring (use the curated set
    already shipped); HTTPS-side blockpage detection (already exists);
    payload archival of injected pages.

  ## Acceptance criteria

  - [ ] Probe issues a single GET against plain HTTP for each target,
        bounded by the standard probe wall-clock budget.
  - [ ] Response is matched against `ripdpi-failure-classifier`
        fingerprints; verdict is one of `clean`, `injected:<operator>`,
        `redirect_to_portal`, `connection_reset_after_request`.
  - [ ] At least three operator-class fingerprints (transparent proxy
        header, RKN-style HTML marker, captive-style redirect) are
        covered by unit tests with golden response bodies.
  - [ ] `HTTP_INJECTION` evidence flag is propagated into the
        `DiagnosticResult` reason for `DNS_BLOCK` and `IP_BLOCK_SUSPECT`
        classes where applicable.
  - [ ] Probe is included in the export bundle's `report.json` as its
        own entry with the matched fingerprint id (not the response
        body) — never persist captured HTML in artifacts.

  ## Design notes

  Cleartext-HTTP probes are sensitive: do not run against arbitrary
  user-supplied domains in automatic profiles. Limit automatic runs to
  the curated diagnostic target pack; manual runs against user-supplied
  domains require an explicit confirmation in the UI. Hash responses for
  fingerprint matching; do not store full response bodies.

  ## Source reference

  dpi-detector v3.2.2: `core/tls_scanner.py` `check_http_injection`.
  RIPDPI parallel: `ripdpi-failure-classifier` blockpage fingerprint set.

  ## Risks / open questions

  - Cleartext probing may itself trigger ISP logging; document this in
    the diagnostics user manual and keep the probe gated.
  - Some operators inject only on specific Host headers; the probe must
    send a realistic UA + Host pair, not a synthetic test fixture.

  ## Links

  - [[ripdpi-android]]
  - [[Epic - Direct-mode diagnostic state machine]]

- [ ] #task Add integration tests per diagnostic result class #repo/RIPDPI #area/direct-mode-diagnostic-state #status/backlog 🔼 [paperclip:POY-122]
  - Paperclip: POY-122 · assigned to: unassigned
  - Parent: POY-42 (Epic - Direct-mode diagnostic state machine)
  - Blocked by: POY-129
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, testing
  - **source:** `TaskNotes/Tasks/Add integration tests per diagnostic result class.md`
  - **epic:** Epic - Direct-mode diagnostic state machine

  ## Summary

  Integration tests that drive the full diagnostic end-to-end in a
  controlled environment, one per `DiagnosticResult` variant and one per
  transport class. Uses the shared failure-injection harness.

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] Phases 1–4.

  ## Acceptance criteria

  - [ ] `TRANSPARENT_WORKS` scenarios: one per class (DNS_BLOCK,
        SNI_TLS_SUSPECT, QUIC_BLOCK_SUSPECT resolved via A3–A8).
  - [ ] `OWNED_STACK_ONLY` scenarios: IP_BLOCK_SUSPECT resolved only by
        A9/A10; transparent arms confirmed failing.
  - [ ] `NO_DIRECT_SOLUTION` scenario: all arms fail within budget.
  - [ ] Attempt budget enforced in every scenario (no test exceeds the
        configured caps).
  - [ ] Tests are deterministic via the harness's fake clock and scripted
        network.

  ## Links

  - [[Implement direct-mode diagnostic orchestrator Phases 1-4]]
  - [[Add orchestration failure-injection harness]]
  - [[Epic - Direct-mode diagnostic state machine]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]

- [ ] #task Define transparent vs owned-stack mode boundary #repo/RIPDPI #area/direct-mode-diagnostic-state #status/backlog ⏫ [paperclip:POY-183]
  - Paperclip: POY-183 · assigned to: unassigned
  - Parent: POY-42 (Epic - Direct-mode diagnostic state machine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, architecture
  - **source:** `TaskNotes/Tasks/Define transparent vs owned-stack mode boundary.md`
  - **epic:** Epic - Direct-mode diagnostic state machine

  ## Summary

  Make the two product modes sharply separate in code and docs. Transparent
  mode (TUN + `VpnService.protect`) handles arbitrary third-party traffic;
  owned-stack mode (browser + SDK) handles traffic we control. Invariants
  differ per mode — enforce them at the boundary.

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] "Foundational constraint:
  two product modes".

  ## Acceptance criteria

  - [ ] Module boundary enforced: transparent-mode code cannot link to ECH
        / Cronet-owned code and vice versa.
  - [ ] Shared types (DNS classification, `TransportPolicy`, `ArmStats`)
        live in a neutral module consumed by both.
  - [ ] Architecture doc in `Docs/` explains the split, the invariants per
        mode, and how the diagnostic chooses between them.
  - [ ] Invariant test: no transparent-mode arm can execute from an
        owned-stack code path by accident.

  ## Links

  - [[Epic - Direct-mode diagnostic state machine]]
  - [[Guard transparent mode against ClientHello byte mutation]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]


## direct-mode-transport-policy

- [ ] #task Cache transport policy per network and host tuple #repo/RIPDPI #area/direct-mode-transport-policy #status/backlog 🔼 [paperclip:POY-164]
  - Paperclip: POY-164 · assigned to: unassigned
  - Parent: POY-43 (Epic - Direct-mode transport policy and verdicts)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, cache
  - **source:** `TaskNotes/Tasks/Cache transport policy per network and host tuple.md`
  - **epic:** Epic - Direct-mode transport policy and verdicts

  ## Summary

  Persist `TransportPolicy` keyed by `(host, ip set, app family, network
  profile)`. Sibling to the TLS family cache. Share the atomic-write path
  and the Phase 5 revalidation rules.

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] §3 + "Phase 5 — Persistence
  and revalidation".

  ## Acceptance criteria

  - [ ] Cache keyed by the exact tuple.
  - [ ] Hit path skips the classification phase.
  - [ ] Shares the same invalidation rules as the family cache (ASN change,
        access-type change, 3 consecutive failures, 7-day TTL, HTTPS/SVCB
        TTL expiry, ECH capability change).
  - [ ] Write path uses `AtomicFile` (see [[Make cache snapshot writes atomic]]).

  ## Links

  - [[Cache winning family per network and host tuple]]
  - [[Persist direct-mode policy with revalidation]]
  - [[Make cache snapshot writes atomic]]
  - [[Epic - Direct-mode transport policy and verdicts]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]

- [ ] #task Define TransportPolicy struct and per-host state #repo/RIPDPI #area/direct-mode-transport-policy #status/backlog ⏫ [paperclip:POY-179]
  - Paperclip: POY-179 · assigned to: unassigned
  - Parent: POY-43 (Epic - Direct-mode transport policy and verdicts)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, transport
  - **source:** `TaskNotes/Tasks/Define TransportPolicy struct and per-host state.md`
  - **epic:** Epic - Direct-mode transport policy and verdicts

  ## Summary

  Introduce the `TransportPolicy` type the rest of the direct-mode system
  uses as its per-host source of truth.

  ```text
  TransportPolicy {
    quic_mode: ALLOW | SOFT_DISABLE | HARD_DISABLE
    preferred_stack: H3 | H2 | H1
    dns_mode: SYSTEM | DOH_PRIMARY | DOH_SECONDARY
    tcp_family: NONE | SEG_PRE_SNI | SEG_MID_SNI | REC_PRE_SNI | REC_MID_SNI
    outcome: TRANSPARENT_OK | OWNED_STACK_ONLY | NO_DIRECT_SOLUTION
  }
  ```

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] §3.

  ## Acceptance criteria

  - [ ] Type exists with the fields above; enums are sealed.
  - [ ] A default policy constructor used on first contact with an unknown
        host.
  - [ ] Serialization/deserialization is stable across app updates
        (versioned envelope).
  - [ ] Unit tests cover state transitions the rest of the engine drives.

  ## Links

  - [[Epic - Direct-mode transport policy and verdicts]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]

- [ ] #task Evaluate sing-box 1.14 rule-action model for policy DSL parity #repo/RIPDPI #area/direct-mode-transport-policy #status/backlog 🔽 [paperclip:POY-191]
  - Paperclip: POY-191 · assigned to: unassigned
  - Parent: POY-43 (Epic - Direct-mode transport policy and verdicts)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, spike, ripdpi, direct-mode, policy
  - **source:** `TaskNotes/Tasks/Evaluate sing-box 1.14 rule-action model for policy DSL parity.md`
  - **epic:** Epic - Direct-mode transport policy and verdicts

  ## Summary

  Summarize sing-box 1.14's rule-action model, then decide whether RIPDPI's
  direct-mode transport-policy DSL should align vocabulary with it or
  deliberately diverge.

  ## Research citation

  [[ripdpi-android-research-2026-04-20]] §Upstream transport engines —
  sing-box 1.14.0-alpha.13 (2026-04-17) replaces legacy
  inbound/outbound-special-field plumbing with a rule-action model that
  supports pre-matching. Aligning (or explicitly diverging with rationale)
  makes it cheaper to exchange strategy expressions with the peer
  community.

  ## Acceptance criteria

  - [ ] sing-box 1.14 rule-action vocabulary summarized (matchers, action
        types, pre-match semantics).
  - [ ] Alignment-vs-divergence decision recorded with rationale on
        [[Epic - Direct-mode transport policy and verdicts]].
  - [ ] If alignment chosen: migration sketch for existing
        `TransportPolicy` struct noted; no migration work performed in
        this spike.

  ## Links

  - [[Epic - Direct-mode transport policy and verdicts]]
  - [[Define TransportPolicy struct and per-host state]]
  - [[Cache transport policy per network and host tuple]]
  - [[ripdpi-android-research-2026-04-20]]

- [ ] #task Spike zapret QUIC desync taxonomy for direct-mode UDP arms #repo/RIPDPI #area/direct-mode-transport-policy #status/backlog ⏫ [paperclip:POY-244]
  - Paperclip: POY-244 · assigned to: unassigned
  - Parent: POY-43 (Epic - Direct-mode transport policy and verdicts)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, spike, ripdpi, direct-mode, quic, udp
  - **source:** `TaskNotes/Tasks/Spike zapret QUIC desync taxonomy for direct-mode UDP arms.md`
  - **epic:** Epic - Direct-mode transport policy and verdicts

  ## Summary

  Catalogue zapret's QUIC and UDP desync strategies by primitive, map each
  to a candidate direct-mode UDP arm, and recommend which arms the
  transport policy engine should add first.

  ## Research citation

  [[ripdpi-android-research-2026-04-20]] §Strategy-pack projects — zapret
  maintains the closest neighbor to our transparent-mode arm taxonomy, and
  its QUIC/UDP desync is load-bearing for HTTP/3 targets (YouTube).
  Cross-checking before inventing our own UDP arm taxonomy avoids
  duplicate work and gives a shared vocabulary with the peer community.

  ## Acceptance criteria

  - [ ] zapret QUIC/UDP desync strategies catalogued by primitive (fake
        packet, TTL game, header split, payload split, etc.).
  - [ ] Each primitive mapped to a candidate UDP arm or marked unmappable
        with a short reason.
  - [ ] Recommendation on which one or two arms to add first to the
        transport policy engine, with expected coverage gain.
  - [ ] Pointer to zapret source files or docs for each cited primitive.

  ## Links

  - [[Epic - Direct-mode transport policy and verdicts]]
  - [[Gate DoQ on UDP-clean classification]]
  - [[Implement QUIC soft-disable per tuple]]
  - [[ripdpi-android-research-2026-04-20]]


## dns

- [ ] #task Add DoH JSON API resolver path alongside RFC 8484 wire #repo/RIPDPI #area/dns #status/backlog 🔽 [paperclip:POY-75]
  - Paperclip: POY-75 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-25
  - **dateModified:** 2026-04-25
  - **area:** android
  - **tags:** task, feature, ripdpi, dns, doh
  - **source:** `TaskNotes/Tasks/Add DoH JSON API resolver path alongside RFC 8484 wire.md`

  ## Summary

  Add a DoH-JSON probe path (Google `/resolve`, Cloudflare DoH JSON,
  AdGuard `/resolve`, Alibaba `/resolve`) to the diagnostics suite,
  alongside the existing RFC 8484 DoH-wire path, so the JSON-only
  endpoints are exercised independently.

  ## Motivation

  dpi-detector probes both DoH formats because some resolver operators
  expose only one of the two paths, and some ISPs block the wire format
  (application/dns-message) while leaving the JSON API reachable, or
  vice versa. RIPDPI's resolver path uses DoH wire only; for diagnostic
  completeness — specifically when classifying which DoH endpoints the
  ISP filters — the JSON variant should be probed too. This is a
  diagnostics-only addition; the runtime resolver continues to use wire
  format.

  ## Scope

  - **In scope:** new probe variant in `ripdpi-monitor` that issues a
    DoH JSON GET (`?name=…&type=A`) and validates the JSON response.
    Surfaces as part of the resolver availability survey
    ([[Add public DNS resolver availability survey diagnostic]]) and the
    authority-scoped DNS classifier as an extra evidence source.
  - **Out of scope:** using DoH JSON in `ripdpi-dns-resolver` for actual
    resolution. The runtime path stays wire-only.

  ## Acceptance criteria

  - [ ] DoH JSON probe is a separate `ResolverProbe` variant with its
        own URL list, parser, and verdict.
  - [ ] Parser is permissive (handles Google's `Answer[].data` and
        Cloudflare's identical schema) but treats malformed JSON as a
        probe failure, not a panic.
  - [ ] No allocation in the hot path beyond what the JSON parser
        requires; reuse the `httpx`-equivalent client already in
        `ripdpi-monitor`.
  - [ ] Probe verdict is reported per-endpoint independently of the wire
        probe to the same operator (so "Google wire blocked, Google JSON
        reachable" is a representable outcome).
  - [ ] No fallback from wire to JSON in the runtime resolver; runtime
        stays wire-only.

  ## Source reference

  dpi-detector v3.2.2: `core/dns_scanner.py` `_probe_doh_json_single`,
  `_probe_doh_json_all`, and `config.yml` `DNS_DOH_SERVERS` for the JSON
  endpoint URL set.

  ## Risks / open questions

  - DoH JSON is non-standard (vendor-specific); scope must stay
    diagnostic-only to avoid baking dependence on a non-IETF interface
    into the runtime path.

  ## Links

  - [[ripdpi-android]]
  - [[Add public DNS resolver availability survey diagnostic]]
  - [[Epic - Encrypted DNS and HTTPS SVCB classifier]]

- [ ] #task Add public DNS resolver availability survey diagnostic #repo/RIPDPI #area/dns #status/backlog 🔼 [paperclip:POY-135]
  - Paperclip: POY-135 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-25
  - **dateModified:** 2026-04-25
  - **area:** android
  - **tags:** task, feature, ripdpi, diagnostics, dns
  - **source:** `TaskNotes/Tasks/Add public DNS resolver availability survey diagnostic.md`

  ## Summary

  A diagnostic that sweeps a curated panel of well-known public DNS
  resolvers and reports per-resolver reachability across UDP/53 and DoH,
  so the user can see at a glance which providers their ISP actually
  permits.

  ## Motivation

  RIPDPI's current resolver chain is fixed (AdGuard, DNS.SB, Google IP,
  Mullvad) and used as a fallback ladder rather than as a
  reachability survey. dpi-detector's Test 2 ("DNS server availability")
  sweeps a much wider panel (Google, Cloudflare, Quad9, AdGuard, Yandex,
  OpenDNS, ControlD, CleanBrowsing, NextDNS, Mullvad, Alibaba, DNS.SB,
  LibreDNS) on both UDP and DoH and reports per-resolver verdicts. That
  output is what informs the user-facing "which resolver should I pin?"
  recommendation, and feeds the resolver recommendation surface RIPDPI
  already exposes — but without the breadth.

  ## Scope

  - **In scope:** static curated panel of public resolvers (UDP/53 and
    DoH wire endpoints), parallel reachability probes with bounded
    concurrency, per-resolver latency + verdict, integration into the
    existing resolver-recommendation surface.
  - **Out of scope:** dynamic resolver discovery; trust scoring of
    resolver operators; rewriting the existing fallback chain in
    `ripdpi-dns-resolver`.

  ## Acceptance criteria

  - [ ] Resolver panel is a static list shipped in repo (extendable via
        strategy-pack); no runtime fetch from an external service (per
        "no backend" rule).
  - [ ] Per-resolver result: `udp_ok`, `doh_ok`, median latency for each,
        and one of `reachable` / `degraded` / `blocked`.
  - [ ] Bounded concurrency (≤8 in flight) with a hard wall-clock budget
        for the whole survey.
  - [ ] Survey results feed the existing resolver-recommendation
        surface so the recommendation set is the intersection of "panel
        reachable on this network" and "passes integrity classification".
  - [ ] Probe respects the `ipv4-only` setting when set.
  - [ ] Survey is gated behind an explicit user toggle and is not part
        of automatic probing/audit by default.

  ## Design notes

  The probe lives in `ripdpi-monitor`, parallel to existing DNS
  classification. Reuse `ripdpi-dns-resolver` query construction; do not
  inline a second DNS encoder. Verdict combines the UDP and DoH outcomes
  per resolver — both reachable = `reachable`, mixed = `degraded`,
  neither = `blocked`.

  ## Source reference

  dpi-detector v3.2.2: `config.yml` `DNS_AVAILABILITY_SERVERS` panel and
  `core/dns_scanner.py` `check_dns_availability`,
  `_probe_udp_all`, `_probe_doh_wire_all`.

  ## Risks / open questions

  - Some resolvers in the dpi-detector panel (Yandex, Mullvad) are
    themselves blocked or degraded on Russian networks; the verdict
    taxonomy must distinguish "this provider is censored" from "your
    network is censoring it" without making strong claims either way.

  ## Links

  - [[ripdpi-android]]
  - [[Epic - Encrypted DNS and HTTPS SVCB classifier]]


## encrypted-dns-and-https

- [ ] #task Limit DNS measurement to user-requested destinations #repo/RIPDPI #area/encrypted-dns-and-https #status/backlog 🔼 [paperclip:POY-212]
  - Paperclip: POY-212 · assigned to: unassigned
  - Parent: POY-44 (Epic - Encrypted DNS and HTTPS SVCB classifier)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, dns, privacy
  - **source:** `TaskNotes/Tasks/Limit DNS measurement to user-requested destinations.md`
  - **epic:** Epic - Encrypted DNS and HTTPS SVCB classifier

  ## Summary

  Measure DNS only for destinations the user is actually trying to reach.
  No preloaded target lists, no broad scanning. Matches the C-Saw
  measurement-with-consent posture.

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] §2 final operational note.

  ## Acceptance criteria

  - [ ] No code path exists that scans a preloaded domain list.
  - [ ] Measurement is always tied to a live flow request.
  - [ ] If measurement results are uploaded later (see shared priors), they
        carry only coarse keys — no raw user URLs, no SSIDs, no precise
        geolocation.
  - [ ] Review documented so future contributors don't accidentally add
        background probing.

  ## Links

  - [[Epic - Encrypted DNS and HTTPS SVCB classifier]]
  - [[Opt-in shared priors with coarse keys only]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]


## extended-outbound-protocol-support

- [ ] #task Add AnyTLS outbound client crate and profile editor #repo/RIPDPI #area/extended-outbound-protocol-support #status/backlog 🔼 [paperclip:POY-68]
  - Paperclip: POY-68 · assigned to: unassigned
  - Parent: POY-45 (Epic - Extended outbound protocol support)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, protocol, anytls
  - **source:** `TaskNotes/Tasks/Add AnyTLS outbound client crate and profile editor.md`
  - **epic:** Epic - Extended outbound protocol support

  ## Summary

  Add a `ripdpi-anytls` Rust crate implementing the AnyTLS client and a
  `AnyTLSProfileScreen` editor. AnyTLS is the newer sing-anytls protocol
  designed to reduce TLS-in-TLS detection vs ShadowTLS.

  ## Context

  Upstream reference: `anytls/sing-anytls`. The protocol coexists with
  ShadowTLS on RIPDPI's roadmap because subscription providers are split
  between the two. Reuse the existing ShadowTLS TLS session machinery where
  shape overlaps.

  ## Acceptance criteria

  - [ ] `ripdpi-anytls` crate passes upstream reference handshake and
        session-framing test vectors.
  - [ ] Fallback-SNI and fallback-server behavior matches upstream spec.
  - [ ] `AnyTLSProfileScreen` validates password length, server + port,
        and server-name (SNI).
  - [ ] Integrate with relay supervisor lifecycle; shutdown joins bounded
        handler work.
  - [ ] Strategy-pack metadata advertises AnyTLS compat hints, especially
        around QUIC-heavy neighborhoods.
  - [ ] Password is redacted in all diagnostic surfaces.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/moe/matsuri/nb4a/proxy/anytls/AnyTLSBean.java` — bean fields: `password`, `sni`, `alpn`, `allowInsecure`, `idleSessionCheckInterval`, `idleSessionTimeout`, `minIdleSession`.
  - `app/src/main/java/moe/matsuri/nb4a/proxy/anytls/AnyTLSFmt.kt` — `anytls://` URI codec.
  - `app/src/main/java/moe/matsuri/nb4a/proxy/anytls/AnyTLSSettingsActivity.kt` — editor.

  **Outbound engine (NOT from NekoBox):** upstream [`anytls/sing-anytls`](https://github.com/anytls/sing-anytls) (Go). No Rust port; either port to Rust or consume the spec directly. The handshake is small — HMAC-SHA1-based with session-ID camouflage.

  **Adapt:** Bean fields, URI codec, idle-session fields (AnyTLS-specific). **Skip:** sing-box integration layer from NekoBox.

  ## Links

  - [[Epic - Extended outbound protocol support]]

- [ ] #task Add HTTP and SOCKS5 outbound proxy clients #repo/RIPDPI #area/extended-outbound-protocol-support #status/backlog ⏫ [paperclip:POY-76]
  - Paperclip: POY-76 · assigned to: unassigned
  - Parent: POY-45 (Epic - Extended outbound protocol support)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, protocol, http, socks
  - **source:** `TaskNotes/Tasks/Add HTTP and SOCKS5 outbound proxy clients.md`
  - **epic:** Epic - Extended outbound protocol support

  ## Summary

  Add generic HTTP CONNECT and SOCKS5 outbound client adapters so profiles
  whose upstream is a commodity HTTP or SOCKS5 proxy can be used.

  ## Context

  Many subscription providers include nodes that are "just an HTTP proxy"
  or "just a SOCKS5 proxy" over TLS; without these adapters, the corres-
  ponding profile types in Clash/sing-box subscriptions cannot connect.
  RIPDPI has SOCKS5 as a local inbound, but not as an outbound adapter
  consumable by the relay dispatch. SOCKS4/4a are deliberately excluded
  as legacy; add only if a real subscription sample requires them.

  ## Acceptance criteria

  - [ ] `ripdpi-http-proxy` adapter in `ripdpi-relay-core` (or a dedicated
        crate) speaks HTTP CONNECT; supports optional Basic auth and TLS
        on the upstream connection (HTTPS proxies).
  - [ ] `ripdpi-socks5-client` adapter supports username/password auth
        plus unauthenticated mode; UDP ASSOCIATE is out of scope for v1.
  - [ ] Both adapters plug into the existing outbound dispatch; no
        parallel supervisor.
  - [ ] Profile editors for each: server + port, auth fields, TLS toggle
        for HTTP, SNI override for HTTPS proxies.
  - [ ] Clash YAML, sing-box JSON, and URI-list subscription parsers
        route `http`, `https`, `socks5`, `socks5-tls` node types to
        these adapters.
  - [ ] Credentials are redacted in all diagnostic surfaces.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/fmt/http/HttpBean.java` — bean fields: `username`, `password`, `tls`, `sni`, `allowInsecure`.
  - `app/src/main/java/io/nekohasekai/sagernet/fmt/http/HttpFmt.kt` — `http://` / `https://` URI parse. Port.
  - `app/src/main/java/io/nekohasekai/sagernet/fmt/socks/SOCKSBean.java` — fields: `protocol` (`PROTOCOL_SOCKS5` / `PROTOCOL_SOCKS4` / `PROTOCOL_SOCKS4A`), `username`, `password`, `tls`, `sni`.
  - `app/src/main/java/io/nekohasekai/sagernet/fmt/socks/SOCKSFmt.kt` — URI codec for the four SOCKS variants.

  **Outbound engine (NOT from NekoBox):** build as thin Rust adapters (`hyper` for HTTP CONNECT, `tokio-socks` or hand-rolled for SOCKS5). Total Rust: ~300 lines combined.

  **Adapt:** Bean field set (drop SOCKS4/4a per task scope), URI codec for `http`/`https`/`socks5`. **Skip:** NekoBox's sing-box delegation; SOCKS4/4a variants.

  ## Links

  - [[Epic - Extended outbound protocol support]]
  - [[Epic - NekoBox subscription and profile import]]

- [ ] #task Add Hysteria v1 outbound client crate and profile editor #repo/RIPDPI #area/extended-outbound-protocol-support #status/backlog 🔽 [paperclip:POY-79]
  - Paperclip: POY-79 · assigned to: unassigned
  - Parent: POY-45 (Epic - Extended outbound protocol support)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, protocol, hysteria, legacy
  - **source:** `TaskNotes/Tasks/Add Hysteria v1 outbound client crate and profile editor.md`
  - **epic:** Epic - Extended outbound protocol support

  ## Summary

  Add a `ripdpi-hysteria-v1` Rust crate (distinct from the existing
  `ripdpi-hysteria2`) for legacy Hysteria v1 subscriptions, plus a
  `HysteriaV1ProfileScreen` editor. Mark the crate with an explicit sunset
  decision date.

  ## Context

  Hysteria v1 is being replaced by v2 in the upstream ecosystem but remains
  present in older subscriptions. v1 protocol framing, auth, and congestion
  control differ enough that forcing them into `ripdpi-hysteria2` would
  regress that crate's simplicity. Ship as a thin, clearly-deprecated
  crate rather than hacking v1 into v2.

  ## Acceptance criteria

  - [ ] `ripdpi-hysteria-v1` crate compiles and passes v1 reference test
        vectors.
  - [ ] Crate has a top-of-file comment stating the sunset target (date
        to be decided during implementation but committed to repo).
  - [ ] `HysteriaV1ProfileScreen` prominently marks the profile as legacy
        and suggests Hysteria2 migration.
  - [ ] Subscription import still routes v1 entries to this crate without
        user intervention.
  - [ ] Shutdown joins bounded handler work; no background QUIC sockets
        leak.
  - [ ] Auth token is redacted in all diagnostic surfaces.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/fmt/hysteria/HysteriaBean.java` — shared v1+v2 bean; `protocolVersion` field distinguishes (`1` or `2`). v1-only fields: `protocol` (`udp`/`wechat-video`/`faketcp`), `authPayloadType` (string/base64), `authPayload`, `obfuscation`, `uploadMbps`, `downloadMbps`.
  - `app/src/main/java/io/nekohasekai/sagernet/fmt/hysteria/HysteriaFmt.kt` — `hysteria://` URI codec (v1). v2 is `hysteria2://` / `hy2://`.
  - `app/src/main/java/io/nekohasekai/sagernet/ui/profile/HysteriaSettingsActivity.kt` — editor handles both versions.

  **Outbound engine (NOT from NekoBox):** RIPDPI already ships `ripdpi-hysteria2` (v2). For v1, upstream [`HyNetwork/hysteria`](https://github.com/HyNetwork/hysteria) is Go. Hysteria v1 uses a custom framing over QUIC incompatible with v2; a separate Rust crate is needed. NekoBox launches Hysteria v1 as an external process via `hysteria-plugin`.

  **Adapt:** Bean fields (v1 subset), URI codec, bandwidth fields (v1 requires them, v2 derives them). **Skip:** NekoBox's external-process plugin path. **Sunset:** commit an explicit removal date in the crate header.

  ## Links

  - [[Epic - Extended outbound protocol support]]

- [ ] #task Add Mieru outbound client crate and profile editor #repo/RIPDPI #area/extended-outbound-protocol-support #status/backlog 🔼 [paperclip:POY-80]
  - Paperclip: POY-80 · assigned to: unassigned
  - Parent: POY-45 (Epic - Extended outbound protocol support)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, protocol, mieru
  - **source:** `TaskNotes/Tasks/Add Mieru outbound client crate and profile editor.md`
  - **epic:** Epic - Extended outbound protocol support

  ## Summary

  Add a `ripdpi-mieru` Rust crate implementing the Mieru outbound client
  and a `MieruProfileScreen` editor. Mieru (enfein/mieru) is actively
  developed and used in the Chinese bypass community; ignoring it blocks
  that user cohort.

  ## Context

  Mieru uses a custom UDP-based protocol with replay resistance; the
  Go reference implementation is the canonical spec. Upstream tests are
  the reference for protocol-level correctness. TCP transport mode is
  also supported upstream; both should land.

  ## Acceptance criteria

  - [ ] `ripdpi-mieru` crate passes upstream reference handshake +
        session-framing test vectors.
  - [ ] UDP and TCP transport modes both supported.
  - [ ] Multiplexing behavior matches upstream.
  - [ ] `MieruProfileScreen` validates server + port, username, password,
        protocol mode (TCP/UDP), mTU.
  - [ ] Mieru's time-based replay protection is clock-synced via the
        existing network-time source, not `System.currentTimeMillis`.
  - [ ] Credentials redacted in all diagnostic surfaces.
  - [ ] Subscription import path recognizes `mieru://` URIs.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/fmt/mieru/MieruBean.java` — bean fields: `username`, `password`, `mtu`, `protocol` (TCP/UDP), `multiplexing` (OFF/LOW/MIDDLE/HIGH).
  - `app/src/main/java/io/nekohasekai/sagernet/ui/profile/MieruSettingsActivity.kt` — editor.
  - NekoBox has no `mieru://` URI codec (editor + plugin-config-only); **RIPDPI should invent one** since subscription import is a stated goal.

  **Outbound engine (NOT from NekoBox):** upstream [`enfein/mieru`](https://github.com/enfein/mieru) (Go). NekoBox shells out to the `mieru-plugin` APK; RIPDPI needs a pure-Rust port or vendored build. The protocol is custom UDP-based with replay protection — non-trivial port effort.

  **Adapt:** Bean fields, multiplexing level mapping. **Invent:** `mieru://` URI scheme (e.g. `mieru://username:password@host:port?protocol=tcp&mux=middle`). **Skip:** NekoBox's external-process plugin path.

  ## Links

  - [[Epic - Extended outbound protocol support]]

- [ ] #task Add SSH outbound client crate and profile editor #repo/RIPDPI #area/extended-outbound-protocol-support #status/backlog 🔼 [paperclip:POY-90]
  - Paperclip: POY-90 · assigned to: unassigned
  - Parent: POY-45 (Epic - Extended outbound protocol support)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, protocol, ssh
  - **source:** `TaskNotes/Tasks/Add SSH outbound client crate and profile editor.md`
  - **epic:** Epic - Extended outbound protocol support

  ## Summary

  Add a `ripdpi-ssh` Rust crate that opens direct-tcpip forwarding via SSH
  (password or private-key auth), plus a `SshProfileScreen` editor.

  ## Context

  SSH tunnels are a common hobbyist bypass primitive, especially for users
  who control their own VPS. Use `russh` (or equivalent maintained crate)
  rather than re-implementing the wire protocol. Multiplexing is optional
  for v1; single-channel per connection is acceptable, though connection
  pooling should be left as an extension point.

  ## Acceptance criteria

  - [ ] `ripdpi-ssh` crate compiles with a maintained SSH crate
        dependency (evaluate `russh`, `thrussh` successors).
  - [ ] Password and OpenSSH private-key auth both supported.
  - [ ] Host-key verification is on by default; "trust on first use"
        is a per-profile opt-in.
  - [ ] `direct-tcpip` forwarding to arbitrary target host:port works
        for TCP; UDP is out of scope for v1.
  - [ ] `SshProfileScreen` validates host, port, user, and auth selection.
        Private key is stored via `EncryptedFile`; never SharedPreferences.
  - [ ] Host key fingerprint is surfaced on first connect with explicit
        accept / reject action.
  - [ ] Passphrase and private-key material are redacted in all
        diagnostic surfaces.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/fmt/ssh/SSHBean.java` — bean fields: `authType` (password/privateKey), `username`, `password`, `privateKey`, `privateKeyPassphrase`, `publicKey` (host key fingerprint).
  - `app/src/main/java/io/nekohasekai/sagernet/ui/profile/SSHSettingsActivity.kt` — editor layout including the trust-on-first-use host-key flow.
  - No `ssh://` URI codec in NekoBox (SSH profiles are editor-only); RIPDPI follows the same pattern.

  **Outbound engine (NOT from NekoBox):** use [`russh`](https://github.com/Eugeny/russh) (maintained pure-Rust SSH client). NekoBox's SSH outbound is sing-box's Go implementation.

  **Adapt:** Bean fields, host-key-TOFU UX pattern, passphrase reveal via biometric gate (same pattern RIPDPI uses for WireGuard private keys). **Skip:** No URI codec (consistent with NekoBox); subscription import for SSH is editor-only.

  ## Links

  - [[Epic - Extended outbound protocol support]]

- [ ] #task Add Shadowsocks outbound client crate and profile editor #repo/RIPDPI #area/extended-outbound-protocol-support #status/backlog 🔺 [paperclip:POY-91]
  - Paperclip: POY-91 · assigned to: unassigned
  - Parent: POY-45 (Epic - Extended outbound protocol support)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, protocol, shadowsocks
  - **source:** `TaskNotes/Tasks/Add Shadowsocks outbound client crate and profile editor.md`
  - **epic:** Epic - Extended outbound protocol support

  ## Summary

  Add a `ripdpi-shadowsocks` Rust crate implementing the full Shadowsocks
  outbound client (AEAD-2022 + legacy AEAD ciphers) and a
  `ShadowsocksProfileScreen` editor. Today RIPDPI only ships SS request-
  parsing as an inbound framing format; there is no outbound client.

  ## Context

  Shadowsocks is the most common protocol across third-party bypass
  subscriptions in every target region. Without an outbound client, SS
  entries in imported subscriptions cannot connect. Use AEAD-2022 ciphers
  first (`2022-blake3-aes-256-gcm`, `2022-blake3-chacha20-poly1305`) and
  the legacy AEAD family (`aes-256-gcm`, `chacha20-ietf-poly1305`) for
  subscription compat. `simple-obfs` and `v2ray-plugin` are out of scope
  for v1; they are plugin layers and belong to a later task.

  ## Acceptance criteria

  - [ ] `ripdpi-shadowsocks` crate compiles standalone and inside the
        android-jni workspace.
  - [ ] AEAD-2022 ciphers pass upstream test vectors (Shadowsocks-rust
        parity suite).
  - [ ] Legacy AEAD ciphers (`aes-128-gcm`, `aes-256-gcm`,
        `chacha20-ietf-poly1305`) pass upstream test vectors.
  - [ ] Stream ciphers (`rc4`, `aes-cfb`, `chacha20`, `salsa20`, etc.)
        are rejected with a typed error; never silently downgraded.
  - [ ] TCP and UDP modes both supported.
  - [ ] `ShadowsocksProfileScreen` validates server + port, password
        length, cipher picker with only supported ciphers.
  - [ ] Password is stored via EncryptedFile; never plaintext in
        preferences, never surfaced in logs or exports.
  - [ ] Subscription import path (Clash YAML + base64 URI list) routes
        SS entries to this crate.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/fmt/shadowsocks/ShadowsocksBean.java` — bean fields: `method`, `password`, `plugin`, `pluginOptions`. Port field set for RIPDPI's `ShadowsocksBean`.
  - `app/src/main/java/io/nekohasekai/sagernet/fmt/shadowsocks/ShadowsocksFmt.kt` — `ss://` URI parse (SIP002 format with base64 userinfo, plus legacy base64-whole-URI) and emit. **Port verbatim.**
  - `app/src/main/java/io/nekohasekai/sagernet/ui/profile/ShadowsocksSettingsActivity.kt` — the editor's validation rules for method/password/plugin. Reference only; RIPDPI editor will be Compose, not PreferenceFragment.

  **Outbound engine (NOT from NekoBox):**
  - [`shadowsocks-rust`](https://github.com/shadowsocks/shadowsocks-rust) — pure-Rust reference implementation. Shadowsocks-rust's `shadowsocks-crypto` crate has the AEAD-2022 and legacy AEAD ciphers. Consume as a dependency or vendored fork.
  - NekoBox's outbound is sing-box's Go implementation; **do not port that**.

  **Adapt:** Bean fields, URI codec, validation rules. **Skip:** sing-box Go outbound, any "plugin" external-process path (simple-obfs / v2ray-plugin are out of scope for v1).

  ## Links

  - [[Epic - Extended outbound protocol support]]
  - [[Epic - NekoBox subscription and profile import]]

- [ ] #task Add Trojan outbound client crate and profile editor #repo/RIPDPI #area/extended-outbound-protocol-support #status/backlog ⏫ [paperclip:POY-93]
  - Paperclip: POY-93 · assigned to: unassigned
  - Parent: POY-45 (Epic - Extended outbound protocol support)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, protocol, trojan
  - **source:** `TaskNotes/Tasks/Add Trojan outbound client crate and profile editor.md`
  - **epic:** Epic - Extended outbound protocol support

  ## Summary

  Add a `ripdpi-trojan` Rust crate implementing the Trojan client over TLS
  plus a `TrojanProfileScreen` editor. Trojan is still common in real-world
  bypass subscriptions that have not migrated to VLESS-Reality.

  ## Context

  Wire format is straightforward (SHA-224(password) + command + target).
  TLS transport reuses the existing transport crate. Keep the client narrowly
  focused: Trojan only, no Trojan-Go extensions — those belong to a separate
  crate if ever added.

  ## Acceptance criteria

  - [ ] `ripdpi-trojan` crate passes upstream reference test vectors for
        handshake and target framing.
  - [ ] TCP and UDP ASSOCIATE modes both supported.
  - [ ] TLS layer allows pluggable SNI, ALPN, and certificate verification
        toggle (insecure mode behind a debug-only flag).
  - [ ] `TrojanProfileScreen` validates SNI hostname, password length, ALPN
        list.
  - [ ] WebSocket and gRPC transports over TLS are supported (reuse
        existing transports).
  - [ ] Password is SHA-224 hashed in-memory; plaintext never written to
        disk. Redacted in all diagnostic surfaces.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/fmt/trojan/TrojanBean.java` — bean fields: `password`, `sni`, `alpn`, plus transport (reuses StandardV2RayBean pattern).
  - `app/src/main/java/io/nekohasekai/sagernet/fmt/trojan/TrojanFmt.kt` — `trojan://` URI parse + emit.
  - `app/src/main/java/io/nekohasekai/sagernet/ui/profile/TrojanSettingsActivity.kt` — editor validation rules.

  **Outbound engine (NOT from NekoBox):** Trojan wire format is simple (SHA-224(password) + command byte + target address + CRLF). Hand-roll in Rust; ~100 lines core handshake + reuse RIPDPI's existing TLS + transport layers.

  **Adapt:** Bean fields, URI codec, SNI/ALPN validation. **Skip:** Trojan-Go extensions (separate crate, [[Add Trojan-Go outbound client crate and profile editor]]).

  ## Links

  - [[Epic - Extended outbound protocol support]]

- [ ] #task Add Trojan-Go outbound client crate and profile editor #repo/RIPDPI #area/extended-outbound-protocol-support #status/backlog 🔽 [paperclip:POY-94]
  - Paperclip: POY-94 · assigned to: unassigned
  - Parent: POY-45 (Epic - Extended outbound protocol support)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, protocol, trojan-go, legacy
  - **source:** `TaskNotes/Tasks/Add Trojan-Go outbound client crate and profile editor.md`
  - **epic:** Epic - Extended outbound protocol support

  ## Summary

  Add a `ripdpi-trojan-go` Rust crate for Trojan-Go subscriptions. Trojan-Go
  extends Trojan with WebSocket / mux / plugin framing; it is declining in
  usage but still present in some subscription mixes.

  ## Context

  Trojan-Go and Trojan share the password-hash handshake but differ in
  transport framing. Keeping them as separate crates avoids mixing two
  protocols in one and lets Trojan-Go be sunset independently later.
  Plugin framing (simple-obfs plugin) is NOT part of v1; only the
  WebSocket-over-TLS + mux transport is.

  ## Acceptance criteria

  - [ ] `ripdpi-trojan-go` crate compiles and passes upstream v0.x test
        vectors for handshake and WebSocket framing.
  - [ ] Mux support: SMUX v1 mode.
  - [ ] Shadowsocks-AEAD inner encryption option supported.
  - [ ] `TrojanGoProfileScreen` validates SNI, password, WS path,
        optional SS cipher for inner encryption.
  - [ ] Profile is flagged as legacy in UI lists.
  - [ ] Sunset date committed in the crate top-of-file comment.
  - [ ] Password redacted in all diagnostic surfaces.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/fmt/trojan_go/TrojanGoBean.java` — full extended field set: `encryption` (shadowsocks-AEAD inner cipher), `plugin`, `pluginOpts`, `path`, `host`, `type` (ws/h2), `mux`.
  - `app/src/main/java/io/nekohasekai/sagernet/fmt/trojan_go/TrojanGoFmt.kt` — `trojan-go://` URI codec.
  - `app/src/main/java/io/nekohasekai/sagernet/ui/profile/TrojanGoSettingsActivity.kt` — editor.

  **Outbound engine (NOT from NekoBox):** upstream [`p4gefau1t/trojan-go`](https://github.com/p4gefau1t/trojan-go) (Go, archived) is the spec reference. No Rust port exists; write one or accept the crate stays legacy-only and is removed per the sunset commitment in the epic.

  **Adapt:** Bean fields, URI codec. **Skip:** NekoBox's external-process path via `trojan-go-plugin` APK (RIPDPI architecture is Rust-only, no external binaries via plugin ecosystem).

  ## Links

  - [[Epic - Extended outbound protocol support]]
  - [[Add Trojan outbound client crate and profile editor]]

- [ ] #task Add VMess outbound client crate and profile editor #repo/RIPDPI #area/extended-outbound-protocol-support #status/backlog 🔼 [paperclip:POY-95]
  - Paperclip: POY-95 · assigned to: unassigned
  - Parent: POY-45 (Epic - Extended outbound protocol support)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, protocol, vmess
  - **source:** `TaskNotes/Tasks/Add VMess outbound client crate and profile editor.md`
  - **epic:** Epic - Extended outbound protocol support

  ## Summary

  Add a `ripdpi-vmess` Rust crate implementing VMess client outbound (AEAD
  only, legacy security variants rejected with typed error) plus a
  `VMessProfileScreen` editor.

  ## Context

  VMess is legacy but widely present in older subscriptions. Supporting the
  AEAD variant is sufficient for realistic traffic. Legacy `security: auto`
  and MD5-based auth are explicitly unsupported. VMess transports (tcp, ws,
  h2, grpc) reuse existing transport crates where possible.

  ## Acceptance criteria

  - [ ] `ripdpi-vmess` crate compiles standalone and as part of the
        android-jni workspace.
  - [ ] AEAD (`aes-128-gcm`, `chacha20-poly1305`) ciphers pass reference
        test vectors.
  - [ ] Legacy security values (`auto`, `none`, `md5`) are rejected with
        typed error messages surfaced to the user.
  - [ ] Transport matrix: tcp, ws, h2, grpc — all supported via shared
        transport layer.
  - [ ] Profile editor enforces schema validation: UUID v4, port range,
        alterId=0 only, security whitelist.
  - [ ] Profile is flagged "legacy" in lists so new users know it is not
        the recommended path.
  - [ ] Secrets (UUID) are redacted in all logs, diagnostics, and exports.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/fmt/v2ray/VMessBean.java` (+ sibling `VLESSBean.java` via `alterId=-1`) — full field set: `uuid`, `alterId`, `encryption`, `security`, `packetEncoding`, `experiments`, plus transport fields (`type`, `host`, `path`, `headerType`, `mKcpSeed`, `quicSecurity`, `quicKey`, `grpcMode`, `grpcServiceName`, `wsMaxEarlyData`, `earlyDataHeaderName`, `reality*` etc.).
  - `app/src/main/java/io/nekohasekai/sagernet/fmt/v2ray/V2RayFmt.kt` — `vmess://` parse (both base64-JSON and standard URI form) + emit. **Port verbatim.**
  - `app/src/main/java/io/nekohasekai/sagernet/ui/profile/VMessSettingsActivity.kt` — validation rules.

  **Outbound engine (NOT from NekoBox):** no mature pure-Rust VMess client exists; most likely path is to port the wire-format pieces from `xray-core` (Go) or `v2fly-core`. Evaluate [`v2ray-rust`](https://github.com/Qv2ray/v2ray-rust) as a starting point; it's unmaintained but has AEAD implementations.

  **Adapt:** Bean fields, URI codec (both forms), legacy-cipher rejection policy. **Skip:** AlterID != 0 (deprecated); `aid` handling for legacy security variants; the NekoBox editor UI (build Compose instead).

  ## Links

  - [[Epic - Extended outbound protocol support]]


## fail-closed-android-vpn

- [ ] #task Add Android Private DNS conflict warning #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog 🔼 [paperclip:POY-65]
  - Paperclip: POY-65 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, dns, android
  - **source:** `TaskNotes/Tasks/Add Android Private DNS conflict warning.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Detect and explain Android Private DNS conflicts without treating system Private DNS as RIPDPI's resolver policy.

  ## Motivation

  Users may configure Android Private DNS and assume it protects VPN DNS. RIPDPI owns DNS inside the VPN and should warn about confusing states instead of relying on system Private DNS behavior.

  ## Scope

  - In scope: settings/read-only detection where public APIs allow it, UX warning, diagnostics field, and test coverage for the policy decision.
  - Out of scope: modifying the user's Private DNS setting.

  ## Acceptance criteria

  - [ ] DNS settings screen explains that RIPDPI uses its own VPN DNS interceptor.
  - [ ] Diagnostics can report `system_private_dns_present`, `ignored_for_vpn_policy`, or `unknown`.
  - [ ] App does not route VPN DNS through system Private DNS as a policy source.
  - [ ] Warning appears only when it helps explain a resolver mismatch or user confusion.

  ## Design notes

  Keep this educational and diagnostic. It should not block secure VPN startup by itself.

  ## Risks / open questions

  - Android version and OEM differences may limit reliable detection.

  ## Links

  - [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
  - [[Add DNS interceptor and split DNS leak tests]]

- [ ] #task Add Android VPN leak-test instrumentation matrix #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog ⏫ [paperclip:POY-66]
  - Paperclip: POY-66 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, ripdpi, testing, vpn, leak-test
  - **source:** `TaskNotes/Tasks/Add Android VPN leak-test instrumentation matrix.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Create an Android VPN leak-test matrix that exercises DNS, IPv6, kill-switch, network transition, revoke, per-app, and credential-revocation behavior across supported API levels.

  ## Context

  The policy-first client is only credible if the failure modes are reproducible. This task collects the cross-cutting instrumentation and acceptance matrix rather than leaving each feature to test only its happy path.

  ## Acceptance criteria

  - [ ] DNS leak test proves proxied domains do not use ISP/default-network DNS.
  - [ ] IPv6 leak test proves IPv4-only mode does not expose direct IPv6 on IPv6-capable networks.
  - [ ] Core-crash and service-stop tests prove traffic is blocked or the VPN state is revoked, not silently direct.
  - [ ] Wi-Fi to LTE, LTE to Wi-Fi, sleep/wake, and captive portal transitions are covered.
  - [ ] `onRevoke()` test verifies sockets, TUN fd, and provider runtimes close.
  - [ ] Per-app allow/disallow tests cover reconnect requirement and lockdown interactions.
  - [ ] Revoked credential fixtures prove stale UUID/shortId/password/profile tokens no longer work in local validation paths.

  ## Notes

  Start with emulator and fake-network harness coverage, then add real-device smoke cases for API 26, 29, 30, 33, 34, 35, and current preview when available.

  ## Links

  - [[Epic - Fail-closed Android VPN policy engine]]
  - [[Epic - Orchestration test posture]]
  - [[Add Xray VPN client regression matrix]]

- [ ] #task Add Android lockdown onboarding and kill-switch health checks #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog 🔺 [paperclip:POY-67]
  - Paperclip: POY-67 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, vpn, kill-switch, onboarding
  - **source:** `TaskNotes/Tasks/Add Android lockdown onboarding and kill-switch health checks.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Add onboarding and runtime health UI that guides users to Android Always-on VPN plus Block connections without VPN and clearly reports whether lockdown is active, missing, or unknown.

  ## Motivation

  App-level reconnect is not a hard kill switch. Android lockdown is user or device-admin controlled, so RIPDPI must make the system requirement visible instead of implying the client can enforce it alone.

  ## Scope

  - In scope: onboarding checklist, Settings deep links, runtime kill-switch status, blocked/reconnecting state copy, and health checks after network transitions.
  - Out of scope: silently enabling lockdown for the user or claiming hard protection when the OS setting is not enabled.

  ## Acceptance criteria

  - [ ] Onboarding distinguishes VPN permission, Always-on VPN, Block connections without VPN, battery optimization, and foreground-service health.
  - [ ] Connection screen shows `System lockdown enabled`, `not enabled`, or `unknown`.
  - [ ] Secure profiles can warn or block start when lockdown is required but not observed.
  - [ ] UI disables or explains disconnect actions when Android controls an always-on VPN lifecycle.
  - [ ] Tests cover the health-state reducer without requiring private Android APIs.

  ## Design notes

  Use explicit language: RIPDPI can fail closed inside its service, but Android system lockdown is the only consumer-grade hard kill switch.

  ## Risks / open questions

  - Android exposes limited public state for lockdown; some verification may need behavioral tests rather than a direct setting read.

  ## Links

  - [[Epic - Fail-closed Android VPN policy engine]]
  - https://developer.android.com/develop/connectivity/vpn

- [ ] #task Add DNS interceptor and split DNS leak tests #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog 🔺 [paperclip:POY-73]
  - Paperclip: POY-73 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, dns, leak-test
  - **source:** `TaskNotes/Tasks/Add DNS interceptor and split DNS leak tests.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Route app DNS through RIPDPI's VPN DNS interceptor and add leak tests proving proxied domains do not fall back to the device or ISP resolver.

  ## Motivation

  DNS leaks are one of the main ways GUI VPN clients fail despite a working transport. The VPN profile must set an internal DNS address and enforce split DNS through policy, not rely on the underlying network defaults.

  ## Scope

  - In scope: VPN DNS address setup, DNS hijack/intercept path, bootstrap resolution policy, direct-domain resolver, proxied-domain resolver, and leak-test instrumentation.
  - Out of scope: broad public resolver benchmarking and server-side DNS operation.

  ## Acceptance criteria

  - [ ] VPN builder always sets DNS servers for secure VPN profiles.
  - [ ] Transport endpoint bootstrap resolution is explicitly scoped and cannot route back into the TUN loop.
  - [ ] RU/direct domains can resolve through direct policy while proxied domains resolve through the selected outbound.
  - [ ] Proxy/default DNS failure uses encrypted backup or fails closed; it never falls back to plaintext system DNS.
  - [ ] Leak test detects fallback to default-network DNS for proxied domains.
  - [ ] Network-switch tests verify DNS policy remains intact across Wi-Fi and cellular changes.

  ## Design notes

  This task is about Android VPN DNS enforcement; it should reuse the existing DNS classifier where possible instead of duplicating DNS classification logic.

  ## Risks / open questions

  - Captive portal assist may need a temporary DNS exception; keep it explicit and short-lived.

  ## Links

  - [[Epic - Fail-closed Android VPN policy engine]]
  - [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
  - [[Epic - Encrypted DNS and HTTPS SVCB classifier]]
  - [[Select resolver mapping from DNS classification]]

- [ ] #task Add NetworkCallback reconnect and underlying-network tracking #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog ⏫ [paperclip:POY-81]
  - Paperclip: POY-81 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, network, lifecycle
  - **source:** `TaskNotes/Tasks/Add NetworkCallback reconnect and underlying-network tracking.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Use `ConnectivityManager.NetworkCallback` and `setUnderlyingNetworks()` to drive reconnect, failover, and policy refresh across Wi-Fi, cellular, captive, metered, suspended, and lost-network states.

  ## Motivation

  Polling produces stale snapshots and misses transition windows where clients leak or show the wrong state. RIPDPI should treat network changes as lifecycle events.

  ## Scope

  - In scope: network callbacks, capability/link-property handling, underlying-network publication, bootstrap re-evaluation, and transition tests.
  - Out of scope: location-derived network fingerprinting and broad ISP profiling.

  ## Acceptance criteria

  - [ ] `onAvailable`, `onCapabilitiesChanged`, `onLinkPropertiesChanged`, and `onLost` update VPN provider state without polling loops.
  - [ ] DNS, route, metered, captive, suspended, and transport changes trigger scoped policy re-evaluation.
  - [ ] VPN builder sets underlying networks when available and safe.
  - [ ] Wi-Fi to LTE, LTE to Wi-Fi, sleep/wake, and captive-portal transitions do not mark the tunnel connected until health checks pass.
  - [ ] Transition tests verify no direct fallback occurs during reconnect.

  ## Design notes

  Network callbacks should feed the same supervisor state machine used by direct-mode and Xray provider mode.

  ## Risks / open questions

  - Some callback fields can be privacy-sensitive; keep persisted network keys coarse.

  ## Links

  - [[Epic - Fail-closed Android VPN policy engine]]
  - [[Epic - Runtime lifecycle and supervisors]]
  - https://developer.android.com/develop/connectivity/network-ops/reading-network-state

- [ ] #task Add authoritative DNS leak-test harness #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog ⏫ [paperclip:POY-101]
  - Paperclip: POY-101 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, ripdpi, dns, testing, leak-test
  - **source:** `TaskNotes/Tasks/Add authoritative DNS leak-test harness.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Build a DNS leak-test harness using unique random test domains and an authoritative test zone, so QA can verify which resolver path actually saw the query.

  ## Context

  Public DNS leak-test pages are useful but not reproducible enough for RIPDPI regression work. A controlled authoritative zone lets the app test proxy, direct, IPv6, captive, and outage scenarios without logging user-identifying profile data.

  ## Acceptance criteria

  - [ ] Test harness generates unique per-run domains for proxy, direct, IPv6, and captive scenarios.
  - [ ] Authoritative logs record resolver source and coarse time bucket without storing live profile secrets.
  - [ ] App-side test reports expected resolver path versus observed resolver path.
  - [ ] Failure cases cover remote resolver outage, bootstrap resolver failure, proxy outbound failure, Android Private DNS enabled, Wi-Fi/LTE switch, captive portal, and core crash.
  - [ ] Harness integrates with the Android VPN leak-test matrix.

  ## Notes

  Do not store live device identifiers or subscription tokens in authoritative DNS logs.

  ## Links

  - [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
  - [[Add Android VPN leak-test instrumentation matrix]]
  - [[Add DNS interceptor and split DNS leak tests]]

- [ ] #task Add captive portal DNS assist via Network object #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog ⏫ [paperclip:POY-105]
  - Paperclip: POY-105 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, dns, captive-portal
  - **source:** `TaskNotes/Tasks/Add captive portal DNS assist via Network object.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Implement captive-portal DNS assist as an explicit temporary state using Android's captive `Network` object, not a general fallback to local DNS.

  ## Motivation

  Captive portals often require local DNS interception, but silently weakening DNS policy creates leaks. RIPDPI should make captive handling explicit, scoped, and short-lived.

  ## Scope

  - In scope: portal state transition, portal-host allowlist, captive `Network` use, temporary direct DNS/HTTP for portal only, expiry, and UI warning.
  - Out of scope: broad direct browsing during captive mode.

  ## Acceptance criteria

  - [ ] Captive mode is entered only after Android or diagnostics identify a captive portal condition.
  - [ ] Portal DNS/HTTP uses the captive `Network` object and only portal-scoped host/IP data.
  - [ ] General proxy/default DNS remains strict and does not fall back to captive DNS.
  - [ ] UI states that DNS is temporarily not private for portal login.
  - [ ] Captive success or timeout returns the app to strict DNS policy.

  ## Design notes

  This refines the broader captive/whitelist state task by specifying the DNS behavior.

  ## Risks / open questions

  - Portal detection and portal URL exposure can be inconsistent; keep fallbacks user-driven.

  ## Links

  - [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
  - [[Add captive-portal and whitelist-mode connection states]]
  - https://developer.android.com/reference/android/net/ConnectivityManager

- [ ] #task Add captive-portal and whitelist-mode connection states #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog 🔼 [paperclip:POY-107]
  - Paperclip: POY-107 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, captive-portal, whitelist
  - **source:** `TaskNotes/Tasks/Add captive-portal and whitelist-mode connection states.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Add explicit captive-portal assist and whitelist-suspected connection states so restricted networks do not appear as generic VPN failures.

  ## Motivation

  During captive portals, mobile restrictions, or whitelist-style shutdowns, a VPN client can look broken even when the correct answer is controlled direct access, relay suggestion, or a blocked/offline state.

  ## Scope

  - In scope: state model, UI copy, short-lived captive portal assist, whitelist suspected detection, and diagnostic evidence summary.
  - Out of scope: building a domestic whitelist relay or storing live relay infrastructure data in TaskNotes.

  ## Acceptance criteria

  - [ ] Connection state can represent `Captive portal assist`, `Whitelist suspected`, `No connectivity`, and `Blocked / reconnecting`.
  - [ ] Captive portal assist requires explicit user action and expires automatically.
  - [ ] Whitelist suspected requires evidence that normal foreign endpoints fail while allowed domestic probes succeed.
  - [ ] UI suggests configured whitelist relay profile only if one exists in the local profile.
  - [ ] No automatic hidden bypass opens broad direct traffic while secure VPN mode is expected.

  ## Design notes

  Keep this as a user-visible network condition, not a secret routing exception.

  ## Risks / open questions

  - Portal and whitelist probes can be privacy-sensitive; use minimal and configurable probe sets.

  ## Links

  - [[Epic - Fail-closed Android VPN policy engine]]
  - [[Epic - Direct-mode diagnostic state machine]]
  - [[Replace generic relay suggestion with transport-specific remediation ladder]]

- [ ] #task Add explicit IPv6 policy modes and leak tests #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog 🔺 [paperclip:POY-114]
  - Paperclip: POY-114 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, ipv6, leak-test
  - **source:** `TaskNotes/Tasks/Add explicit IPv6 policy modes and leak tests.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Add explicit IPv4-only and verified dual-stack VPN modes, with tests proving IPv6 cannot bypass the tunnel accidentally.

  ## Motivation

  Existing Android clients often proxy IPv4 while IPv6 continues over the underlying network. RIPDPI should default to IPv4-only unless full dual-stack routing, DNS, and leak tests pass.

  ## Scope

  - In scope: profile-level IPv6 policy, VPN builder address/route/DNS handling, re-establish behavior when policy changes, and IPv6 leak tests.
  - Out of scope: server-side IPv6 provisioning and user-facing education beyond state labels.

  ## Acceptance criteria

  - [ ] Secure default is `ipv4_only`.
  - [ ] IPv4-only profiles do not add IPv6 address, route, DNS, or `allowFamily(AF_INET6)` behavior.
  - [ ] Dual-stack mode requires explicit profile support for IPv6 TUN address, `::/0`, AAAA DNS through tunnel, and transport support.
  - [ ] Changing IPv6 mode forces VPN session re-establish.
  - [ ] Leak tests fail if an IPv6-capable network exposes direct public IPv6 while VPN is connected.

  ## Design notes

  Treat direct IPv6 while IPv4 is proxied as a leak state, not a feature.

  ## Risks / open questions

  - Android builder family behavior can be subtle when DNS servers or addresses implicitly allow a family; tests should exercise real Builder configurations where possible.

  ## Links

  - [[Epic - Fail-closed Android VPN policy engine]]
  - https://developer.android.com/reference/android/net/VpnService.Builder

- [ ] #task Add no-secret logging and diagnostics redaction tests #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog ⏫ [paperclip:POY-128]
  - Paperclip: POY-128 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, diagnostics, privacy
  - **source:** `TaskNotes/Tasks/Add no-secret logging and diagnostics redaction tests.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Add release-log and diagnostics tests that fail if VPN credentials, subscription URLs, tokens, endpoints, or raw configs appear in logcat, crash reports, or exported bundles.

  ## Motivation

  Several existing clients leak operational details through logcat, crash exports, copied URIs, or diagnostics. RIPDPI should make no-secret logging a tested invariant.

  ## Scope

  - In scope: redaction helpers, R8 release-log policy, diagnostics-mode consent and TTL, export bundle redaction, and test fixtures with realistic secret-looking values.
  - Out of scope: third-party crash-report service integration.

  ## Acceptance criteria

  - [ ] Release builds strip or downgrade verbose logs that could contain network/config state.
  - [ ] Test fixtures containing UUIDs, shortIds, subscription tokens, passwords, and endpoints are fully redacted from diagnostics output.
  - [ ] Diagnostics mode is opt-in, time-limited, and exports encrypted or explicitly user-controlled bundles.
  - [ ] Crash/report path stores config hash, profile ID, and state reason rather than raw profile fields.
  - [ ] Clipboard/share actions clear or warn when content contains live profile material.

  ## Design notes

  Prefer deny-by-default secret wrappers plus allowlisted diagnostic fields. Do not rely only on regex cleanup after logging.

  ## Risks / open questions

  - Some lower-level native libraries may log before Kotlin redaction; capture and sanitize their output path separately.

  ## Links

  - [[Epic - Fail-closed Android VPN policy engine]]
  - [[Epic - Privacy and diagnostics]]
  - https://developer.android.com/privacy-and-security/risks/log-info-disclosure

- [ ] #task Add per-device subscription token UX and shared-link warnings #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog ⏫ [paperclip:POY-131]
  - Paperclip: POY-131 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, subscriptions, credentials
  - **source:** `TaskNotes/Tasks/Add per-device subscription token UX and shared-link warnings.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Add client UX and storage fields for per-device subscription tokens, expiry, rotation, and warnings when an imported subscription appears shared or unsafe.

  ## Motivation

  Shared subscription URLs turn one leak into full-fleet credential exposure. RIPDPI should present subscriptions as device-scoped credentials with expiry and rotation state, not anonymous URL lists.

  ## Scope

  - In scope: subscription detail fields, expiry/refresh state, token rotation state, one-time bootstrap import handling, shared-link warnings, and no-secret UI reveal behavior.
  - Out of scope: implementing the remote delivery service or deciding provider billing policy.

  ## Acceptance criteria

  - [ ] Subscription detail screen shows device ID, profile version, last refresh, token expiry, credential expiry, and assigned profile count without revealing secrets by default.
  - [ ] Imported bootstrap tokens are marked distinct from persistent subscription tokens.
  - [ ] App warns when a subscription payload appears to contain multiple users, shared UUIDs, or all-fleet profiles.
  - [ ] Refresh failures distinguish expired, revoked, rate-limited, and unreachable states without logging the URL.
  - [ ] Full URL, token, UUID, shortId, and passwords require explicit reveal and are redacted in screenshots/exports where possible.

  ## Design notes

  This task is client-side only. Server-side delivery and token validation belong outside the Android app.

  ## Risks / open questions

  - Third-party providers may not expose enough metadata to prove a token is per-device; warnings may need heuristic language.

  ## Links

  - [[Epic - Fail-closed Android VPN policy engine]]
  - [[Epic - NekoBox subscription and profile import]]
  - [[Add subscription auto-update WorkManager worker]]

- [ ] #task Add priority-based outbound failover state machine #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog ⏫ [paperclip:POY-133]
  - Paperclip: POY-133 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, failover, selector
  - **source:** `TaskNotes/Tasks/Add priority-based outbound failover state machine.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Implement a priority-based outbound failover state machine that prefers primary REALITY, then HTTPS fallback, then Hysteria2 only when UDP is viable, while still allowing manual selector override.

  ## Motivation

  Manual failover leaves users guessing which profile works. Latency-only auto-selection can choose a fast but fragile UDP path. RIPDPI needs policy-aware failover that understands censorship-bypass priorities.

  ## Scope

  - In scope: connection states, health probes, manual selector override, URL-test style scoring, UDP viability gate, and UI state for active outbound.
  - Out of scope: adding new transport protocols beyond the initial primary/fallback/speed roles.

  ## Acceptance criteria

  - [ ] State machine represents `CONNECTED_PRIMARY`, `TRY_HTTPS_FALLBACK`, `TRY_HYSTERIA2`, `WHITELIST_MODE_HINT`, and `BLOCKED_RECONNECTING`.
  - [ ] Default auto mode tests primary REALITY and HTTPS fallback before considering Hysteria2.
  - [ ] Hysteria2 becomes an auto candidate only after UDP/443 viability is confirmed for the current network.
  - [ ] Manual selector override is visible and can be reset to auto.
  - [ ] Existing connections are not interrupted unless the user or emergency failover policy explicitly requests it.

  ## Design notes

  This is different from subscription group selection. It is the runtime outbound policy for a single device profile.

  ## Risks / open questions

  - Health probes must avoid creating a recognizable, high-frequency pattern against the same endpoints.

  ## Links

  - [[Epic - Fail-closed Android VPN policy engine]]
  - [[Epic - Xray VPN client mode]]
  - [[Add selector outbound runtime for group-based profile switching]]

- [ ] #task Bind DNS answers to route decisions #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog ⏫ [paperclip:POY-159]
  - Paperclip: POY-159 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, dns, routing
  - **source:** `TaskNotes/Tasks/Bind DNS answers to route decisions.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Store DNS answers with resolver-path and route-decision metadata so direct answers are not accidentally reused for proxy routes or the reverse.

  ## Motivation

  Split-brain DNS is a leak and compatibility risk: the same domain can return different CDN answers depending on resolver path, and the connection route must match the DNS decision.

  ## Scope

  - In scope: `ResolvedAnswer` metadata, route-aware cache keys, TTL caps, negative-cache policy, and policy checks before connection.
  - Out of scope: FakeIP implementation and large route-rule editor UI.

  ## Acceptance criteria

  - [ ] DNS cache entries record domain, qtype, IPs, resolver path, route decision, expiry, and source policy version.
  - [ ] Direct DNS answers are not reused for proxy routes unless policy explicitly permits it.
  - [ ] Proxy DNS answers are not reused for direct RU/local routes unless policy explicitly permits it.
  - [ ] Negative cache has short bounded TTL and preserves resolver path.
  - [ ] Route decision mismatch triggers re-resolution or fail-closed behavior, not silent reuse.

  ## Design notes

  This task is the runtime coherence layer between DNS policy and routing policy.

  ## Risks / open questions

  - Hardcoded-IP connections have no DNS answer to bind. They should be handled by routing rules and diagnostics separately.

  ## Links

  - [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
  - [[Add Rust rule matcher with domain ip port process matchers]]
  - [[Add geoip.db and geosite.db runtime loader and lookup]]

- [ ] #task Define policy bundle profile schema #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog 🔺 [paperclip:POY-181]
  - Paperclip: POY-181 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, profile, policy
  - **source:** `TaskNotes/Tasks/Define policy bundle profile schema.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Define RIPDPI's internal full-device policy profile schema so URI strings become import/export formats, not the app's runtime source of truth.

  ## Motivation

  Existing clients often lose routing, DNS, IPv6, selector, kill-switch, and credential lifecycle policy because subscriptions only carry transport URIs. RIPDPI needs one typed model that can render transport configs and drive Android policy consistently.

  ## Scope

  - In scope: schema fields for device ID, profile version, outbounds, selector/urltest, routing, DNS, IPv6, kill-switch, subscription state, expiry, and redaction metadata.
  - Out of scope: public server-side delivery API and payment/subscription business logic.

  ## Acceptance criteria

  - [ ] `DeviceProfile` or equivalent typed model represents transport profiles plus policy, not just `vless://` / `hy2://` strings.
  - [ ] VLESS/REALITY, XHTTP/HTTPS, and Hysteria2 initial outbound shapes can be represented without raw JSON.
  - [ ] Secrets are represented through redacted/secret wrapper types and never through default `toString()`.
  - [ ] Schema has explicit `profile_version`, `expires_at`, and migration hooks.
  - [ ] Renderers can derive Xray/sing-box-style config fragments from the typed model without losing policy information.

  ## Design notes

  Keep this schema separate from direct-mode strategy packs. It describes the user device VPN profile, while strategy packs describe censorship-bypass decisions and rule catalogs.

  ## Risks / open questions

  - Decide whether imported third-party subscription profiles become lossy typed records or preserve a redacted raw extension block for unsupported fields.

  ## Links

  - [[Epic - Fail-closed Android VPN policy engine]]
  - [[Render validated Xray client configs]]
  - [[Add ProxyGroup and Subscription entities to RIPDPI data layer]]

- [ ] #task Define split-strict DNS policy model #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog 🔺 [paperclip:POY-182]
  - Paperclip: POY-182 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, dns, policy
  - **source:** `TaskNotes/Tasks/Define split-strict DNS policy model.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Define a split-strict DNS policy model that separates bootstrap, direct, tunneled, and blocked resolver paths inside the device VPN profile.

  ## Motivation

  RIPDPI should not treat DNS as a single resolver setting. DNS route and connect route must stay coherent, and encrypted resolver failure must not fall back to plaintext local DNS for proxied domains.

  ## Scope

  - In scope: model types for resolver planes, domain classes, qtype policy, strict failure, IPv6 interaction, cache metadata, and profile serialization.
  - Out of scope: DNS packet parser implementation and server-side resolver deployment.

  ## Acceptance criteria

  - [ ] Policy model has distinct `bootstrap`, `proxy`, `direct`, and `block/refuse` resolver paths.
  - [ ] Proxy/default domains require strict encrypted DNS and cannot fall back to direct plaintext DNS.
  - [ ] Direct DNS can be selected only for domains whose connect route is also DIRECT.
  - [ ] `AAAA` handling is explicit and tied to the active IPv6 policy.
  - [ ] Policy serialization can represent DoH POST, DoT strict, optional DoQ, pinned bootstrap IP, and direct allowlists.

  ## Design notes

  This should feed both Android runtime DNS decisions and profile rendering. It complements, but does not replace, the direct-mode DNS classifier.

  ## Risks / open questions

  - Avoid two parallel DNS policy systems: direct-mode classifier output should map into this runtime policy rather than bypass it.

  ## Links

  - [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
  - [[Define policy bundle profile schema]]
  - [[Add DNS interceptor and split DNS leak tests]]

- [ ] #task Encrypt VPN profiles with Android Keystore #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog ⏫ [paperclip:POY-188]
  - Paperclip: POY-188 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, secrets, storage
  - **source:** `TaskNotes/Tasks/Encrypt VPN profiles with Android Keystore.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Store VPN profiles, subscription state, and credential-bearing rule metadata encrypted with an Android Keystore-backed key and internal storage only.

  ## Motivation

  VPN client profiles contain bearer credentials. They must not live in plaintext SharedPreferences, external storage, logs, screenshots, or crash reports.

  ## Scope

  - In scope: Keystore key management, encrypted profile blobs, migration from plaintext fields if any exist, redacted secret wrappers, and storage tests.
  - Out of scope: cloud backup integration and server-side secret management.

  ## Acceptance criteria

  - [ ] Profile and subscription credential blobs are encrypted at rest in internal app storage.
  - [ ] Android Keystore holds the key-encryption key and uses StrongBox when available and configured.
  - [ ] Secret wrapper types redact `toString()`, equality debug output, logs, and diagnostics.
  - [ ] Migration path can import existing plaintext development profiles and remove plaintext copies.
  - [ ] Tests prove exported diagnostics contain config hashes or fingerprints, not raw credentials.

  ## Design notes

  Public values can still become sensitive when combined with endpoints. Keep profile export and diagnostics conservative even for public keys.

  ## Risks / open questions

  - StrongBox availability and authentication requirements vary by device; default should not make normal VPN startup fragile.

  ## Links

  - [[Epic - Fail-closed Android VPN policy engine]]
  - [[Epic - Privacy and diagnostics]]
  - https://developer.android.com/privacy-and-security/keystore

- [ ] #task Enforce fail-closed VpnService lifecycle #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog ⏫ [paperclip:POY-190]
  - Paperclip: POY-190 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, vpn, lifecycle
  - **source:** `TaskNotes/Tasks/Enforce fail-closed VpnService lifecycle.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Make VpnService startup, core failure, and `onRevoke()` paths fail closed by closing TUN, protected sockets, and provider runtimes before any direct traffic can continue silently.

  ## Motivation

  Existing clients often look connected while the core, DNS resolver, or protected socket path has failed. RIPDPI should enter `Blocked / reconnecting` or `Revoked` states rather than leaving traffic behavior ambiguous.

  ## Scope

  - In scope: `prepare()` handling, foreground startup sequencing, `protect()` failure behavior, TUN establishment failure, core crash handling, and `onRevoke()` cleanup.
  - Out of scope: Android system lockdown implementation and provider-specific protocol retries.

  ## Acceptance criteria

  - [ ] VPN start aborts before TUN establishment if required transport sockets cannot be protected.
  - [ ] Core crash transitions connection state to blocked/reconnecting rather than connected.
  - [ ] `onRevoke()` closes TUN fd, tunnel sockets, provider runtimes, and local inbounds without main-thread assumptions.
  - [ ] Secure profiles never call `Builder.allowBypass()` unless the user explicitly enables an unsafe bypass setting.
  - [ ] Regression tests cover startup failure, core crash, and revoke cleanup.

  ## Design notes

  Coordinate state names with Xray provider state so direct-mode and Xray-backed mode report the same lifecycle semantics.

  ## Risks / open questions

  - Some OEMs reorder service shutdown and revoke callbacks; cleanup must be idempotent.

  ## Links

  - [[Epic - Fail-closed Android VPN policy engine]]
  - [[Epic - Runtime lifecycle and supervisors]]
  - [[Run Xray as managed VPN relay runtime]]
  - https://developer.android.com/reference/android/net/VpnService.Builder

- [ ] #task Harden DoH POST resolver client #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog ⏫ [paperclip:POY-197]
  - Paperclip: POY-197 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, dns, doh, privacy
  - **source:** `TaskNotes/Tasks/Harden DoH POST resolver client.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Make DoH POST the privacy-first runtime DNS path for proxied domains and harden it against URL/query logging, cache leakage, and resolver authentication mistakes.

  ## Motivation

  DoH GET encodes the DNS query in the URL. RIPDPI's runtime resolver should prefer POST, no-store semantics, authenticated TLS, and no logging of request body, path, or query.

  ## Scope

  - In scope: DoH POST runtime mode, no-store headers, resolver auth name, pinned bootstrap IP support, response validation, and redacted diagnostics.
  - Out of scope: DoH JSON runtime resolver and public resolver survey probes.

  ## Acceptance criteria

  - [ ] Runtime proxy DNS uses DoH POST by default for encrypted DNS.
  - [ ] DoH GET is disabled for runtime resolver unless a profile explicitly enables it for compatibility.
  - [ ] Request URL, body, domain, and response payload are absent from release logs and diagnostics by default.
  - [ ] Resolver TLS authentication validates the expected auth name and configured trust/pin policy.
  - [ ] DoH failure integrates with strict tunneled resolver failover and never falls back to plaintext local DNS.

  ## Design notes

  Diagnostics may still probe DoH JSON or GET as separate evidence sources, but runtime resolution should remain wire-format POST by default.

  ## Risks / open questions

  - Some resolvers may behave differently for POST and GET; keep this as a profile capability, not a silent fallback.

  ## Links

  - [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
  - [[Build DoH primary and secondary resolver pipeline]]
  - [[Add DoH JSON API resolver path alongside RFC 8484 wire]]

- [ ] #task Implement scoped bootstrap DNS allowlist #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog ⏫ [paperclip:POY-204]
  - Paperclip: POY-204 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, dns, bootstrap
  - **source:** `TaskNotes/Tasks/Implement scoped bootstrap DNS allowlist.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Implement bootstrap DNS that resolves only pinned or allowlisted transport, delivery, and resolver-auth hostnames needed to start the VPN.

  ## Motivation

  Cold-start DNS is a leak risk and a routing-loop risk. RIPDPI should avoid the pattern where system DNS resolves everything until the VPN is connected.

  ## Scope

  - In scope: bootstrap allowlist, qtype limits, short TTL cap, pinned IP preference, last-known-good cache, and explicit bootstrap-failed state.
  - Out of scope: general DNS resolution and public resolver benchmarking.

  ## Acceptance criteria

  - [ ] Bootstrap resolver rejects names outside the profile allowlist.
  - [ ] Bootstrap `AAAA` is disabled unless the profile IPv6 mode is dual-stack.
  - [ ] Pinned endpoint IPs are preferred over system resolution when present.
  - [ ] Last-known-good endpoint cache has bounded TTL and is tagged as bootstrap-derived.
  - [ ] Bootstrap failure produces a typed state and never enables general ISP DNS fallback.

  ## Design notes

  Bootstrap is allowed to use direct/local DNS only for its tiny scope. Once the VPN is active, normal split-strict DNS policy owns resolution.

  ## Risks / open questions

  - Endpoint DNS migration conflicts with pinning; subscription/profile refresh must be the path for durable endpoint changes.

  ## Links

  - [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
  - [[Bridge TUN traffic through Xray local inbound]]
  - [[Add NetworkCallback reconnect and underlying-network tracking]]

- [ ] #task Implement strict tunneled DNS resolver failover #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog ⏫ [paperclip:POY-206]
  - Paperclip: POY-206 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, dns, failover
  - **source:** `TaskNotes/Tasks/Implement strict tunneled DNS resolver failover.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Add strict encrypted DNS failover for proxied/default domains: retry encrypted resolvers and allowed fallback outbounds, then fail closed with no plaintext local fallback.

  ## Motivation

  The most dangerous DNS bug is turning resolver outage into an ISP DNS leak. Proxied domains must fail closed or use an encrypted backup path.

  ## Scope

  - In scope: primary/secondary encrypted resolver order, fallback outbound list, strict failure state, cache use, and resolver outage tests.
  - Out of scope: direct RU DNS fallback and server-side resolver operation.

  ## Acceptance criteria

  - [ ] Proxy DNS tries configured encrypted resolvers through the active outbound first.
  - [ ] If active outbound DNS fails, only explicitly allowed encrypted DNS fallback outbounds are attempted.
  - [ ] Total failure returns `DNS_FAILED_STRICT` or equivalent and `SERVFAIL`/blocked state to callers.
  - [ ] No code path uses system/local plaintext DNS for proxy/default domains after strict failure.
  - [ ] Tests cover remote DoH block, DoT block, DoQ block, proxy-outbound failure, and cache-assisted recovery.

  ## Design notes

  DoH POST should be the default hostile-network resolver; DoT and DoQ remain profile-controlled options.

  ## Risks / open questions

  - Resolver retry cadence can become a fingerprint if it is too regular across users; keep health checks scoped and backoff-driven.

  ## Links

  - [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
  - [[Build DoH primary and secondary resolver pipeline]]
  - [[Gate DoQ on UDP-clean classification]]

- [ ] #task Spike FakeIP mode compatibility on Android #repo/RIPDPI #area/fail-closed-android-vpn #status/backlog 🔽 [paperclip:POY-241]
  - Paperclip: POY-241 · assigned to: unassigned
  - Parent: POY-46 (Epic - Fail-closed Android VPN policy engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, spike, ripdpi, dns, fakeip
  - **source:** `TaskNotes/Tasks/Spike FakeIP mode compatibility on Android.md`
  - **epic:** Epic - Fail-closed Android VPN policy engine

  ## Summary

  Evaluate FakeIP mode as an advanced Android profile option, while keeping Real IP plus domain mapping cache as the production default.

  ## Context

  FakeIP can improve domain-aware routing but can also break captive portals, local networks, hardcoded-IP flows, and OEM network behavior. RIPDPI should not ship it as the default without compatibility evidence.

  ## Acceptance criteria

  - [ ] Document candidate FakeIP pool, route rules, and reverse mapping requirements.
  - [ ] Test at least browser, Telegram-like, bank/gov-direct, captive portal, local LAN, and hardcoded-IP flows.
  - [ ] Compare failure modes against Real IP plus resolver-path metadata.
  - [ ] Recommend ship/no-ship for advanced profiles with explicit caveats.

  ## Notes

  This is intentionally low priority. The current production recommendation is Real IP mode.

  ## Links

  - [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
  - [[Bind DNS answers to route decisions]]


## general

- [ ] #task Remove PCAP from normal diagnostics archives and harden developer-analytics.json #repo/RIPDPI #area/general #status/backlog ⏫ [paperclip:POY-27]
  - Paperclip: POY-27 · assigned to: Senior Android Engineer
  
  Objective:
  Bring `DefaultDiagnosticsArchiveExporter` and `developer-analytics.json` content into compliance with the AppSec decision on POY-14 (which adopts the CTO boundary in POY-13). PCAP files must not be auto-attached to normal archives, and `developer-analytics.json` must drop fields that have no user-facing disclosure.

  Context:
  AppSec POY-14 verdict: changes_requested. The current `DefaultDiagnosticsArchiveExporter.createArchive` calls `selection.copy(pcapFiles = fileStore.getRecentPcapFiles())` for every archive request type (`SHARE_ARCHIVE`, `SAVE_ARCHIVE`, `SHARE_DEBUG_BUNDLE`, `SHARE_HOME_ANALYSIS`), and `DiagnosticsArchiveCsvEntryBuilder.buildCsvEntries` writes each `.pcap` byte-for-byte as a zip entry. This contradicts README:56 and the POY-13 boundary. Separately, `DefaultDeveloperAnalyticsSource` ships `lastPanicBacktrace`, `nativeLibDigests`, `breadcrumbs`, `pcapManifest`, and a config diff (including `rootModeEnabled`, `enableCmdSettings`) inside `developer-analytics.json` for every archive without disclosure on `DataTransparencyScreen`.

  Owner:
  Senior Android Engineer (RIPDPI).

  User story:
  As a RIPDPI user sharing a diagnostics archive, I want my exported zip to never silently include packet captures, native panic backtraces, or build digests, so that what I share matches the on-screen disclosure.

  Affected surface:
  - `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveExporter.kt`
  - `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveCsvEntryBuilder.kt`
  - `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveFileStore.kt`
  - `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveModels.kt`
  - `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveRenderer.kt`
  - `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DeveloperAnalyticsModel.kt`
  - `app/src/main/kotlin/com/poyka/ripdpi/diagnostics/DefaultDeveloperAnalyticsSource.kt`

  Acceptance criteria:
  1. F-01 (Critical): For all four `DiagnosticsArchiveReason` values, the rendered zip contains zero entries with the `.pcap` extension and `manifest.includedFiles` lists no `.pcap`. The exporter must not call `getRecentPcapFiles()` on the normal share/save paths; if a future explicit "Share PCAP" action lands, it does so via a separate code path.
  2. F-03 (High): `developer-analytics.json` for normal archive reasons must omit `lastPanicBacktrace`, `nativeLibDigests`, `breadcrumbs`, `pcapManifest`, and `effectiveConfigDiff` until each is re-introduced under an AppSec-approved allow-list (out of scope for this issue).
  3. F-06 (Medium): Add a redaction pass over `probe-results.csv` (`detailJson`, `target`) and `native-events.csv` (`message`) that strips IP/SSID/MAC/email/path-style strings, OR document in code why those columns can never carry such values from upstream sanitisation.
  4. F-08 (Low): Remove the duplicate logcat tail in `DefaultDeveloperAnalyticsSource.readLogcatTail` (or remove the `LogcatSnapshotCollector` path) so only one logcat capture lands in the archive.
  5. One-time cleanup: on first launch of the build that lands these changes, invoke `cleanupPcapFiles()` ignoring the 24h window when `rootModeEnabled == false`, so pre-upgrade `.pcap` files cannot survive into a build that forbids auto-attach.

  Required verification:
  - Add tests `createArchive_share_archive_excludes_pcap_when_recent_pcap_files_exist` and the equivalent for `SAVE_ARCHIVE`, `SHARE_DEBUG_BUNDLE`, `SHARE_HOME_ANALYSIS` in `DiagnosticsArchiveExporterTest`.
  - Extend `DiagnosticsArchiveRendererTest` with assertions on `manifest.includedFiles`, `developer-analytics.json` absence of forbidden fields, and a redaction sweep on the rendered byte buffers.
  - Add or extend `DiagnosticsArchiveRedactorTest` with a fuzz-style test that constructs a `NetworkSnapshotModel` with non-default sensitive fields and asserts no verbatim original value reaches the encoded JSON.
  - AppSec re-review on a single re-review request once F-01..F-04 are addressed.

  Required reviewers:
  Security/AppSec Engineer (mandatory), QA Lead, Principal Android/Rust Architect.

  Privacy implication:
  High. Closing F-01 is a release-blocker for AppSec re-approval.

  Rollback note:
  Reverting reintroduces the auto-attach. Do not revert without AppSec approval. No data migration is required because `.pcap` files are kept in `cacheDir/diagnostics` and Android handles cache cleanup on uninstall.

  Non-goals:
  - No copy or docs changes (owned by POY-15).
  - No QA gate definition (owned by POY-16).
  - No new "Share PCAP" action (would need its own AppSec review per POY-14 §4.4).
  - No re-introduction of any field listed in F-03 without an AppSec allow-list issue.

  Definition of done:
  The four normal archive reasons produce archives with no `.pcap` entries and a sanitised `developer-analytics.json`; tests above are green; AppSec re-approves on re-review of POY-14.

- [ ] #task Gate Diagnostics packet-capture surface on rootModeEnabled and add raw-packet disclosure #repo/RIPDPI #area/general #status/backlog ⏫ [paperclip:POY-28]
  - Paperclip: POY-28 · assigned to: Senior Android Engineer
  
  Objective:
  Bring the in-app packet-capture UI into compliance with the AppSec decision on POY-14. Today the Diagnostics-tools "Packet Capture" card is visible and operable on non-rooted devices, uses hardcoded English copy, and does not surface a raw-packet disclosure before recording starts.

  Context:
  AppSec POY-14 verdict: changes_requested. `DiagnosticsToolsSection.kt:124-138` renders "Packet Capture" / "Start Recording" / "Stop Recording" with hardcoded English strings and is shown unconditionally. `DiagnosticsViewModel.togglePcapRecording` flips a boolean without a `rootModeEnabled` check or a confirmation step. The Home full-analysis PCAP toggle is correctly gated (`MainHomeDiagnosticsUiState.kt:141: pcapToggleVisible = settings.rootModeEnabled`) and that gating is the model for this surface.

  Owner:
  Senior Android Engineer (RIPDPI).

  User story:
  As a non-rooted RIPDPI user, I do not want to see or accidentally start a packet-capture flow that requires root to be useful, and as an advanced/root user I want to be told what raw data will be written before recording begins.

  Affected surface:
  - `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsToolsSection.kt`
  - `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsRoute.kt`
  - `app/src/main/kotlin/com/poyka/ripdpi/activities/DiagnosticsViewModel.kt`
  - `app/src/main/res/values/strings.xml` and translation siblings
  - `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/settings/SettingsPreferencesScreen.kt` (delete-PCAP affordance)

  Acceptance criteria:
  1. F-02 (High): The "Packet Capture" card in `ToolsSection` is hidden when `settings.rootModeEnabled == false`. If product wants the card visible as a "requires advanced settings" affordance, render it disabled with a string resource explaining the requirement; do not allow `togglePcapRecording` to start a recording in that state.
  2. `DiagnosticsViewModel.togglePcapRecording` short-circuits to a no-op + user-visible error when `rootModeEnabled == false`.
  3. F-04 (High): All `Packet Capture` card copy moves to localised string resources matching the rest of the app's translation set. The card body must mention raw-packet capture, retention (24h / 3 most-recent files), and that no automatic export occurs.
  4. Pre-recording disclosure: tapping "Start Recording" on either the Diagnostics-tools surface or the Home full-analysis toggle shows a confirmation that names: raw IP packet bytes are written to a local file, retention window, that the user can stop recording at any time, and that PCAP files are not attached to normal diagnostics shares. Confirmation copy must be reviewed by Documentation/UX (POY-15) before merge.
  5. F-07 (Medium): Settings exposes a user-visible "Delete recorded packet captures" action that immediately invokes `DiagnosticsArchiveFileStore.cleanupPcapFiles()` ignoring the 24h window, and shows a confirmation toast.
  6. On `rootModeEnabled` transition true → false, invoke `cleanupPcapFiles()` ignoring the 24h window so advanced-mode artefacts do not persist.

  Required verification:
  - Compose semantics tests in `DiagnosticsScreenTest` (or new `DiagnosticsToolsSectionTest`) asserting visibility/disabled state by `rootModeEnabled`.
  - Compose semantics tests asserting that tapping "Start Recording" requires a confirmation step.
  - `HomeScreenTest` assertions: PCAP toggle hidden when `rootModeEnabled == false` and defaults off when toggled visible.
  - Roborazzi screenshot for the Diagnostics tools card in both states.
  - AppSec re-review on POY-14 once F-01..F-04 are addressed.

  Required reviewers:
  Security/AppSec Engineer (mandatory), Documentation/UX Engineer (copy), QA Lead.

  Privacy implication:
  High. F-02 and F-04 are release-blockers for AppSec re-approval.

  Rollback note:
  Reverting re-exposes the unguarded card. No retained-file impact because cleanup hook still runs.

  Non-goals:
  - No archive exporter changes (owned by sibling remediation issue).
  - No new "Share PCAP" export action (would need its own AppSec review per POY-14 §4.4).
  - No DataTransparencyScreen content additions (owned by POY-15).

  Definition of done:
  The Diagnostics packet-capture card and `togglePcapRecording` are correctly gated by `rootModeEnabled`, all copy is localised, a pre-recording confirmation is in place, a delete-PCAP action exists in Settings, and AppSec re-approves on re-review of POY-14.

- [ ] #task Add Telegram MTProto diagnostic with DC reachability and throughput #repo/RIPDPI #area/general #status/backlog 🔼 [paperclip:POY-92]
  - Paperclip: POY-92 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-25
  - **dateModified:** 2026-04-25
  - **area:** android
  - **tags:** task, feature, ripdpi, diagnostics, telegram, mtproto
  - **source:** `TaskNotes/Tasks/Add Telegram MTProto diagnostic with DC reachability and throughput.md`

  ## Summary

  A diagnostic profile that probes Telegram MTProto reachability and
  throughput from the current network: per-DC TCP ping across all known
  Telegram datacenters, plus a transient upload/download throughput run
  against a Telegram-owned endpoint with stall/slowdown classification.

  ## Motivation

  dpi-detector's Test 6 ("Telegram") fills a gap that RIPDPI's current
  diagnostics surface does not cover: it answers "is Telegram itself
  reachable on this network, and at what speed?" — independent of the
  WS tunnel relay path. RIPDPI already ships the WS tunnel
  (`skill: ws-tunnel-telegram`), but a diagnostic that quantifies the
  underlying transparent-Telegram baseline tells the user whether the
  tunnel is even necessary on the current network and gives a concrete
  throughput delta when it is.

  ## Scope

  - **In scope:** new diagnostic profile in `ripdpi-monitor` that
    enumerates Telegram DCs, performs a TCP-connect reachability probe
    per DC, and runs a short bidirectional throughput measurement against
    one healthy DC. Result class includes `ok`, `slow`, `stalled`,
    `blocked`, with timing and byte counts for both directions. Result
    surfaces as a Diagnostics screen card and an export-bundle entry.
  - **Out of scope:** any change to the WS tunnel relay path; persistent
    speed history; payload-level MTProto correctness (this is a transport
    reachability + throughput probe, not a protocol conformance test).

  ## Acceptance criteria

  - [ ] DC IP database from `ripdpi-ws-tunnel` (`dc_from_ip` /
        `TelegramDc`) is reused — no second source of truth.
  - [ ] Per-DC reachability probe reports `reachable: bool` plus median
        RTT for ports 443 and 80.
  - [ ] Throughput probe runs for a bounded wall-clock budget (default
        10s up, 10s down) and reports avg bps + total bytes per
        direction.
  - [ ] Stall detection: `stalled` if a transfer hits zero progress for
        ≥3s mid-run; `slow` if avg bps falls below a configurable floor.
  - [ ] Result surfaces in the diagnostics summary card and is included
        in `summary.txt` / `report.json` export bundle entries.
  - [ ] No payload data, IDs, or auth keys are logged or exported.
  - [ ] Probe is gated behind an explicit user toggle in the diagnostics
        profile picker — never runs automatically.

  ## Design notes

  Reuse the existing TCP probe primitives in `ripdpi-monitor`. The
  throughput measurement should select a DC from the reachable set; if
  none reachable, return `blocked` with the reachability matrix and skip
  the throughput stage. Honor VPN socket protection when running while
  the tunnel is active so the probe measures the correct path.

  ## Source reference

  dpi-detector v3.2.2: `core/telegram_scanner.py` —
  `_check_dc`, `_run_upload`, `_run_download`, `run_telegram_test`. The
  status taxonomy (`ok` / `stalled` / `slow` / `blocked`) and the
  upload/download asymmetry are taken directly from there.

  ## Risks / open questions

  - Endpoint selection for upload: dpi-detector uses Telegram CDN
    endpoints; confirm that the chosen endpoints are operationally
    acceptable to probe and not rate-limited.
  - "Slow" floor: 250 kbps is a defensible default but should be a
    configuration knob with sane regional defaults rather than a
    hard-coded constant.

  ## Links

  - [[ripdpi-android]]

- [ ] #task Add upstream HTTP and SOCKS5 proxy override for diagnostic probes #repo/RIPDPI #area/general #status/backlog 🔽 [paperclip:POY-151]
  - Paperclip: POY-151 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-25
  - **dateModified:** 2026-04-25
  - **area:** android
  - **tags:** task, feature, ripdpi, diagnostics, proxy
  - **source:** `TaskNotes/Tasks/Add upstream HTTP and SOCKS5 proxy override for diagnostic probes.md`

  ## Summary

  Allow diagnostic probes (TLS reachability, TCP 16-20KB cutoff, DNS
  resolver availability, HTTP injection) to be routed through an
  arbitrary upstream HTTP or SOCKS5 proxy supplied by the user, so the
  operator can compare results across paths without leaving the app.

  ## Motivation

  dpi-detector exposes `-p socks5://user:pass@host:port` to push every
  probe through an external proxy, which is invaluable for A/B comparing
  "network as-is" against "network via my pinned VPS proxy" or against a
  neighbour's tunnel. RIPDPI is itself a local proxy/VPN, so the natural
  question is "compare RIPDPI's transparent verdict against the same
  verdict via my external server" — which today requires running
  diagnostics on a separate device.

  This is opt-in and does not change the default diagnostic behavior.

  ## Scope

  - **In scope:** a diagnostic-scoped upstream-proxy field with HTTP and
    SOCKS5 (with auth) support; routing through the proxy is per-run,
    not persisted across sessions; visible badge in the diagnostics card
    showing "via upstream: <host>" so results aren't misread.
  - **Out of scope:** chained upstream proxies; proxy autodiscovery;
    reusing this proxy for the runtime relay/tunnel paths (those have
    their own profile editors).

  ## Acceptance criteria

  - [ ] Diagnostic profile supports `upstream_proxy: socks5://… | http://…`
        including basic auth in the URL.
  - [ ] When set, every TCP-based probe (TLS reachability, TCP 16-20KB,
        HTTP injection) routes through the proxy. DNS UDP probes are
        skipped or fall back to DoH-via-proxy and are flagged as such.
  - [ ] Diagnostics summary clearly labels the result as proxy-routed
        and never persists a transparent verdict from a proxy-routed run
        into the per-network policy store.
  - [ ] Proxy URL is treated as a credential: never logged at any level,
        never written to export bundles, redacted in summary.
  - [ ] Setting is per-run via the diagnostics screen; no global default.

  ## Design notes

  Reuse the existing local SOCKS5 client primitives in `ripdpi-runtime`
  where possible; if HTTP CONNECT is missing, add a minimal HTTP CONNECT
  adapter strictly for diagnostic use. Keep the proxy plumbing inside
  `ripdpi-monitor`; do not leak proxy state into the policy store or
  host autolearn paths — proxy-routed results have different validity.

  ## Source reference

  dpi-detector v3.2.2: `dpi_detector.py` `--proxy` CLI argument and
  `config.yml` `PROXY_URL`. Upstream proxy is wired into the shared
  `httpx.AsyncClient` for every probe.

  ## Risks / open questions

  - Cross-mode invariant: if RIPDPI's proxy/VPN service is running and
    the user also sets a diagnostic upstream proxy, the request graph
    becomes "RIPDPI → external proxy → target". The diagnostic must
    either disable the local service for the run or surface the
    double-hop topology in the result so the user understands what is
    being measured.
  - "No backend" rule still holds: the upstream proxy is user-supplied,
    not project-operated.

  ## Links

  - [[ripdpi-android]]

- [ ] #task Spike relay-assisted QUICstep rescue mode after NO_DIRECT_SOLUTION #repo/RIPDPI #area/general #status/backlog 🔽 [paperclip:POY-242]
  - Paperclip: POY-242 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-22
  - **dateModified:** 2026-04-22
  - **area:** android
  - **tags:** task, spike, ripdpi, quic, relay, research
  - **source:** `TaskNotes/Tasks/Spike relay-assisted QUICstep rescue mode after NO_DIRECT_SOLUTION.md`

  ## Summary

  Evaluate whether RIPDPI should add a second-tier rescue mode that uses a
  relay-assisted QUICstep-style first-flight bootstrap only after direct-mode has
  already returned `NO_DIRECT_SOLUTION`.

  ## Context

  The current direct-mode plan explicitly keeps relay-assisted QUICstep out of
  scope for the default no-proxy path. Today's [[quicstep-first-flight-hiding]]
  note sharpens why: it is strongest only for controlled infrastructure and
  first-flight classifiers, and becomes a liability when migration support is
  weak or generic QUIC blocking dominates.

  That still leaves a possible niche: a post-`NO_DIRECT_SOLUTION` rescue track
  for controlled server or CDN-backed controlled property, not arbitrary
  third-party sites.

  ## Acceptance criteria

  - [ ] The spike defines the only acceptable deployment scopes for RIPDPI
        (`controlled server` and, if justified, `CDN-backed controlled property`)
        and rejects arbitrary-site assumptions explicitly.
  - [ ] The spike records go/no-go criteria using the practical indicators from
        [[quicstep-first-flight-hiding]]: migration support, operator-level QUIC
        blocking, and whether the later path can really detach from the censored
        bootstrap path.
  - [ ] The spike decides where this mode would attach in product flow:
        post-`NO_DIRECT_SOLUTION` remediation only, not default transparent mode.
  - [ ] The spike records Android-specific costs: battery, background execution,
        socket lifecycle, and policy interaction with the existing relay stack.
  - [ ] The output ends with one explicit recommendation:
        `do not pursue`, `research-only`, or `promote to implementation epic`.

  ## Notes

  Do not let this reopen the default direct-mode plan. If the answer is
  "interesting but niche", keep it as a parked research branch.

  ## Links

  - [[Epic - Direct-mode transport policy and verdicts]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]
  - [[quicstep-first-flight-hiding]]


## localization-expansion

- [ ] #task Add fa ar de es fr translations and RTL screenshot tests #repo/RIPDPI #area/localization-expansion #status/backlog 🔼 [paperclip:POY-116]
  - Paperclip: POY-116 · assigned to: unassigned
  - Parent: POY-47 (Epic - Localization expansion)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, i18n, rtl
  - **source:** `TaskNotes/Tasks/Add fa ar de es fr translations and RTL screenshot tests.md`
  - **epic:** Epic - Localization expansion

  ## Summary

  Land human-reviewed translations for Persian (fa), Arabic (ar), German
  (de), Spanish (es), and French (fr). Add RTL-variant Roborazzi screenshot
  tests for fa and ar to catch layout regressions.

  ## Context

  Persian and Arabic are RTL and represent the next-largest bypass user
  cohorts after Chinese. German / Spanish / French are coverage locales;
  their volume is lower but their review cost is lowest (native-speaker
  contributors are easier to recruit).

  ## Acceptance criteria

  - [ ] `values-fa/`, `values-ar/`, `values-de/`, `values-es/`,
        `values-fr/` each cover ≥95% of default strings.
  - [ ] Each locale has documented human reviewer sign-off.
  - [ ] Roborazzi RTL screenshot tests for fa and ar on Home, Config,
        Diagnostics, Settings, Onboarding.
  - [ ] RTL padding / chevron / icon-flip regressions, if any, fixed in
        the same PR stack.
  - [ ] Persian and Arabic glyph coverage for the Geist font family is
        verified; fallback is wired where needed.
  - [ ] Weekly string-diff from the pipeline keeps these locales fresh
        without manual polling.

  ## Source references

  **Translation corpora — use as reference only, NOT verbatim copy** (string keys and license headers differ):

  - **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`) — 20 locale directories under `app/src/main/res/`. Relevant paths: `values-fa/`, `values-ar/`, `values-de/`, `values-es/`, `values-fr/`. Use for proxy/protocol terminology reference in each language.
  - **amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`) — 35 locale directories; the richest RTL reference among WireGuard-ecosystem clients. Paths: `ui/src/main/res/values-fa-rIR/`, `values-ar-rSA/`, `values-de/`, `values-es-rES/`, `values-fr/`. RTL layout survey is especially strong here — look at how AWG handles bidirectional text in their `strings.xml` with HTML entities and bidi marks.

  **Adapt (glossary alignment):** Consistent terminology for tunnel/peer/interface across WireGuard-ecosystem clients (AWG baseline); for proxy protocol names, NekoBox is the broader reference. **Skip:** verbatim value copy. **License note:** Both upstreams are Apache 2.0 / GPL-3.0; string-value copies would propagate headers — use as terminology reference only.

  ## Links

  - [[Epic - Localization expansion]]
  - [[Select and set up translation pipeline for RIPDPI]]

- [ ] #task Add zh-CN translation and initial human review #repo/RIPDPI #area/localization-expansion #status/backlog 🔼 [paperclip:POY-153]
  - Paperclip: POY-153 · assigned to: unassigned
  - Parent: POY-47 (Epic - Localization expansion)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, i18n, zh-cn
  - **source:** `TaskNotes/Tasks/Add zh-CN translation and initial human review.md`
  - **epic:** Epic - Localization expansion

  ## Summary

  Land a human-reviewed `values-zh-rCN` translation covering ≥95% of
  `values/` strings. zh-CN is the first wave because the Chinese bypass
  community is the largest non-Russian user cohort.

  ## Context

  MT pre-translation is acceptable as a starting point for the translator
  to work from, but shipping MT-only is not. Screenshot tests cover the
  main screens in zh-CN to catch layout breakage from longer strings.

  ## Acceptance criteria

  - [ ] `app/src/main/res/values-zh-rCN/strings.xml` covers ≥95% of
        default strings; uncovered strings list is tracked in the
        pipeline.
  - [ ] At least one human reviewer sign-off documented in the merge PR.
  - [ ] Roborazzi screenshot tests in zh-CN for: Home, Config,
        Diagnostics, Settings, Onboarding.
  - [ ] No hard-coded strings surface on the reviewed screens (manual
        audit + lint rule).
  - [ ] Glossary terms land in the shared glossary for consistency with
        other future locales.

  ## Source references

  **Translation corpora — use as reference only, NOT verbatim copy** (string keys differ):

  - **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`): `app/src/main/res/values-zh-rCN/strings.xml` — 20+ locale comparison baseline, zh-CN is their largest translation. Useful reference for proxy/protocol term translations (e.g. "订阅" for subscription, "节点" for node, "分流" for routing).
  - **amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`): `ui/src/main/res/values-zh-rCN/strings.xml` — smaller vocabulary but aligned with WireGuard terminology. Reference for tunnel/peer/interface term translations.

  **License note:** both upstreams are Apache 2.0 / GPL-3.0. Do NOT copy string values verbatim without proper attribution — the file headers would propagate. Use as **reference for terminology consistency** only; strings for RIPDPI must be translated independently from its own English source.

  **Adapt (glossary alignment):** Match NekoBox's zh-CN term choices for proxy/protocol vocabulary so subscription-importing users see familiar terminology. **Skip:** verbatim value copy.

  ## Links

  - [[Epic - Localization expansion]]
  - [[Select and set up translation pipeline for RIPDPI]]

- [ ] #task Select and set up translation pipeline for RIPDPI #repo/RIPDPI #area/localization-expansion #status/backlog 🔼 [paperclip:POY-233]
  - Paperclip: POY-233 · assigned to: unassigned
  - Parent: POY-47 (Epic - Localization expansion)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, spike, ripdpi, i18n, pipeline
  - **source:** `TaskNotes/Tasks/Select and set up translation pipeline for RIPDPI.md`
  - **epic:** Epic - Localization expansion

  ## Summary

  Pick and stand up the translation pipeline: evaluate self-hosted Weblate
  vs SaaS Crowdin vs a pure GitHub-PR-based workflow, make the call, and
  land the chosen flow in `docs/` + CI.

  ## Context

  Picking wrong here makes every future locale slower. Bias is toward a
  self-hosted or pure-PR workflow because the project cannot tolerate a
  SaaS service being geofenced or priced-out. Weblate is the default
  candidate; a PR-only flow is the fallback if ops budget is zero.

  ## Acceptance criteria

  - [ ] Decision doc in `docs/localization.md` with: compared options,
        chosen tool, ops cost estimate, contributor instructions,
        escalation plan if the chosen tool disappears.
  - [ ] CI check that exports `values/strings.xml` into the pipeline's
        ingestion format on every main merge.
  - [ ] `translatable="false"` audit complete: any string the translator
        must not touch is marked.
  - [ ] Translator-visible glossary committed (at minimum: protocol
        names, service-mode names, diagnostic verdict names).
  - [ ] README has a "Translate RIPDPI" section pointing at the chosen
        tool.

  ## Links

  - [[Epic - Localization expansion]]


## native-hotspot-decomposition

- [ ] #task Decompose RipDpiProxyJsonCodec #repo/RIPDPI #area/native-hotspot-decomposition #status/backlog 🔼 [paperclip:POY-172]
  - Paperclip: POY-172 · assigned to: unassigned
  - Parent: POY-48 (Epic - Native hotspot decomposition)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, feature, ripdpi, kotlin, refactor
  - **source:** `TaskNotes/Tasks/Decompose RipDpiProxyJsonCodec.md`
  - **epic:** Epic - Native hotspot decomposition

  ## Summary

  `RipDpiProxyJsonCodec.kt` (708 LOC) mixes schema definition, version
  migration, validation, and rewrite concerns.

  ## Audit citation

  - `core/engine/.../RipDpiProxyJsonCodec.kt` — 708 LOC.

  ## Acceptance criteria

  - [ ] Split into: `schema` (field definitions), `migration` (version-to-
        version transforms), `validation` (constraint checks), `rewrite`
        (import/export reshaping).
  - [ ] Public API preserved unless simplification is obvious.
  - [ ] Existing codec tests still pass; new tests cover migration paths
        independently.
  - [ ] `file-loc-baseline.json` updated.

  ## Links

  - [[Epic - Native hotspot decomposition]]
  - [[ripdpi-android-audit-2026-04-20]]

- [ ] #task Decompose desync.rs by responsibility #repo/RIPDPI #area/native-hotspot-decomposition #status/backlog 🔼 [paperclip:POY-173]
  - Paperclip: POY-173 · assigned to: unassigned
  - Parent: POY-48 (Epic - Native hotspot decomposition)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, feature, ripdpi, rust, refactor
  - **source:** `TaskNotes/Tasks/Decompose desync.rs by responsibility.md`
  - **epic:** Epic - Native hotspot decomposition

  ## Summary

  `desync.rs` mixes planning, fallback choice, fake-packet construction,
  TTL-sensitive send logic, and plan execution in 1538 LOC. Split by
  responsibility.

  ## Audit citation

  - `native/rust/crates/ripdpi-runtime/src/runtime/desync.rs` — 1538 LOC,
    function-dense in practice.

  ## Acceptance criteria

  - [ ] `desync.rs` split into: `planner`, `emitters`, `fallback` (classifier),
        `fake_packet` (builders).
  - [ ] Each module has its own unit tests.
  - [ ] No behavior change — existing integration/fuzz tests stay green.
  - [ ] `file-loc-baseline.json` updated to reflect the split.

  ## Notes

  Coordinate with [[Extract native ActionPlan IR]] — the planner module is the
  natural home for the IR.

  ## Links

  - [[Epic - Native hotspot decomposition]]
  - [[Extract native ActionPlan IR]]
  - [[ripdpi-android-audit-2026-04-20]]

- [ ] #task Decompose linux.rs by responsibility #repo/RIPDPI #area/native-hotspot-decomposition #status/backlog 🔼 [paperclip:POY-174]
  - Paperclip: POY-174 · assigned to: unassigned
  - Parent: POY-48 (Epic - Native hotspot decomposition)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, feature, ripdpi, rust, refactor
  - **source:** `TaskNotes/Tasks/Decompose linux.rs by responsibility.md`
  - **epic:** Epic - Native hotspot decomposition

  ## Summary

  `linux.rs` (1557 LOC) mixes socket options, protect logic, raw sends, TCP
  repair, TTL capture, and low-level packet mutation. Split by responsibility.

  ## Audit citation

  - `native/rust/crates/ripdpi-runtime/src/platform/linux.rs` — 1557 LOC.

  ## Acceptance criteria

  - [ ] Split into: `sockopts`, `protect`, `raw_send`, `tcp_repair`.
  - [ ] Each module has scoped unit tests where feasible.
  - [ ] No behavior change — existing tests green.
  - [ ] `file-loc-baseline.json` updated.

  ## Links

  - [[Epic - Native hotspot decomposition]]
  - [[ripdpi-android-audit-2026-04-20]]

- [ ] #task Extract native ActionPlan IR #repo/RIPDPI #area/native-hotspot-decomposition #status/backlog 🔼 [paperclip:POY-192]
  - Paperclip: POY-192 · assigned to: unassigned
  - Parent: POY-48 (Epic - Native hotspot decomposition)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, feature, ripdpi, rust, architecture
  - **source:** `TaskNotes/Tasks/Extract native ActionPlan IR.md`
  - **epic:** Epic - Native hotspot decomposition

  ## Summary

  Introduce a first-class internal `ActionPlan` IR in the Rust runtime so
  planning, emission, and fallback decisions become independently testable
  concerns.

  ## Audit citation

  - Highest-ROI recommendation #3 in [[ripdpi-android-audit-2026-04-20]].

  ## Acceptance criteria

  - [ ] `ActionPlan` type defined with enough fidelity to describe current
        desync / emit flows.
  - [ ] Planner produces an `ActionPlan`; emitter consumes one; fallback
        classifier operates on it.
  - [ ] Round-trip tests for plan → emission on representative scenarios.
  - [ ] At least one existing use-site migrated to the IR as a pilot; others
        can follow incrementally.

  ## Notes

  Decide IR shape in a spike before committing to a public surface. Keep the
  IR internal to the Rust runtime initially — no JNI exposure required.

  ## Links

  - [[Epic - Native hotspot decomposition]]
  - [[Decompose desync.rs by responsibility]]
  - [[ripdpi-android-audit-2026-04-20]]


## nekobox-subscription-and-profile

- [ ] #task Add Clash and Clash.Meta YAML subscription parser #repo/RIPDPI #area/nekobox-subscription-and-profile #status/backlog 🔺 [paperclip:POY-69]
  - Paperclip: POY-69 · assigned to: unassigned
  - Parent: POY-49 (Epic - NekoBox subscription and profile import)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, subscriptions, parser
  - **source:** `TaskNotes/Tasks/Add Clash and Clash.Meta YAML subscription parser.md`
  - **epic:** Epic - NekoBox subscription and profile import

  ## Summary

  Parse `proxies:` arrays from Clash and Clash.Meta YAML subscriptions into
  RIPDPI profile beans.

  ## Context

  Clash YAML is the most common subscription format in Chinese and Iranian
  bypass ecosystems. Clash.Meta adds reality-opts, smux, and ech-opts on top.
  NekoBox's `RawUpdater.kt` handles: socks5, http, ss (with obfs and v2ray-
  plugin), vmess, vless (with reality-opts), trojan, anytls, hysteria,
  hysteria2, tuic. Routing rules in the YAML are ignored — only node lists.

  ## Acceptance criteria

  - [ ] Detect Clash YAML by presence of `proxies:` top-level key.
  - [ ] Map Clash proxy types to RIPDPI profile beans for: socks5, http, ss,
        vmess, vless (with reality-opts, smux), trojan (with ech-opts),
        anytls, hysteria, hysteria2, tuic.
  - [ ] Unknown fields are ignored, not hard-errored.
  - [ ] Parser is streaming (SnakeYAML event-based) to handle 500+ node
        payloads without loading the whole document into memory.
  - [ ] Parse failures surface as typed `SubscriptionParseError` with the
        failing node index, not a fatal stack trace.
  - [ ] Unit tests cover a realistic sample bank for each listed protocol,
        plus malformed/partial inputs.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt` — method `parseRaw(text: String)`. The Clash branch is guarded by `text.contains("proxies:")`. Inside, every `proxies:` array entry is dispatched by `type` (`ss`, `vmess`, `vless`, `trojan`, `anytls`, `hysteria`, `hysteria2`, `tuic`, `socks5`, `http`). Port this switch verbatim; replace each branch's bean construction with the RIPDPI equivalent.
  - Per-protocol Clash field mappings: same file, inline within each branch. Handle known quirks:
    - `reality-opts` (public-key, short-id, spider-x) → VLESS-Reality fields
    - `smux` (v1/v2, max-streams, max-connections) → mux composition (blocked on [[Epic - Composable transport layer parity]])
    - `ech-opts` → ECH fields (RIPDPI already has these)
    - `ws-opts` (path, headers, early-data) → WebSocket transport ([[Generalize WebSocket transport for outbound composition]])

  **Adapt:** The detection string, switch dispatch, per-field mapping. **Skip:** Clash routing rules (`rules:`, `proxy-groups:` blocks) — NekoBox ignores them too. Use `snakeyaml-engine` (Kotlin-friendly) or event-based `snakeyaml` for streaming; NekoBox uses TypeDescription-driven SnakeYAML which is heavier than needed.

  ## Links

  - [[Epic - NekoBox subscription and profile import]]
  - [[Add ProxyGroup and Subscription entities to RIPDPI data layer]]

- [ ] #task Add ProxyGroup and Subscription entities to RIPDPI data layer #repo/RIPDPI #area/nekobox-subscription-and-profile #status/backlog 🔺 [paperclip:POY-82]
  - Paperclip: POY-82 · assigned to: unassigned
  - Parent: POY-49 (Epic - NekoBox subscription and profile import)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, data, subscriptions
  - **source:** `TaskNotes/Tasks/Add ProxyGroup and Subscription entities to RIPDPI data layer.md`
  - **epic:** Epic - NekoBox subscription and profile import

  ## Summary

  Add a ProxyGroup abstraction (Basic + Subscription types) and a
  SubscriptionBean child record so profiles can be organized, fetched, and
  auto-refreshed from a subscription URL.

  ## Context

  RIPDPI's current data layer has user relays and operator-shipped packs, but
  no user-owned "group" that can hold dynamic subscription-sourced profiles.
  This entity is the prerequisite for every other task in
  [[Epic - NekoBox subscription and profile import]].

  ## Acceptance criteria

  - [ ] `ProxyGroup` Protobuf message + Room projection with fields: id, name,
        type (`BASIC` | `SUBSCRIPTION`), order, isSelector,
        optional `Subscription` child.
  - [ ] `Subscription` record with link, token, customUserAgent, autoUpdate,
        autoUpdateDelay, lastUpdated, updateWhenConnectedOnly, forceResolve,
        deduplication, subscriptionUserinfo, bytesUsed, bytesRemaining,
        expiryDate.
  - [ ] Repository exposes add / update / delete / list flows and emits
        Kotlin `Flow` for UI binding.
  - [ ] Existing user-relay data migrates cleanly into an ungrouped "default"
        group; no data loss.
  - [ ] Schema is versioned; one forward migration is wired up under
        `core/diagnostics-data` or a new `core/profiles-data` module if the
        separation is cleaner.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`) — these files are the template for the schema shape:

  - `app/src/main/java/io/nekohasekai/sagernet/database/ProxyGroup.kt` — `@Entity` fields: `id`, `userOrder`, `ungrouped`, `name`, `type` (`BASIC`/`SUBSCRIPTION`), `subscription` (embedded), `order`, `isSelector`, `frontProxy`, `landingProxy`. Port field-for-field but map to Protobuf DataStore or Room per RIPDPI's existing pattern (`DiagnosticsDatabase`).
  - `app/src/main/java/io/nekohasekai/sagernet/database/SubscriptionBean.java` — field set: `link`, `token`, `customUserAgent`, `autoUpdate`, `autoUpdateDelay`, `lastUpdated`, `updateWhenConnectedOnly`, `forceResolve`, `deduplication`, `subscriptionUserinfo`, `bytesUsed`, `bytesRemaining`, `expiryDate`. Port verbatim.
  - `app/src/main/java/io/nekohasekai/sagernet/database/ProxyEntity.kt` — the flat-bean-per-protocol pattern NekoBox uses (one nullable column per protocol). **Do NOT copy** this pattern; RIPDPI should use a discriminated union (Protobuf `oneof` or Kotlin sealed class) since the ProxyEntity bean-per-column layout is legacy.
  - `app/src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt` — Room database wiring for reference; RIPDPI already has its own DB conventions.

  **Adapt:** Field set, semantics. **Skip:** Kryo serialization (RIPDPI uses Protobuf), the bean-per-column ProxyEntity layout, `frontProxy`/`landingProxy` (proxy-chaining excluded per project note).

  ## Links

  - [[Epic - NekoBox subscription and profile import]]
  - [[ripdpi-android]]

- [ ] #task Add WireGuard INI subscription parser #repo/RIPDPI #area/nekobox-subscription-and-profile #status/backlog ⏫ [paperclip:POY-96]
  - Paperclip: POY-96 · assigned to: unassigned
  - Parent: POY-49 (Epic - NekoBox subscription and profile import)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, subscriptions, parser, wireguard
  - **source:** `TaskNotes/Tasks/Add WireGuard INI subscription parser.md`
  - **epic:** Epic - NekoBox subscription and profile import

  ## Summary

  Parse standard `.conf`-style WireGuard INI payloads (multi-peer supported)
  into one WireGuard profile per peer.

  ## Context

  Subscription providers sometimes distribute WireGuard nodes as raw INI,
  including WARP-compatible layouts. Detection marker is `[Interface]`
  presence. Multiple `[Peer]` sections produce multiple profiles sharing the
  interface key material; surface them clearly in the populated group.

  ## Acceptance criteria

  - [ ] Detect INI via `[Interface]` header presence.
  - [ ] Parse `[Interface]` (PrivateKey, Address, DNS, MTU) and each `[Peer]`
        (PublicKey, AllowedIPs, Endpoint, PresharedKey, PersistentKeepalive).
  - [ ] Produce one WireGuard profile per peer, sharing the interface
        keypair and distinguishing by peer endpoint in display name.
  - [ ] Preserve `AllowedIPs` as per-profile routing hint even if the
        runtime currently ignores it; keep for future routing epic.
  - [ ] Malformed INI surfaces a typed error; per-peer failures degrade to
        "skip and warn", not full subscription rejection.
  - [ ] Unit tests cover: single-peer, multi-peer, WARP-style config, DNS
        field present and absent, IPv4-only and dual-stack AllowedIPs.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt` — method `parseWireGuard(text)`. Detection: `text.contains("[Interface]")`. Uses `org.ini4j.Ini` to parse.
  - `app/src/main/java/io/nekohasekai/sagernet/fmt/wireguard/` — the `WireGuardBean` field set that receives parsed values.

  **amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`) — the AWG-extended INI parser is the definitive reference for Jc/Jmin/Jmax/S1-S4/H1-H4/I1-I5 key handling:

  - `tunnel/src/main/java/org/amnezia/awg/config/Config.java` (`parse(InputStream)` starting line 50) — section dispatch on `[Interface]` / `[Peer]`.
  - `tunnel/src/main/java/org/amnezia/awg/config/Interface.java:101-184` — the per-key `switch` that parses every AWG extension key. **Port this switch verbatim** for the [[Wire AmneziaWG into the subscription WireGuard-INI parser]] follow-on task.

  **Adapt:** Detection marker, per-section header handling, per-peer profile emission. **Skip:** NekoBox's `ini4j` dependency if RIPDPI already has an INI parser; otherwise add it. Use `ini4j` 0.5.4 (same version NekoBox pins) for parity.

  ## Links

  - [[Epic - NekoBox subscription and profile import]]

- [ ] #task Add base64 and plain URI-list subscription parser #repo/RIPDPI #area/nekobox-subscription-and-profile #status/backlog 🔺 [paperclip:POY-102]
  - Paperclip: POY-102 · assigned to: unassigned
  - Parent: POY-49 (Epic - NekoBox subscription and profile import)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, subscriptions, parser
  - **source:** `TaskNotes/Tasks/Add base64 and plain URI-list subscription parser.md`
  - **epic:** Epic - NekoBox subscription and profile import

  ## Summary

  Parse subscription payloads that are either base64-encoded newline-delimited
  proxy URIs, or already-decoded plain URI lists.

  ## Context

  This is the fallback path when the payload is not YAML, not JSON, and not
  INI. NekoBox attempts base64 URL-safe decode first, then plain text. Per-
  URI parsing uses the same standard URI codec that profile share links use,
  so this task coexists with per-protocol URI codecs.

  ## Acceptance criteria

  - [ ] Attempt URL-safe base64 decode; on failure, fall through to plain
        text parsing.
  - [ ] Split by any of `
  `, `
  `, `
  `; trim whitespace per line; skip
        empty lines and comment lines starting with `#`.
  - [ ] Per-URI parse via the shared codec; unknown schemes emit a typed
        warning and skip that line.
  - [ ] Parser is streaming line-by-line.
  - [ ] Unit tests cover: pure base64, plain text, mixed (some base64-decoded
        lines accidentally re-encoded), invalid URIs, whitespace-only lines.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt` — method `parseProxies()`. Tries URL-safe base64 decode first (`Base64.decode(text, URL_SAFE)`); on failure falls through to plain-text line split.
  - `app/src/main/java/io/nekohasekai/sagernet/ktx/Network.kt` — `decodeBase64UrlSafe()` helper with padding-tolerant fallback.
  - `app/src/main/java/io/nekohasekai/sagernet/fmt/KryoConverters.kt` and the per-protocol `*Fmt.kt` files (`ShadowsocksFmt.kt`, `TrojanFmt.kt`, `HysteriaFmt.kt`, `TuicFmt.kt`, `V2RayFmt.kt`, etc.) — each has a `parseXxx(url: String)` function that is the per-URI-scheme codec. **These are the single most important set of files to port.**

  **Adapt:** The base64-then-fallback detection, per-line trimming, comment-line skip. **Skip:** NekoBox's Kryo serialization round-trip — the URI codec should go directly to the Protobuf profile bean.

  ## Links

  - [[Epic - NekoBox subscription and profile import]]
  - [[Add share-sheet handler for proxy URI schemes]]

- [ ] #task Add duplicate-profile detection on subscription merge #repo/RIPDPI #area/nekobox-subscription-and-profile #status/backlog ⏫ [paperclip:POY-113]
  - Paperclip: POY-113 · assigned to: unassigned
  - Parent: POY-49 (Epic - NekoBox subscription and profile import)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, subscriptions
  - **source:** `TaskNotes/Tasks/Add duplicate-profile detection on subscription merge.md`
  - **epic:** Epic - NekoBox subscription and profile import

  ## Summary

  On subscription refresh, detect and collapse profiles that are byte-equal
  except for display name, so periodic re-fetch does not duplicate the group.

  ## Context

  NekoBox uses Kryo binary equality (ignoring `name`) to drive dedup. RIPDPI
  needs an equivalent: a canonical byte serialization of the profile bean
  followed by SHA-256 and compare-set. User-edited display names should
  survive refresh; adversary-crafted collisions are out of scope (the
  attacker already controls the subscription content).

  ## Acceptance criteria

  - [ ] Canonical serializer produces a stable byte string for each protocol
        bean, ignoring `name` and any `finalAddress` runtime-only fields.
  - [ ] Dedup hash column exists on `ProxyEntity` and is reindexed on every
        save.
  - [ ] On subscription merge, incoming profiles hash-matching an existing
        profile inherit the incoming config but preserve the existing name
        and the user-edited `customOutboundJson` / `customConfigJson`.
  - [ ] Unit tests cover: rename-only change (no-op), server-IP change
        (replace), UUID change (replace), new-profile (insert), missing-
        profile (delete).
  - [ ] Dedup toggle on the group controls this behavior; off by default to
        match user expectation on first use.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt` — the `doUpdate()` merge pass (`existingByName`, `existingBean.equals(newBean)` calls). Read the delete/add/update/reorder reconciliation flow; port the structure, replace Kryo-equality with a canonical Protobuf encoding + SHA-256.
  - `app/src/main/java/io/nekohasekai/sagernet/fmt/AbstractBean.java` — `equals()` ignores `name` and `finalAddress`/`finalPort`. Mirror that invariant in the canonical serializer: exclude display name and transient resolved-address fields before hashing.

  **Adapt:** The merge algorithm (preserve user-edited `customOutboundJson`/`customConfigJson` across refresh). **Skip:** Kryo-dependent equality — use stable Protobuf bytes + SHA-256 instead since RIPDPI does not ship Kryo.

  ## Links

  - [[Epic - NekoBox subscription and profile import]]
  - [[Add ProxyGroup and Subscription entities to RIPDPI data layer]]

- [ ] #task Add force-resolve DNS and Subscription-Userinfo handling #repo/RIPDPI #area/nekobox-subscription-and-profile #status/backlog 🔼 [paperclip:POY-118]
  - Paperclip: POY-118 · assigned to: unassigned
  - Parent: POY-49 (Epic - NekoBox subscription and profile import)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, subscriptions, dns
  - **source:** `TaskNotes/Tasks/Add force-resolve DNS and Subscription-Userinfo handling.md`
  - **epic:** Epic - NekoBox subscription and profile import

  ## Summary

  Two small but useful subscription refinements: (a) optional force-resolve
  of server hostnames to IPs at refresh time, using a bounded-concurrency
  DNS pool; (b) parse the `Subscription-Userinfo` response header and surface
  upload/download/quota/expiry in the group detail screen.

  ## Context

  Force-resolve is a NekoBox feature that pre-resolves hostnames to avoid
  relying on the runtime DNS path for nodes whose DNS is flaky. The existing
  `hickory-resolver` + DoH stack in RIPDPI can back this. The user-info
  header (format:
  `upload=…; download=…; total=…; expire=…`) is standard in most commercial
  bypass subscriptions.

  ## Acceptance criteria

  - [ ] Per-group toggle "Force resolve on update" (default off).
  - [ ] When on, refresh DNS-resolves each profile's `serverAddress` with
        up to 5 parallel lookups; rewrite both `serverAddress` and SNI-ish
        fields for V2Ray/Trojan/Hysteria beans.
  - [ ] `Subscription-Userinfo` response header is parsed into typed
        fields; malformed values become `null`, not thrown exceptions.
  - [ ] Group detail screen surfaces upload/download/total/expire in
        localized, redaction-aware format.
  - [ ] Expired subscription surfaces a warning banner; refresh still
        proceeds to inform user-driven action.
  - [ ] Unit tests cover header parsing variants, malformed headers, and
        IPv4/IPv6/dual-resolve outcomes.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/group/GroupUpdater.kt` — method `forceResolve()`. 5-thread pool via `Executors.newFixedThreadPool(5)`, per-bean resolve + SNI-field rewrite for HTTP/V2Ray/Trojan/Hysteria. Port the thread-pool pattern; replace the Java executor with Kotlin coroutines bounded by a `Semaphore(5)`.
  - `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt` — `Subscription-Userinfo` header read path (look for `response.headers["Subscription-Userinfo"]`), value format is semicolon-separated `upload=N; download=N; total=N; expire=UNIX_TS`.
  - `app/src/main/java/io/nekohasekai/sagernet/database/SubscriptionBean.java` — fields `bytesUsed`, `bytesRemaining`, `expiryDate` are populated from the header parse.

  **Adapt:** Parallel resolve with bounded concurrency, SNI-field rewrite set, Userinfo header parse. **Skip:** Java `ExecutorService` (use coroutines). **Parser robustness:** NekoBox hard-fails on missing fields; RIPDPI should treat each numeric field as `Long?` so providers that only emit `expire=` don't break the refresh.

  ## Links

  - [[Epic - NekoBox subscription and profile import]]
  - [[Add subscription auto-update WorkManager worker]]

- [ ] #task Add selector outbound runtime for group-based profile switching #repo/RIPDPI #area/nekobox-subscription-and-profile #status/backlog 🔼 [paperclip:POY-140]
  - Paperclip: POY-140 · assigned to: unassigned
  - Parent: POY-49 (Epic - NekoBox subscription and profile import)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, routing, selector
  - **source:** `TaskNotes/Tasks/Add selector outbound runtime for group-based profile switching.md`
  - **epic:** Epic - NekoBox subscription and profile import

  ## Summary

  Implement the runtime side of ProxyGroup's `isSelector` flag: when a
  group is a selector, the user can hot-switch which member profile is
  active without tearing down the service. Matches NekoBox's sing-box
  selector outbound + SwitchActivity pattern.

  ## Context

  The ProxyGroup entity task introduces the `isSelector` field. This task
  owns the runtime: exposing a selected-profile signal, wiring it into the
  relay supervisor's reload path (using the existing hot-reload semantics),
  and surfacing a quick-switch entry in the persistent service
  notification. URL test inside the group feeds the picker with latency
  hints but does not auto-switch — that is a future "auto-select" feature.

  ## Acceptance criteria

  - [ ] Selector groups expose a `selectedProfileId: Flow<Long>` at the
        repository layer.
  - [ ] Changing the selected profile while the service is running
        triggers a hot reload; no full service tear-down.
  - [ ] Persistent service notification gains a "Switch" action that
        opens a dialog-style Activity listing the group's profiles with
        latency + current selection marker.
  - [ ] Quick Settings tile subtitle updates to the new profile name on
        switch.
  - [ ] If the last-active profile on disk is a selector group, on
        service restart the last-selected member resumes, not the first.
  - [ ] Non-selector groups render as plain lists; no extra UI drift.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/ui/SwitchActivity.kt` — the dialog-style transparent activity launched from the persistent notification's "Switch" action. Shows group member list with latency hints; tap triggers supervisor reload with new selectedProfile. **Port the UX pattern.**
  - `app/src/main/java/io/nekohasekai/sagernet/bg/proto/ProxyInstance.kt` — hot-reload pathway when selectedProfile changes within a selector group. Search for `selectorGroupId` and `cbSelectorUpdate`.
  - `app/src/main/java/io/nekohasekai/sagernet/bg/TileService.kt` — QS tile subtitle updates via `cbSelectorUpdate` callback.
  - `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt` — sing-box `selector` outbound generation (search for `"selector"` as `type`). Reference only — RIPDPI doesn't emit sing-box config.

  **Adapt:** Notification "Switch" action, dialog profile list with latency hints, hot-reload via supervisor reload path (no full teardown), QS tile subtitle update. **Skip:** sing-box selector outbound JSON generation.

  ## Links

  - [[Epic - NekoBox subscription and profile import]]
  - [[Add ProxyGroup and Subscription entities to RIPDPI data layer]]

- [ ] #task Add sing-box JSON subscription parser #repo/RIPDPI #area/nekobox-subscription-and-profile #status/backlog 🔺 [paperclip:POY-145]
  - Paperclip: POY-145 · assigned to: unassigned
  - Parent: POY-49 (Epic - NekoBox subscription and profile import)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, subscriptions, parser
  - **source:** `TaskNotes/Tasks/Add sing-box JSON subscription parser.md`
  - **epic:** Epic - NekoBox subscription and profile import

  ## Summary

  Parse sing-box JSON subscriptions — both a bare `outbounds:` array and a
  single-outbound config — into RIPDPI profile beans.

  ## Context

  sing-box JSON is the canonical subscription format for the modern bypass
  stack (Xray, sing-box, Clash.Meta upstream ecosystem). Per NekoBox's
  `RawUpdater.parseJSON`: detects JSON via JSONTokener, then inspects top-
  level keys. Outbound-array entries become profiles; non-shadowsocks/trojan/
  hysteria entries that cannot be mapped to a native bean fall back to
  `ConfigBean` (raw JSON fragment).

  ## Acceptance criteria

  - [ ] Detect JSON via a permissive tokener; reject only on throw.
  - [ ] Route on top-level shape: `outbounds:` array → iterate; single
        outbound object → wrap as one-element array; Hysteria1 config shape;
        Shadowsocks config shape.
  - [ ] Map known outbound `type:` values to RIPDPI beans (VMess, VLESS,
        Trojan, Shadowsocks, Hysteria, Hysteria2, TUIC, WireGuard, AnyTLS,
        ShadowTLS, SSH).
  - [ ] Unknown outbound types round-trip as `ConfigBean` holding the raw
        JSON fragment, consumable by the Rust engine via custom-config path.
  - [ ] Malformed JSON surfaces as typed error with line/column pointer.
  - [ ] Unit tests cover each mapping plus fall-through to `ConfigBean`.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt` — method `parseJSON()`. Detection: `JSONTokener(text).nextValue()` returns a `JSONObject` or `JSONArray`. Dispatch on top-level shape: `outbounds:` array (iterate), single outbound object (wrap), Hysteria1 single-config shape, Shadowsocks single-config shape, TrojanGo single-config shape.
  - `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt` — the reverse mapping (ProxyEntity → sing-box outbound JSON) is instructive for understanding which sing-box `type:` values map to which beans.

  **Adapt:** The shape-detection dispatch, fall-through-to-ConfigBean for unknown types. **Skip:** sing-box `inbounds`, `route`, `dns`, `experimental` sections (we only want outbounds). Use `kotlinx.serialization` with a permissive JSON config (`ignoreUnknownKeys = true`, `isLenient = true`); NekoBox uses `org.json.JSONObject` which is slower and weaker-typed.

  ## Links

  - [[Epic - NekoBox subscription and profile import]]

- [ ] #task Add subscription auto-update WorkManager worker #repo/RIPDPI #area/nekobox-subscription-and-profile #status/backlog ⏫ [paperclip:POY-149]
  - Paperclip: POY-149 · assigned to: unassigned
  - Parent: POY-49 (Epic - NekoBox subscription and profile import)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, subscriptions, workmanager
  - **source:** `TaskNotes/Tasks/Add subscription auto-update WorkManager worker.md`
  - **epic:** Epic - NekoBox subscription and profile import

  ## Summary

  Schedule a WorkManager PeriodicWorkRequest that refreshes every auto-update
  subscription at its configured cadence (min 15 min), gated by the "update
  when connected only" group toggle.

  ## Context

  NekoBox clamps the WorkManager interval to the shortest configured
  `autoUpdateDelay` across all auto-updating groups. The worker runs in the
  `:bg` service process via `work-multiprocess` so it shares lifecycle with
  the tunnel supervisor. On boot, the boot receiver re-triggers schedule
  reconciliation.

  ## Acceptance criteria

  - [ ] PeriodicWorkRequest is registered via WorkManager with the shortest
        applicable interval (>= 15 min).
  - [ ] Worker skips an entry if its `updateWhenConnectedOnly` is true and
        the VPN/proxy is not currently connected.
  - [ ] Worker posts a foreground-notification during the refresh window
        via the `service-subscription` channel.
  - [ ] Refresh reuses the HTTP client from `ripdpi-runtime` so in-proxy
        fetch works when the tunnel is up.
  - [ ] Rate-limit: a manual refresh and an auto refresh for the same
        group within 30 s collapse into one network round-trip.
  - [ ] Failure classification: network error, auth error, parse error —
        each with typed telemetry, not a generic "failed" toast.
  - [ ] Boot receiver [[Add boot-completed receiver with dynamic enable]]
        re-registers the schedule on device boot.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/bg/SubscriptionUpdater.kt` — full reference: scheduling via `RemoteWorkManager` (multiprocess), `UpdateTask` as `CoroutineWorker`, min-interval clamp (15 min), shortest-auto-update-delay across all groups, `updateWhenConnectedOnly` gating, `cancelUniqueWork` on reconfigure.
  - `app/src/main/java/io/nekohasekai/sagernet/group/GroupUpdater.kt` — `doUpdate()` orchestration: `updating` lock set, `userInterface.onUpdateSuccess()` callback, error-type classification (network/auth/parse).
  - `app/src/main/AndroidManifest.xml` — `androidx.work:work-multiprocess` service declaration; worker runs in the `:bg` process.

  **Adapt:** The multiprocess WorkManager pattern, the shortest-interval-clamp scheduling, typed error telemetry categories. **Skip:** NekoBox's in-proxy HTTP fetch via `Libcore.newHttpClient()` — RIPDPI should use its own in-tunnel HTTP client when the tunnel is up (falls back to direct when not), which is architecturally cleaner than NekoBox's one-path approach.

  ## Links

  - [[Epic - NekoBox subscription and profile import]]
  - [[Epic - Boot autostart and session persistence]]


## orchestration-test-posture

- [ ] #task Add repeated startup-shutdown supervisor test #repo/RIPDPI #area/orchestration-test-posture #status/backlog 🔼 [paperclip:POY-138]
  - Paperclip: POY-138 · assigned to: unassigned
  - Parent: POY-50 (Epic - Orchestration test posture)
  - Blocked by: POY-129
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, feature, ripdpi, testing, lifecycle
  - **source:** `TaskNotes/Tasks/Add repeated startup-shutdown supervisor test.md`
  - **epic:** Epic - Orchestration test posture

  ## Summary

  Regression test that hammers each supervisor with rapid start/stop cycles
  and scripted exit causes. Backs the explicit-exit-cause fix.

  ## Acceptance criteria

  - [ ] For each supervisor (`ProxyRuntimeSupervisor`,
        `UpstreamRelaySupervisor`, `WarpRuntimeSupervisor`): rapid start/stop
        cycles leave no leaked coroutines, threads, or file descriptors.
  - [ ] Scripted exit cause produces the correct `ExitCause` variant.
  - [ ] Expected-stop vs crash disambiguation verified without relying on the
        caller's `stopping` flag.

  ## Links

  - [[Epic - Orchestration test posture]]
  - [[Add explicit supervisor exit cause types]]
  - [[Add orchestration failure-injection harness]]
  - [[ripdpi-android-audit-2026-04-20]]

- [ ] #task Spike CensorLab as offline censor-replay harness #repo/RIPDPI #area/orchestration-test-posture #status/backlog 🔼 [paperclip:POY-239]
  - Paperclip: POY-239 · assigned to: unassigned
  - Parent: POY-50 (Epic - Orchestration test posture)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, spike, ripdpi, testing, offline
  - **source:** `TaskNotes/Tasks/Spike CensorLab as offline censor-replay harness.md`
  - **epic:** Epic - Orchestration test posture

  ## Summary

  Build CensorLab locally, replay a TSPU-like scenario against RIPDPI's
  direct-mode arms, and decide whether to adopt, fork, or reject it as an
  offline censor-replay harness for the orchestration test posture.

  ## Research citation

  [[ripdpi-android-research-2026-04-20]] §Academic papers — CensorLab
  (arxiv 2412.16349) is a testbed for replaying censor strategies against
  bypass tools. Having an offline replay that exercises our six arms
  without a real TSPU egress reduces regression risk on every release.

  ## Acceptance criteria

  - [ ] CensorLab built locally and documented (OS, deps, gotchas).
  - [ ] One TSPU-like scenario replayed against at least two named arms
        with captured verdicts.
  - [ ] Verdict on coverage: does it exercise all six transparent-mode
        arms plus the DoH/DoQ classifier, or is it partial.
  - [ ] Decision recorded on adopt / fork / reject with the next concrete
        action (integrate into CI, fork and extend, or drop).

  ## Links

  - [[Epic - Orchestration test posture]]
  - [[Add orchestration failure-injection harness]]
  - [[Build CensorLab-style offline strategy-pack pipeline]]
  - [[ripdpi-android-research-2026-04-20]]


## owned-stack-mode-with

- [ ] #task Adopt Android 17 NetworkSecurityConfig domainEncryption for per-domain ECH policy #repo/RIPDPI #area/owned-stack-mode-with #status/backlog ⏫ [paperclip:POY-154]
  - Paperclip: POY-154 · assigned to: unassigned
  - Parent: POY-51 (Epic - Owned-stack mode with Android 17 ECH)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-25
  - **dateModified:** 2026-04-25
  - **area:** android
  - **tags:** task, feature, ripdpi, android-17, ech, network-security-config
  - **source:** `TaskNotes/Tasks/Adopt Android 17 NetworkSecurityConfig domainEncryption for per-domain ECH policy.md`
  - **epic:** Epic - Owned-stack mode with Android 17 ECH

  ## Summary

  Wire RIPDPI's NSC (NetworkSecurityConfig) generator and control-plane to emit `<domainEncryption>` per-domain modes (`enabled` / `disabled` / `opportunistic`) per Android 17 (API 37). Couple this to the DnsResolver path that queries HTTPS DNS records carrying ECH configs, so owned-stack endpoints get hard-on ECH while everything else stays opportunistic.

  ## Research citation

  [[ripdpi-android-research-2026-04-25]] §Android platform — Android 17 (API 37, behavior-changes-17, 2026-02-13) opportunistically enables ECH on TLS 1.3 by default; new `<domainEncryption>` NSC element accepts `enabled` / `disabled` / `opportunistic`; `DnsResolver` now queries HTTPS DNS records with ECH configs; Conscrypt `SSLEngine` gains explicit ECH-enable APIs.

  ## Acceptance criteria

  - [ ] NSC schema generator emits `<domainEncryption>` with `mode="enabled"` for Reality and owned-stack endpoints, `opportunistic` for everything else
  - [ ] Control-plane can override per-domain mode (`enabled` / `disabled` / `opportunistic`) via strategy pack
  - [ ] DnsResolver wired to query HTTPS DNS records for ECH config when `mode != disabled`
  - [ ] Integration test on Android 17 emulator confirms ECH enabled on TLS 1.3 to a Reality endpoint and disabled on a misconfigured one

  ## Links

  - Project: [[ripdpi-android]]
  - Epic: [[Epic - Owned-stack mode with Android 17 ECH]]
  - Research: [[ripdpi-android-research-2026-04-25]] §Android platform

- [ ] #task Pin RFC 9849 wording in owned-stack epic and host-pack schema #repo/RIPDPI #area/owned-stack-mode-with #status/backlog 🔼 [paperclip:POY-220]
  - Paperclip: POY-220 · assigned to: unassigned
  - Parent: POY-51 (Epic - Owned-stack mode with Android 17 ECH)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, chore, ripdpi, ech, standards
  - **source:** `TaskNotes/Tasks/Pin RFC 9849 wording in owned-stack epic and host-pack schema.md`
  - **epic:** Epic - Owned-stack mode with Android 17 ECH

  ## Summary

  Replace draft-ietf-tls-esni-25 references with RFC 9849 across the owned-
  stack epic and the host-pack schema, and verify Conscrypt ECH API names
  against the stable RFC vocabulary.

  ## Research citation

  [[ripdpi-android-research-2026-04-20]] §Standards and protocol activity —
  RFC 9849 was ratified in 2026; existing RIPDPI documents still cite the
  draft. Bumping the reference prevents future schema reviewers from
  chasing a superseded draft.

  ## Acceptance criteria

  - [ ] Epic body and host-pack schema reference RFC 9849, not
        draft-ietf-tls-esni-25.
  - [ ] Conscrypt ECH API names in code comments and docs verified against
        the stable RFC vocabulary.
  - [ ] Decision-block citation list on [[Epic - Owned-stack mode with Android 17 ECH]]
        updated accordingly.

  ## Links

  - [[Epic - Owned-stack mode with Android 17 ECH]]
  - [[Parse HTTPS SVCB records with ECH config metadata]]
  - [[ripdpi-android-research-2026-04-20]]

- [ ] #task Snapshot owned-stack JA4 fingerprint in release CI #repo/RIPDPI #area/owned-stack-mode-with #status/backlog 🔼 [paperclip:POY-236]
  - Paperclip: POY-236 · assigned to: unassigned
  - Parent: POY-51 (Epic - Owned-stack mode with Android 17 ECH)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, chore, ripdpi, ci, tls, fingerprint
  - **source:** `TaskNotes/Tasks/Snapshot owned-stack JA4 fingerprint in release CI.md`
  - **epic:** Epic - Owned-stack mode with Android 17 ECH

  ## Summary

  Add a release-time CI step that records owned-stack outbound JA4 against
  a fixture endpoint and fails the build on drift from the intended
  browser-class spec, including explicit assertion of `X25519MLKEM768`
  presence in the key-share list.

  ## Research citation

  [[ripdpi-android-research-2026-04-20]] §TLS fingerprinting tooling — by
  early 2026 post-quantum `X25519MLKEM768` is in 57.4% of browser
  ClientHellos, so its *absence* is now a fingerprintable anomaly. JA4+
  rotates roughly yearly with TLS-library updates; a drift gate catches
  Conscrypt or OEM TLS changes before they ship.

  ## Acceptance criteria

  - [ ] CI step captures owned-stack outbound JA4 against a pinned fixture
        endpoint on every release build.
  - [ ] Expected JA4 baseline committed to the repo; build fails on drift.
  - [ ] Assertion explicitly verifies `X25519MLKEM768` is present in the
        ClientHello key-share list.
  - [ ] Runbook documents how to update the baseline when Conscrypt
        intentionally rotates browser-class fingerprint.

  ## Links

  - [[Epic - Owned-stack mode with Android 17 ECH]]
  - [[Implement owned-stack request pipeline]]
  - [[ripdpi-android-research-2026-04-20]]

- [ ] #task Spike ECH end-to-end on Android 17 Beta 4 #repo/RIPDPI #area/owned-stack-mode-with #status/backlog ⏫ [paperclip:POY-240]
  - Paperclip: POY-240 · assigned to: unassigned
  - Parent: POY-51 (Epic - Owned-stack mode with Android 17 ECH)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-22
  - **area:** android
  - **tags:** task, spike, ripdpi, ech, android17
  - **source:** `TaskNotes/Tasks/Spike ECH end-to-end on Android 17 Beta 4.md`
  - **epic:** Epic - Owned-stack mode with Android 17 ECH

  ## Summary

  Validate the full platform-ECH happy path on Android 17 Beta 4: query an
  HTTPS/SVCB record that carries an ECHConfig, feed it to Conscrypt, and
  complete a TLS handshake against a known ECH-capable host.

  ## Research citation

  [[ripdpi-android-research-2026-04-20]] §Android platform — Android 17
  Beta 4 (April 2026) exposes `DnsResolver` HTTPS-RR queries with ECH and
  new Conscrypt `SSLEngine`/`SSLSocket` ECH knobs. This is the platform
  path owned-stack mode depends on; verify it works before deeper design.

  ## Acceptance criteria

  - [ ] `DnsResolver` HTTPS-RR query returns a parseable ECHConfig on
        Beta 4 for at least one known ECH-capable host.
  - [ ] Conscrypt `SSLEngine` / `SSLSocket` completes a handshake using
        that ECHConfig (ClientHelloInner encrypted, ClientHelloOuter
        innocuous).
  - [ ] Spike note records: emulator/device matrix, flaky paths, pre-stable
        API caveats, and any deltas from the documented surface.
  - [ ] Spike note records whether successful ECH changes only metadata
        privacy / owned-stack reachability, or actually changes the practical
        bypass verdict on the tested host class.
  - [ ] Spike note records the DNS dependency explicitly: which resolver path
        and `HTTPS/SVCB` bootstrap were required before ECH could even be tried.

  ## Links

  - [[Epic - Owned-stack mode with Android 17 ECH]]
  - [[Parse HTTPS SVCB records with ECH config metadata]]
  - [[Document Android 17 ECH requirement and graceful degradation]]
  - [[ripdpi-android-research-2026-04-20]]
  - [[ech-practical-censorship-value-2026]]


## privacy-preserving-strategy-learner

- [ ] #task Add rarity and repeated-attempt penalties to arm ranking #repo/RIPDPI #area/privacy-preserving-strategy-learner #status/backlog 🔼 [paperclip:POY-137]
  - Paperclip: POY-137 · assigned to: unassigned
  - Parent: POY-53 (Epic - Privacy-preserving strategy learner)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, bandit
  - **source:** `TaskNotes/Tasks/Add rarity and repeated-attempt penalties to arm ranking.md`
  - **epic:** Epic - Privacy-preserving strategy learner

  ## Summary

  `rarity_penalty`: high for rare, distinctive wire images — protects
  against accumulation-based detection. `repeated_attempt_penalty`: grows
  when we keep hammering the same host with failures — protects against
  pattern pinning and battery burn.

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] §5.

  ## Acceptance criteria

  - [ ] Rarity is computed from local-observed arm frequency, not a preset
        label.
  - [ ] Penalty resets appropriately when the network profile changes (new
        observation window).
  - [ ] Repeated-attempt penalty is per `(host, NetProfile)`, not global.
  - [ ] Unit tests: rare arm wins tie-break only when posterior is high
        enough to justify it; repeated-attempt penalty caps after N
        consecutive failures.

  ## Links

  - [[Implement Bayesian posterior arm scoring]]
  - [[Epic - Privacy-preserving strategy learner]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]

- [ ] #task Decay successful families slower than failed variants #repo/RIPDPI #area/privacy-preserving-strategy-learner #status/backlog 🔼 [paperclip:POY-171]
  - Paperclip: POY-171 · assigned to: unassigned
  - Parent: POY-53 (Epic - Privacy-preserving strategy learner)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, bandit
  - **source:** `TaskNotes/Tasks/Decay successful families slower than failed variants.md`
  - **epic:** Epic - Privacy-preserving strategy learner

  ## Summary

  Decay `ArmStats` so successful families retain their prior longer than
  failed exact variants. Otherwise a single failure can wipe out
  accumulated learning.

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] §5 (successful families
  decay more slowly than failed exact variants).

  ## Acceptance criteria

  - [ ] Separate decay half-lives for wins and losses; wins decay slower.
  - [ ] Decay applies per-arm at periodic intervals, not on every update
        (cheap).
  - [ ] Unit tests: with a 50/50 history, repeated additional losses
        gradually decrease score without zeroing it immediately.

  ## Links

  - [[Epic - Privacy-preserving strategy learner]]
  - [[Define NetProfile HostProfile and ArmStats]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]

- [ ] #task Define NetProfile HostProfile and ArmStats #repo/RIPDPI #area/privacy-preserving-strategy-learner #status/backlog ⏫ [paperclip:POY-178]
  - Paperclip: POY-178 · assigned to: unassigned
  - Parent: POY-53 (Epic - Privacy-preserving strategy learner)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, bandit
  - **source:** `TaskNotes/Tasks/Define NetProfile HostProfile and ArmStats.md`
  - **epic:** Epic - Privacy-preserving strategy learner

  ## Summary

  Introduce the three data classes that back the learner. Field shapes come
  straight from the plan; keep them minimal and explicit.

  ```text
  NetProfile { asn, access_type, ip_family, dns_class,
               udp443_ok, tcp443_ok, observed_fail_phase }
  HostProfile { etld_plus_1, h3_advertised, https_rr_present,
                ech_capable, app_family }
  ArmStats { arm_id, alpha, beta, p50_ttfb_ms, bytes_overhead,
             repeated_failures, last_success_at }
  ```

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] §5 "Local state".

  ## Acceptance criteria

  - [ ] Types defined with serde support (stable schema, versioned).
  - [ ] No leakage of user-identifying data: no URL, no SSID, no precise
        location anywhere on these types.
  - [ ] Unit tests cover serialization round-trips and enum exhaustiveness.

  ## Links

  - [[Epic - Privacy-preserving strategy learner]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]

- [ ] #task Enforce diagnostic attempt budget #repo/RIPDPI #area/privacy-preserving-strategy-learner #status/backlog ⏫ [paperclip:POY-189]
  - Paperclip: POY-189 · assigned to: unassigned
  - Parent: POY-53 (Epic - Privacy-preserving strategy learner)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, bandit
  - **source:** `TaskNotes/Tasks/Enforce diagnostic attempt budget.md`
  - **epic:** Epic - Privacy-preserving strategy learner

  ## Summary

  Strict budget caps per diagnostic run:

  ```text
  max_active_arms = 5
  max_elapsed_ms  = 6000
  max_probe_bytes = 65536
  stop_on_first_stable_success = true
  ```

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] §5 attempt budget.

  ## Acceptance criteria

  - [ ] Orchestrator respects all four caps; breaching any one stops the
        run.
  - [ ] "Stable success" = first-pass success + one confirmation request
        (Phase 4 `confirm_once`).
  - [ ] Budget is observable via diagnostics — users/debugging see which cap
        fired.
  - [ ] Unit tests cover each cap firing first, and the interaction with
        `confirm_once`.

  ## Links

  - [[Implement direct-mode diagnostic orchestrator Phases 1-4]]
  - [[Epic - Privacy-preserving strategy learner]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]

- [ ] #task Implement Bayesian posterior arm scoring #repo/RIPDPI #area/privacy-preserving-strategy-learner #status/backlog ⏫ [paperclip:POY-198]
  - Paperclip: POY-198 · assigned to: unassigned
  - Parent: POY-53 (Epic - Privacy-preserving strategy learner)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, bandit
  - **source:** `TaskNotes/Tasks/Implement Bayesian posterior arm scoring.md`
  - **epic:** Epic - Privacy-preserving strategy learner

  ## Summary

  Score arms using Beta posterior with performance and rarity penalties:

  ```text
  posterior = alpha / (alpha + beta)
  score = posterior
        - 0.10 * normalized_ttfb
        - 0.08 * normalized_bytes_overhead
        - 0.15 * repeated_attempt_penalty
        - 0.20 * rarity_penalty
  ```

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] §5 arm ranking.

  ## Acceptance criteria

  - [ ] Scorer consumes `ArmStats` + `NetProfile` + `HostProfile`.
  - [ ] Normalization of TTFB and byte overhead is network-profile-aware
        (cellular vs wifi baselines differ).
  - [ ] Ties are broken deterministically but with a small randomization to
        avoid consistent arm preference.
  - [ ] Unit tests cover each weighting term in isolation.

  ## Links

  - [[Define NetProfile HostProfile and ArmStats]]
  - [[Add rarity and repeated-attempt penalties to arm ranking]]
  - [[Epic - Privacy-preserving strategy learner]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]

- [ ] #task Opt-in shared priors with coarse keys only #repo/RIPDPI #area/privacy-preserving-strategy-learner #status/backlog 🔼 [paperclip:POY-214]
  - Paperclip: POY-214 · assigned to: unassigned
  - Parent: POY-53 (Epic - Privacy-preserving strategy learner)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, privacy
  - **source:** `TaskNotes/Tasks/Opt-in shared priors with coarse keys only.md`
  - **epic:** Epic - Privacy-preserving strategy learner

  ## Summary

  If the user opts in, upload summaries that help future users on similar
  networks. Hard constraints: no payloads, no raw URLs, no SSID, no precise
  geolocation. Key only by coarse `(asn, access_type, dns_class, udp443_ok,
  fail_phase)`.

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] §5 shared priors.

  ## Acceptance criteria

  - [ ] Default: off. Opt-in is explicit and explained in the UI.
  - [ ] Uploader enforces coarse-key schema at serialization time — any
        unexpected field is a build-time error, not a runtime filter.
  - [ ] Upload batches are delayed and shuffled to avoid temporal
        correlation with user activity.
  - [ ] Upload is subject to the same kill switch as any other non-essential
        network activity.
  - [ ] Static analysis test asserts that the uploader module only depends
        on sanitized types — no path to leak URLs or SSIDs.

  ## Links

  - [[Epic - Privacy-preserving strategy learner]]
  - [[Limit DNS measurement to user-requested destinations]]
  - [[Coarsen location-derived egress hints to regional buckets]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]


## qr-code-and-clipboard

- [ ] #task Add QR generation and share for saved profiles #repo/RIPDPI #area/qr-code-and-clipboard #status/backlog 🔼 [paperclip:POY-84]
  - Paperclip: POY-84 · assigned to: unassigned
  - Parent: POY-54 (Epic - QR code and clipboard profile import)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, qr
  - **source:** `TaskNotes/Tasks/Add QR generation and share for saved profiles.md`
  - **epic:** Epic - QR code and clipboard profile import

  ## Summary

  Let users generate a QR code (and plain URI) from any saved profile, with
  an explicit one-time warning that the QR contains secrets.

  ## Context

  Generation is offline; no network round-trip. Use the same URI codec that
  the scanner consumes. Warning is dismissible but cannot be permanently
  suppressed — secret-sharing risk is high enough that nagging is warranted.

  ## Acceptance criteria

  - [ ] "Share profile" entry in the profile-detail menu emits both a QR
        bitmap and a plain URI string.
  - [ ] First invocation shows a non-dismissible-for-5s warning modal that
        credentials are embedded in the output.
  - [ ] QR is generated offline via zxing-core (no Play Services dep).
  - [ ] Share sheet lets the user choose "Copy URI" or "Share image".
  - [ ] Image share uses `FileProvider` at `profile.fileprovider`; file is
        cleaned up after share completion.
  - [ ] Clear-text URI is not written to app logs; share intent is logged
        as metadata only.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/ui/QRCodeDialog.kt` — QR bitmap generation via `BarcodeEncoder` from `zxing-lite`. Replace with `zxing-core` directly (lighter) to keep off `zxing-lite`.
  - `app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt` — the "share profile" menu entry and its intent-build path: emits both QR bitmap and plain URI via share sheet.
  - `app/src/main/java/io/nekohasekai/sagernet/fmt/UniversalFmt.kt` — `toLink()` emits `sn://<type-slug>?<zlib+base64url Kryo>`. RIPDPI should use per-protocol URIs (not `sn://`) since Kryo is not in the RIPDPI stack.

  **Adapt:** The two-action share sheet (copy URI / share image), FileProvider cleanup on share completion. **Skip:** `sn://` universal link (invent nothing; always emit the canonical per-protocol scheme like `vless://`, `ss://`, `hy2://`).

  ## Links

  - [[Epic - QR code and clipboard profile import]]
  - [[Add QR scanner screen with CameraX and ML Kit]]

- [ ] #task Add QR scanner screen with CameraX and ML Kit #repo/RIPDPI #area/qr-code-and-clipboard #status/backlog ⏫ [paperclip:POY-85]
  - Paperclip: POY-85 · assigned to: unassigned
  - Parent: POY-54 (Epic - QR code and clipboard profile import)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, qr, onboarding
  - **source:** `TaskNotes/Tasks/Add QR scanner screen with CameraX and ML Kit.md`
  - **epic:** Epic - QR code and clipboard profile import

  ## Summary

  Add a Compose scanner screen that reads a QR containing a proxy URI
  (`vless://`, `vmess://`, `trojan://`, `ss://`, `hysteria2://`, `tuic://`,
  `anytls://`, `ripdpi://`) and routes to the profile-edit screen with
  populated fields.

  ## Context

  Shared URI codec lives in the subscription epic; this task is strictly the
  UI and camera plumbing. Denied camera permission must not brick the flow —
  offer an "import from image" fallback using SAF.

  ## Acceptance criteria

  - [ ] `ScannerScreen` composable with CameraX preview + ML Kit barcode
        scanner configured for QR only.
  - [ ] On decode, validate scheme against the allowlist and dispatch to
        profile-edit via Compose Navigation.
  - [ ] Camera permission rationale rendered inline, not a modal.
  - [ ] Fallback "pick image" via `ActivityResultContracts.OpenDocument`
        decodes QR from a still image.
  - [ ] Invalid QR content shows a redacted error (first 16 chars only);
        never log the full payload.
  - [ ] RTL-safe layout; Roborazzi screenshot tests for en / ar / fa / zh-CN.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/ui/ScannerActivity.kt` — entire flow: camera permission gate, `CaptureManager` lifecycle, decoded-text dispatch. Port the flow, not the library (NekoBox uses `zxing-lite`; RIPDPI should use CameraX + ML Kit barcode scanner for smaller APK and no camera-permission hang on vendor ROMs).
  - `app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt` — the "scan result received" callback path: `onScanResult(text)` validates, dispatches to per-protocol URI codec, falls back to `UniversalFmt.parseLink` for `sn://` scheme.

  **amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`) — the image-file QR decode path is cleaner than NekoBox's:

  - `ui/src/main/java/org/amnezia/awg/util/QrCodeFromFileScanner.kt` — decodes a QR from a picked image URI via `QRCodeReader` (no camera dependency). **Port this pattern** for the SAF-file-picker fallback path.

  **Adapt:** Permission-gate UX, image-file fallback, decoded-text dispatch. **Skip:** zxing-lite dependency (use ML Kit unbundled-model variant to stay Play-Services-free).

  ## Links

  - [[Epic - QR code and clipboard profile import]]

- [ ] #task Add clipboard-import menu action with explicit user consent #repo/RIPDPI #area/qr-code-and-clipboard #status/backlog 🔼 [paperclip:POY-109]
  - Paperclip: POY-109 · assigned to: unassigned
  - Parent: POY-54 (Epic - QR code and clipboard profile import)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, clipboard, privacy
  - **source:** `TaskNotes/Tasks/Add clipboard-import menu action with explicit user consent.md`
  - **epic:** Epic - QR code and clipboard profile import

  ## Summary

  Add an "Import from clipboard" menu action on the Configuration screen
  that reads the clipboard only when the user taps it, parses via the shared
  URI codec, and lands on profile-edit.

  ## Context

  RIPDPI's privacy posture forbids silent clipboard reads. Android 12+ also
  surfaces a toast for every programmatic clipboard read; only pull when the
  user has made an intent explicit. No watcher, no auto-paste detection.

  ## Acceptance criteria

  - [ ] Menu entry is visible on Configuration top-bar overflow, labeled
        "Import from clipboard".
  - [ ] Tap reads clipboard once, parses via shared URI codec, and
        navigates.
  - [ ] Unknown clipboard content surfaces a typed error with the scheme
        it found (no payload leak).
  - [ ] No broadcast receiver, service, or foreground listener monitors
        clipboard in the background.
  - [ ] On Android 12+, the system toast appearance is expected and not
        suppressed.
  - [ ] Clipboard is cleared after import on user's explicit opt-in
        (default off) to reduce persisted credential exposure.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt` — the "Import from clipboard" menu handler: `SagerNet.clipboard.primaryClip?.getItemAt(0)?.text?.toString()` then dispatches to the same URI parser used by QR scan.
  - `app/src/main/java/io/nekohasekai/sagernet/SagerNet.kt` — `clipboard` accessor (wraps `ClipboardManager` as a typed system-service property). Reference for the accessor pattern only.

  **Adapt:** The menu action + one-shot read + dispatch. **Skip:** NekoBox has no consent gate because it reads clipboard only on explicit user menu tap (same posture as this task asks for). NekoBox has no "clear clipboard after import" step — add it in RIPDPI as an opt-in, documented per task acceptance.

  ## Links

  - [[Epic - QR code and clipboard profile import]]

- [ ] #task Add share-sheet handler for proxy URI schemes #repo/RIPDPI #area/qr-code-and-clipboard #status/backlog ⏫ [paperclip:POY-143]
  - Paperclip: POY-143 · assigned to: unassigned
  - Parent: POY-54 (Epic - QR code and clipboard profile import)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, intents
  - **source:** `TaskNotes/Tasks/Add share-sheet handler for proxy URI schemes.md`
  - **epic:** Epic - QR code and clipboard profile import

  ## Summary

  Register intent filters so RIPDPI appears in the Android share sheet (and
  as a URL opener) for `vless://`, `vmess://`, `trojan://`, `ss://`,
  `hysteria://`, `hysteria2://`, `tuic://`, `anytls://`, `ssh://`, and
  grouped NekoBox `sn://` schemes.

  ## Context

  Today RIPDPI only handles `ripdpi://`. Extending the filters lets users
  tap a share link in Telegram or a browser and land directly in the
  profile-edit flow. No subscription schemes are claimed here — that is
  handled by URL import inside the subscription epic.

  ## Acceptance criteria

  - [ ] `MainActivity` or a dedicated entry Activity declares intent filters
        for each listed scheme.
  - [ ] The handler dispatches to the shared URI codec and navigates to
        profile-edit with populated state.
  - [ ] Multiple filter priority avoids claiming HTTPS — browser ordering
        for `https://` stays untouched.
  - [ ] Unknown sub-schemes fall through to a typed "unsupported scheme"
        error, not a crash.
  - [ ] Instrumented test covers at least one representative URI per
        scheme.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/AndroidManifest.xml` — the `MainActivity` intent-filter list declares each scheme (`sn://`, `ss://`, `ssr://`, `vmess://`, `trojan://`, `trojan-go://`, `naive+https://`, `naive+quic://`, `hysteria://`, `socks://`, `socks4://`, `socksa://`, `sock5://`, plus `clash://install-config` subscription scheme). Port the filter list shape.
  - `app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt` — `onNewIntent()` routes by scheme to parse-and-open-editor vs parse-and-create-subscription paths.
  - Per-protocol URI codecs under `app/src/main/java/io/nekohasekai/sagernet/fmt/` — **the canonical source of truth for each scheme**:
    - `shadowsocks/ShadowsocksFmt.kt` — `ss://` parse + emit (SIP002 format)
    - `trojan/TrojanFmt.kt` — `trojan://`
    - `v2ray/V2RayFmt.kt` — `vmess://` (JSON-base64 and standard), `vless://`, also `trojan://` variant
    - `hysteria/HysteriaFmt.kt` — `hysteria://`, `hysteria2://`, `hy2://`
    - `tuic/TuicFmt.kt` — `tuic://`
    - `socks/SOCKSFmt.kt` — `socks5://`, `socks://`, `sock5://`, `socks4://`, `socksa://`
    - `http/HttpFmt.kt` — `http://`, `https://` (as proxy URIs)
    - `naive/NaiveFmt.kt` — `naive+https://`, `naive+quic://`
    - `trojan_go/TrojanGoFmt.kt` — `trojan-go://`
    - `moe/matsuri/nb4a/proxy/anytls/AnyTLSFmt.kt` — `anytls://`
    - `moe/matsuri/nb4a/proxy/shadowtls/ShadowTLSFmt.kt` — `shadowtls://` (non-standard)

  **Adapt:** Full intent-filter manifest block, per-scheme dispatch in activity, full URI codec set. **Skip:** `sn://` universal link.

  ## Links

  - [[Epic - QR code and clipboard profile import]]


## remove-cloudflare-from-critical

- [ ] #task Add Cloudflare degradation classification runbook #repo/RIPDPI #area/remove-cloudflare-from-critical #status/backlog ⏫ [paperclip:POY-70]
  - Paperclip: POY-70 · assigned to: unassigned
  - Parent: POY-55 (Epic - Remove Cloudflare from critical path)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** vps
  - **tags:** task, vps, ripdpi, cloudflare, runbook
  - **source:** `TaskNotes/Tasks/Add Cloudflare degradation classification runbook.md`
  - **epic:** Epic - Remove Cloudflare from critical path

  ## Summary

  Create a runbook that distinguishes Cloudflare edge throttling, domain-specific blocking, origin failure, client/protocol failure, and mobile whitelist/shutdown modes.

  ## Context

  Different failures produce similar user reports. The response differs: demote Cloudflare path, rotate hostname, fix origin, patch client protocol, or switch to whitelist-mode guidance.

  ## Acceptance criteria

  - [ ] Runbook defines symptoms and checks for edge throttling, domain block, origin issue, client/protocol issue, and whitelist/shutdown.
  - [ ] Includes payload-level checks rather than relying only on TLS handshake.
  - [ ] Includes non-Russian control checks to detect origin failures.
  - [ ] Includes guidance for when to disable Cloudflare path in auto-selection.
  - [ ] Includes guidance for where to store sensitive live findings under `ops/live-infra/`.

  ## Notes

  Keep user-visible state simple: degraded Cloudflare-like path, origin issue, network restricted, or profile issue.

  ## Links

  - [[Epic - Remove Cloudflare from critical path]]
  - [[Add Cloudflare large-payload healthcheck]]

- [ ] #task Add Cloudflare large-payload healthcheck #repo/RIPDPI #area/remove-cloudflare-from-critical #status/backlog ⏫ [paperclip:POY-71]
  - Paperclip: POY-71 · assigned to: unassigned
  - Parent: POY-55 (Epic - Remove Cloudflare from critical path)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, cloudflare, healthcheck
  - **source:** `TaskNotes/Tasks/Add Cloudflare large-payload healthcheck.md`
  - **epic:** Epic - Remove Cloudflare from critical path

  ## Summary

  Add payload-level health checks that detect Cloudflare-like degradation where TCP/TLS succeeds but transfer stalls around the first tens of kilobytes.

  ## Motivation

  Small `/generate_204`-style checks cannot detect the documented Russian Cloudflare disruption pattern. RIPDPI needs large-payload checks before treating Cloudflare-backed profiles as healthy.

  ## Scope

  - In scope: 64 KB payload check, 256 KB hash check, protocol-level tunnel probe, degraded state, selector integration, and diagnostics.
  - Out of scope: storing user identifiers in probe URLs or making Cloudflare a required probe target.

  ## Acceptance criteria

  - [ ] Health checker records TCP connect, TLS handshake, small response, 64 KB body, 256 KB body hash, and protocol-level tunnel outcome separately.
  - [ ] If TLS succeeds but large body stalls, profile is marked `DEGRADED_CLOUDFLARE_LIKE` or equivalent.
  - [ ] Degraded Cloudflare-like profiles are disabled for auto selection and remain manual-only.
  - [ ] Health checks are rate-limited and do not include subscription tokens.
  - [ ] UI explains that handshake success is not sufficient evidence of payload availability.

  ## Design notes

  Use neutral controlled health objects. Do not rely on public Cloudflare test URLs as the only evidence source.

  ## Risks / open questions

  - Large probes consume bandwidth; cadence should be conservative and triggered by profile health transitions.

  ## Links

  - [[Epic - Remove Cloudflare from critical path]]
  - [[Add priority-based outbound failover state machine]]
  - [[Add Android VPN leak-test instrumentation matrix]]

- [ ] #task Add multi-delivery subscription mirror support #repo/RIPDPI #area/remove-cloudflare-from-critical #status/backlog ⏫ [paperclip:POY-125]
  - Paperclip: POY-125 · assigned to: unassigned
  - Parent: POY-55 (Epic - Remove Cloudflare from critical path)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, subscriptions, delivery
  - **source:** `TaskNotes/Tasks/Add multi-delivery subscription mirror support.md`
  - **epic:** Epic - Remove Cloudflare from critical path

  ## Summary

  Allow a per-device subscription profile to carry multiple delivery URLs or bootstrap mirrors, with Cloudflare mirrors treated as optional rather than authoritative.

  ## Motivation

  Users need a way to refresh profiles when one delivery plane is unreachable. A single bearer URL behind Cloudflare is a critical failure point.

  ## Scope

  - In scope: mirror list model, ordered refresh attempts, mirror health state, token redaction, no-log diagnostics, and UI showing which mirror last succeeded.
  - Out of scope: sharing one token across unrelated devices or bypassing per-device token scope.

  ## Acceptance criteria

  - [ ] Subscription state can store multiple scoped delivery mirrors for one physical device.
  - [ ] Refresh attempts prefer non-Cloudflare direct delivery when available.
  - [ ] Cloudflare mirror failures do not block trying non-Cloudflare mirrors.
  - [ ] Logs and diagnostics redact every mirror token and full URL.
  - [ ] UI shows last refresh mirror and degraded mirror state without exposing secrets.

  ## Design notes

  Mirror support must not weaken bearer-token scope. Each mirror can have its own token or a scoped token design, but shared all-user URLs are not allowed.

  ## Risks / open questions

  - Multiple URLs increase leak surface; pair this with token expiry and redaction tests.

  ## Links

  - [[Epic - Remove Cloudflare from critical path]]
  - [[Epic - NekoBox subscription and profile import]]
  - [[Add per-device subscription token UX and shared-link warnings]]

- [ ] #task Audit Cloudflare-only dependencies #repo/RIPDPI #area/remove-cloudflare-from-critical #status/backlog 🔺 [paperclip:POY-157]
  - Paperclip: POY-157 · assigned to: unassigned
  - Parent: POY-55 (Epic - Remove Cloudflare from critical path)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** vps
  - **tags:** task, vps, ripdpi, cloudflare, audit
  - **source:** `TaskNotes/Tasks/Audit Cloudflare-only dependencies.md`
  - **epic:** Epic - Remove Cloudflare from critical path

  ## Summary

  Find every Cloudflare-only dependency in the fleet, client profiles, subscription delivery, DNS, public site, API/update path, and emergency access flows.

  ## Context

  Cloudflare must be treated as a degraded/failable edge for Russian users. The first step is to identify single points of failure before building replacement paths.

  ## Acceptance criteria

  - [ ] Inventory every Cloudflare-backed delivery hostname, subscription URL, DoH/DoT/DoQ resolver, XHTTP frontend, public site, API/update endpoint, Worker/Pages/Tunnel, and reverse-proxy path.
  - [ ] Classify each dependency as primary, fallback, optional, or unused.
  - [ ] Mark which dependencies currently block IP rotation, subscription refresh, profile recovery, or emergency migration if Cloudflare is unreachable.
  - [ ] Assign a non-Cloudflare replacement or fallback plan to each critical dependency.
  - [ ] Store live hostnames and sensitive findings only in `ops/live-infra/`; keep TaskNotes summary sanitized.

  ## Notes

  This audit should happen before any DNS-only flip or origin exposure.

  ## Links

  - [[Epic - Remove Cloudflare from critical path]]
  - [[cloudflare-ru-critical-path-removal-2026-05-01]]

- [ ] #task Demote Cloudflare profiles from default auto selection #repo/RIPDPI #area/remove-cloudflare-from-critical #status/backlog ⏫ [paperclip:POY-184]
  - Paperclip: POY-184 · assigned to: unassigned
  - Parent: POY-55 (Epic - Remove Cloudflare from critical path)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, cloudflare, selector
  - **source:** `TaskNotes/Tasks/Demote Cloudflare profiles from default auto selection.md`
  - **epic:** Epic - Remove Cloudflare from critical path

  ## Summary

  Make Cloudflare-backed XHTTP/HTTPS profiles low-priority or manual-only in the default selector when Russian-path degradation is detected or likely.

  ## Motivation

  Cloudflare can pass TCP/TLS and still fail payload transfer. It should not compete equally with direct REALITY or non-Cloudflare HTTPS fallback in auto mode for Russian users.

  ## Scope

  - In scope: profile capability flag, health-state based demotion, selector ordering, manual override, and UI labels.
  - Out of scope: removing Cloudflare support entirely.

  ## Acceptance criteria

  - [ ] Default auto candidates prefer direct REALITY and non-Cloudflare HTTPS fallback.
  - [ ] Cloudflare-backed profiles are excluded from auto when marked degraded.
  - [ ] Manual selection still allows Cloudflare profile use where it works.
  - [ ] Selector UI labels Cloudflare paths as optional/edge fallback.
  - [ ] Tests cover transition from healthy to degraded and back after payload health recovers.

  ## Design notes

  This task complements, but does not replace, the broader failover state machine.

  ## Risks / open questions

  - Some Russian ISPs may still pass Cloudflare; demotion should be health-based, not a global hard block.

  ## Links

  - [[Epic - Remove Cloudflare from critical path]]
  - [[Epic - Xray VPN client mode]]
  - [[Add Cloudflare large-payload healthcheck]]

- [ ] #task Remove Cloudflare DNS from critical resolver chain #repo/RIPDPI #area/remove-cloudflare-from-critical #status/backlog ⏫ [paperclip:POY-226]
  - Paperclip: POY-226 · assigned to: unassigned
  - Parent: POY-55 (Epic - Remove Cloudflare from critical path)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, feature, ripdpi, dns, cloudflare
  - **source:** `TaskNotes/Tasks/Remove Cloudflare DNS from critical resolver chain.md`
  - **epic:** Epic - Remove Cloudflare from critical path

  ## Summary

  Ensure Cloudflare DNS services are never the only bootstrap resolver, tunneled resolver, or encrypted DNS fallback for RIPDPI profiles.

  ## Motivation

  If Cloudflare is degraded as a network path, Cloudflare DoH/DoT/DoQ can become the same failure domain as Cloudflare edge.

  ## Scope

  - In scope: resolver inventory, profile defaults, bootstrap allowlist, non-CF encrypted resolver backup, and diagnostics warning.
  - Out of scope: banning Cloudflare DNS as an optional resolver.

  ## Acceptance criteria

  - [ ] No secure profile uses Cloudflare DNS as its only bootstrap or tunneled resolver.
  - [ ] Tunneled DNS has own-resolver or non-CF encrypted primary/backup options.
  - [ ] Bootstrap endpoint resolution prefers pinned IPs or tiny direct allowlist, not general Cloudflare DNS.
  - [ ] Diagnostics warn when all configured resolver paths share the Cloudflare failure domain.
  - [ ] Resolver outage tests prove no fallback to local plaintext DNS for proxied/default domains.

  ## Design notes

  This is a specialization of split-strict DNS policy for the Cloudflare failure domain.

  ## Risks / open questions

  - Public resolver diversity can still centralize metadata; own resolver through tunnel should be evaluated for production profiles.

  ## Links

  - [[Epic - Remove Cloudflare from critical path]]
  - [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
  - [[Implement strict tunneled DNS resolver failover]]


## semantic-tls-first-flight

- [ ] #task Cross-check Lantern record-fragmentation offsets against rec_sni arms #repo/RIPDPI #area/semantic-tls-first-flight #status/backlog 🔼 [paperclip:POY-170]
  - Paperclip: POY-170 · assigned to: unassigned
  - Parent: POY-57 (Epic - Semantic TLS first-flight family engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, spike, ripdpi, direct-mode, tls
  - **source:** `TaskNotes/Tasks/Cross-check Lantern record-fragmentation offsets against rec_sni arms.md`
  - **epic:** Epic - Semantic TLS first-flight family engine

  ## Summary

  Enumerate Lantern's published TLS record-fragmentation split offsets and
  diff them against RIPDPI's `rec_pre_sni` and `rec_mid_sni` neighborhoods,
  then recommend whether to widen our neighborhoods.

  ## Research citation

  [[ripdpi-android-research-2026-04-20]] §Peer mobile clients — Lantern
  Unbounded fragments the TLS handshake across records so SNI straddles a
  record boundary. Our `rec_*_sni` arms exist in the same family; making
  sure we cover their offsets de-risks field regressions where Lantern
  works and RIPDPI does not.

  ## Acceptance criteria

  - [ ] Lantern's TLS record-fragmentation offsets enumerated with source
        pointers.
  - [ ] Diff against `rec_pre_sni` and `rec_mid_sni` neighborhoods
        documented (same / subset / superset / disjoint).
  - [ ] Recommendation: widen neighborhood, add a new record-split arm, or
        no change — with expected coverage impact.

  ## Links

  - [[Epic - Semantic TLS first-flight family engine]]
  - [[Implement TLS record-split family arms]]
  - [[Rotate successful family through variant neighborhood]]
  - [[ripdpi-android-research-2026-04-20]]

- [ ] #task Defensive dMAP ambiguity-probe regression for semantic TLS engine #repo/RIPDPI #area/semantic-tls-first-flight #status/backlog 🔼 [paperclip:POY-176]
  - Paperclip: POY-176 · assigned to: unassigned
  - Parent: POY-57 (Epic - Semantic TLS first-flight family engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, spike, ripdpi, direct-mode, tls, defensive
  - **source:** `TaskNotes/Tasks/Defensive dMAP ambiguity-probe regression for semantic TLS engine.md`
  - **epic:** Epic - Semantic TLS first-flight family engine

  ## Summary

  Replay dMAP-style DPI ambiguity probe sequences against all six named
  arms and verify that no rotated family produces a stable ambiguity
  fingerprint that a TSPU-class censor could use to identify RIPDPI.

  ## Research citation

  [[ripdpi-android-research-2026-04-20]] §Academic papers — dMAP (CCS '25)
  fingerprints DPI devices by how they resolve protocol ambiguities. The
  same primitive inverted lets a censor fingerprint *us* by how our arms
  resolve ambiguities. Transparent-mode rotation must stay behind this
  bar.

  ## Acceptance criteria

  - [ ] dMAP-style probe sequences replayed against `seg_pre_sni`,
        `seg_mid_sni`, `seg_post_sni`, `rec_pre_sni`, `rec_mid_sni`,
        `two_phase_send`.
  - [ ] Verdict per arm: stable ambiguity profile? if yes, which invariant.
  - [ ] Recommendation on neighborhood widening or arm retirement where a
        stable profile is found.
  - [ ] Result added as a recurring regression in
        [[Epic - Orchestration test posture]] follow-up if material.

  ## Links

  - [[Epic - Semantic TLS first-flight family engine]]
  - [[Guard transparent mode against ClientHello byte mutation]]
  - [[Rotate successful family through variant neighborhood]]
  - [[ripdpi-android-research-2026-04-20]]

- [ ] #task Pin uTLS to v1.8.2 and add ClientHello fingerprint regression test #repo/RIPDPI #area/semantic-tls-first-flight #status/backlog 🔼 [paperclip:POY-221]
  - Paperclip: POY-221 · assigned to: unassigned
  - Parent: POY-57 (Epic - Semantic TLS first-flight family engine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-25
  - **dateModified:** 2026-04-25
  - **area:** android
  - **tags:** task, chore, ripdpi, tls-fingerprints, utls
  - **source:** `TaskNotes/Tasks/Pin uTLS to v1.8.2 and add ClientHello fingerprint regression test.md`
  - **epic:** Epic - Semantic TLS first-flight family engine

  ## Summary

  Pin `refraction-networking/utls` to ≥ v1.8.2 to close the Chrome 120 padding-extension regression and the GREASE ECH AES/ChaCha20 mismatch (PR #375). Add a regression test that asserts emitted ClientHello bytes match a Chrome 120 reference fixture, so future uTLS upgrades cannot silently re-introduce fingerprint drift.

  ## Research citation

  [[ripdpi-android-research-2026-04-25]] §TLS fingerprinting tooling — uTLS v1.8.2 (2026-01-13) restored padding extension after PQ key shares altered packet sizing; PR #375 (merged 2025-10-14) fixed GREASE ECH cipher-mismatch that produced provably non-Chrome ClientHellos ~50% of the time. Both fixes affect any RIPDPI code path using `HelloChrome_120`, `HelloChrome_120_PQ`, `HelloChrome_131`, or `HelloChrome_133`.

  ## Acceptance criteria

  - [ ] Dependency manifest pins `refraction-networking/utls` to ≥ v1.8.2
  - [ ] Regression test verifies `HelloChrome_120` ClientHello matches a recorded reference byte-for-byte (including padding extension)
  - [ ] CI fails on any uTLS-emitted ClientHello drift vs the reference fixture
  - [ ] Test corpus includes ECH-enabled and ECH-disabled flows (covers PR #375 cipher-consistency)

  ## Links

  - Project: [[ripdpi-android]]
  - Epic: [[Epic - Semantic TLS first-flight family engine]]
  - Research: [[ripdpi-android-research-2026-04-25]] §TLS fingerprinting tooling


## settings-backup-and-restore

- [ ] #task Add SAF export action with FULL and SHARE variants #repo/RIPDPI #area/settings-backup-and-restore #status/backlog 🔼 [paperclip:POY-88]
  - Paperclip: POY-88 · assigned to: unassigned
  - Parent: POY-58 (Epic - Settings backup and restore)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, backup, saf
  - **source:** `TaskNotes/Tasks/Add SAF export action with FULL and SHARE variants.md`
  - **epic:** Epic - Settings backup and restore

  ## Summary

  Add Tools-screen export action that writes the backup JSON via
  `ActivityResultContracts.CreateDocument`, letting the user pick between
  FULL (credentials included) and SHARE (redacted) variants.

  ## Context

  SAF is the only write path — no hardcoded file locations. Default target
  is the Downloads bucket, default filename is
  `ripdpi-backup-YYYY-MM-DDTHH-MM.json`. The FULL/SHARE picker is a
  bottom-sheet with clear risk framing for FULL.

  ## Acceptance criteria

  - [ ] Export entry point in Tools → Backup & Restore.
  - [ ] Variant picker makes the risk visually distinct; FULL is not the
        default.
  - [ ] Writer streams the JSON via SAF `OutputStream`; never materializes
        the full archive in memory.
  - [ ] On success, a snackbar confirms the destination and offers a
        "Share" follow-up for SHARE variant; for FULL, share is not
        offered inline.
  - [ ] Write failure surfaces a typed error; partial file is deleted if
        the user hit cancel mid-write.
  - [ ] Export never logs the payload; only the byte count and variant.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt` — the export flow uses `ActivityResultContracts.CreateDocument("application/json")`. Search for `exportLauncher` registration.
  - Default filename pattern: `nekobox_backup_<timestamp>.json`. RIPDPI should use `ripdpi-backup-<timestamp>.json`.

  **amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`) — **has a superior gating pattern**:

  - `ui/src/main/java/org/amnezia/awg/preference/ZipExporterPreference.kt` — **biometric-gated** export, plus MDM-policy suppression via `AdminKnobs.disable_config_export`. **Adopt both patterns** in RIPDPI: biometric gate for FULL variant, optional MDM suppression.

  **Adapt:** SAF contract, filename pattern. **Adopt from AWG:** biometric gate for FULL, MDM suppression knob. **Skip:** NekoBox's zero-gate export (credentials-to-any-picker is a privacy footgun).

  ## Links

  - [[Epic - Settings backup and restore]]
  - [[Add versioned backup JSON schema with redaction allowlist]]

- [ ] #task Add SAF import flow with selective restore #repo/RIPDPI #area/settings-backup-and-restore #status/backlog 🔼 [paperclip:POY-89]
  - Paperclip: POY-89 · assigned to: unassigned
  - Parent: POY-58 (Epic - Settings backup and restore)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, backup, restore, saf
  - **source:** `TaskNotes/Tasks/Add SAF import flow with selective restore.md`
  - **epic:** Epic - Settings backup and restore

  ## Summary

  Add a SAF-based import flow that reads a backup JSON, previews its
  contents, and lets the user pick which subsets (profiles / routing /
  settings) to restore.

  ## Context

  Restore is destructive; no silent overwrite. The preview step lists the
  counts (N profiles, M rules, K settings changed vs current), and the
  user opts in to specific subsets. Schema-version gating is strict:
  newer-than-app rejects, older migrates.

  ## Acceptance criteria

  - [ ] Import entry point in Tools → Backup & Restore.
  - [ ] File picker restricts to `application/json` MIME.
  - [ ] Preview screen shows per-category counts and the schema version.
  - [ ] Checkbox per category (profiles+groups / routes / settings)
        selects what to restore; current state for unchecked categories
        is preserved.
  - [ ] Restore writes to a staging area, validates integrity, then
        atomically swaps into the live data stores.
  - [ ] `ProcessPhoenix`-equivalent restart after successful restore so
        all in-flight DataStore / Room observers reinitialize.
  - [ ] Malformed JSON or failed integrity check aborts without touching
        live data.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt` — the import flow: `ActivityResultContracts.OpenDocument(arrayOf("application/json"))` → confirmation dialog (no preview) → `Backup.importBackup()` → `ProcessPhoenix.triggerRebirth()`.

  **Adapt:** SAF contract, ProcessPhoenix restart pattern (use `com.jakewharton:process-phoenix:2.1.2` — same version). **Improve over NekoBox:** NekoBox confirmation is a plain "yes/no" dialog without preview; RIPDPI's acceptance criteria adds per-category preview counts and opt-in selectivity. **Skip:** NekoBox's all-or-nothing restore pattern.

  ## Links

  - [[Epic - Settings backup and restore]]
  - [[Add versioned backup JSON schema with redaction allowlist]]

- [ ] #task Add reset-all-settings action with confirmation and restart #repo/RIPDPI #area/settings-backup-and-restore #status/backlog 🔽 [paperclip:POY-139]
  - Paperclip: POY-139 · assigned to: unassigned
  - Parent: POY-58 (Epic - Settings backup and restore)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, settings, destructive
  - **source:** `TaskNotes/Tasks/Add reset-all-settings action with confirmation and restart.md`
  - **epic:** Epic - Settings backup and restore

  ## Summary

  Add a "Reset all settings" destructive action in Tools → Backup & Restore
  that wipes profiles, groups, routes, user settings, and caches; then
  restarts the app.

  ## Context

  Complements export/import: gives a clean slate for testing. This is a
  destructive action, so the confirmation must be typed (not a single tap)
  and the action must surface telemetry so diagnostics can distinguish
  "reset" from "crash" in user reports.

  ## Acceptance criteria

  - [ ] Action surfaces behind a "type RESET to confirm" dialog (localized).
  - [ ] On confirm, wipes: ProxyEntity/ProxyGroup/Subscription, RuleEntity,
        AppSettings proto, DiagnosticsDatabase tables that hold user
        history, cache directories.
  - [ ] Keeps: app install state, keystore entries needed for the next
        session bootstrap, permission grants.
  - [ ] Emits a one-shot telemetry event "user_initiated_reset" before
        wipe; the event is preserved across restart.
  - [ ] `ProcessPhoenix`-equivalent restart brings the app to onboarding.
  - [ ] Destructive action can be cancelled up to the confirm step
        without side effects.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt` — the "Reset settings" action: clears `DataStore.configurationStore`, `SagerDatabase` tables, then `ProcessPhoenix.triggerRebirth()`. Single-tap with a plain yes/no dialog.

  **Adapt:** Wipe-then-restart pattern, ProcessPhoenix usage. **Improve over NekoBox:** add the typed-confirmation input (user must type "RESET") and the pre-wipe telemetry event. NekoBox's single-tap is too easy to trigger accidentally — a real user-pain report in NekoBox issue tracker.

  ## Links

  - [[Epic - Settings backup and restore]]

- [ ] #task Add share-sheet intent for redacted SHARE backups #repo/RIPDPI #area/settings-backup-and-restore #status/backlog 🔽 [paperclip:POY-144]
  - Paperclip: POY-144 · assigned to: unassigned
  - Parent: POY-58 (Epic - Settings backup and restore)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, backup, share
  - **source:** `TaskNotes/Tasks/Add share-sheet intent for redacted SHARE backups.md`
  - **epic:** Epic - Settings backup and restore

  ## Summary

  Add a "Share diagnostic backup" shortcut that generates a SHARE-variant
  backup on-demand and hands it to the Android share sheet.

  ## Context

  For remote debugging, a user can send a redacted backup to a maintainer
  without keeping a file on disk. The file is written to the cache dir,
  shared via `FileProvider`, and cleaned up after the intent completes.

  ## Acceptance criteria

  - [ ] Shortcut in Tools → Backup & Restore labeled "Share redacted
        backup".
  - [ ] Invocation generates a fresh SHARE backup, writes to cache dir,
        and launches `ACTION_SEND` with `FileProvider` URI.
  - [ ] MIME is `application/json`; subject is predictable; message body
        is empty to avoid accidental leaks from autofill.
  - [ ] File is deleted after the share completes or is cancelled (hook
        into the result callback).
  - [ ] First-run shows a one-time reminder that SHARE is redacted but
        not zero-knowledge.

  ## Source references

  **NekoBoxForAndroid** — no direct analog. NekoBox's `BackupFragment.kt` has a "share" path but it shares the full-credentials backup, which is the exact footgun this task is designed to prevent.

  **amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`):

  - `ui/src/main/java/org/amnezia/awg/activity/LogViewerActivity.kt` — FileProvider authority pattern `${applicationId}.exported-log` with `grantUriPermissions=true`. Reuse this pattern for the backup FileProvider (authority e.g. `ripdpi.backup.fileprovider`).
  - The `ShareCompat.IntentBuilder` usage there is the cleanest template.

  **Adapt:** FileProvider authority + grant-uri-permission setup, `ShareCompat` builder usage. **Add (neither project has):** post-share cleanup of the cache-dir temp file on intent result.

  ## Links

  - [[Epic - Settings backup and restore]]

- [ ] #task Add versioned backup JSON schema with redaction allowlist #repo/RIPDPI #area/settings-backup-and-restore #status/backlog ⏫ [paperclip:POY-152]
  - Paperclip: POY-152 · assigned to: unassigned
  - Parent: POY-58 (Epic - Settings backup and restore)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, backup, schema, privacy
  - **source:** `TaskNotes/Tasks/Add versioned backup JSON schema with redaction allowlist.md`
  - **epic:** Epic - Settings backup and restore

  ## Summary

  Define a versioned JSON schema for RIPDPI backups — profiles, groups,
  routing rules, user settings — with an explicit per-protocol allowlist of
  fields that may ship in SHARE mode after secrets are redacted.

  ## Context

  Schema is the contract for export/import; redaction is the contract for
  sharing. Both must be explicit and unit-tested. Denial-by-default: any
  field added to a new protocol bean must be enumerated in the allowlist
  before it can appear in SHARE output; otherwise the export fails loudly
  rather than silently leaking the new field.

  ## Acceptance criteria

  - [ ] `backup/v1` JSON schema documented under `docs/` with field
        semantics and migration policy.
  - [ ] Serializer exports the schema version, creation timestamp, and
        app version as top-level metadata.
  - [ ] `SHARE` variant strips every field not on the per-protocol
        allowlist. A test matrix covers every bean type; a test fails if
        a new bean introduces a field not classified as
        `PUBLIC` / `REDACTED` / `EXCLUDED`.
  - [ ] `FULL` variant keeps every field but marks the archive with a
        prominent "contains credentials" flag.
  - [ ] Future schema versions must provide a forward migration; schema
        version N+1 must deserialize N by migration or reject cleanly with
        a typed "unsupported version" error.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt` — the full export/import codepath. Schema is a Gson-serialized `Backup` object with fields: `version`, `profiles`, `groups`, `rules`, `settings` (map). **Reference the field shape**; version starts at `1`.
  - Inside the same file: the `Backup.importBackup()` method shows the reverse — version gate, selective restore per category.

  **Adapt:** Top-level schema shape (`version`, `profiles`, `groups`, `rules`, `settings`), category-level selectivity. **Improve over NekoBox:** NekoBox has NO redaction variant; every export contains credentials. RIPDPI must add a SHARE variant with per-protocol field allowlist — a deliberate improvement documented in acceptance criteria. **Skip:** Gson (use `kotlinx.serialization`).

  ## Links

  - [[Epic - Settings backup and restore]]


## system-http-proxy-service

- [ ] #task Add ProxyService foreground service as alternative to TUN VPN #repo/RIPDPI #area/system-http-proxy-service #status/backlog 🔼 [paperclip:POY-83]
  - Paperclip: POY-83 · assigned to: unassigned
  - Parent: POY-59 (Epic - System HTTP proxy service mode)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, service
  - **source:** `TaskNotes/Tasks/Add ProxyService foreground service as alternative to TUN VPN.md`
  - **epic:** Epic - System HTTP proxy service mode

  ## Summary

  Introduce `RipDpiProxyService` as a foreground-service alternative to
  `RipDpiVpnService`: runs the mixed inbound and outbound dispatch, but
  opens no TUN and creates no `vpn_protect` socket server.

  ## Context

  The existing VPN service holds the TUN-centric invariants. A parallel
  service class keeps those separate; session picker decides which one
  starts. One-session-at-a-time guard prevents both from racing.

  ## Acceptance criteria

  - [ ] `RipDpiProxyService` extends a `LifecycleService`, not `VpnService`.
  - [ ] Foreground-service type is `systemExempted` + `specialUse`;
        notification channel reused from VPN path or dedicated.
  - [ ] Start/stop transitions share the supervisor lifecycle with
        `RipDpiVpnService`; a mutual-exclusion guard ensures only one of
        the two runs per session.
  - [ ] Switching VPN → Proxy (or vice versa) closes cleanly before the
        other starts; no socket or route leaks.
  - [ ] Diagnostics, logs, and crash reports clearly tag the active mode.
  - [ ] Strategy probe and detection checker both work in Proxy mode
        without a TUN.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/bg/ProxyService.kt` — the full class. Extends `Service` (not `VpnService`), implements `BaseService.Interface`. Declared as `foregroundServiceType="systemExempted"` in manifest.
  - `app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt` — the shared state machine (`Idle → Connecting → Connected → Stopping → Stopped`) both services implement. **Reference the interface** to understand the contract; RIPDPI's `LifecycleService`-based pattern will fit cleanly.
  - `app/src/main/AndroidManifest.xml` — the full `<service>` declaration including `process=":bg"` (separate process for the service) and notification-channel wiring.

  **Adapt:** The state machine contract, the one-session-at-a-time guard pattern (mutually exclusive with VPN), the `:bg` separate-process pattern (if RIPDPI doesn't already split). **Skip:** NekoBox-specific state constants; RIPDPI has its own supervisor state enum.

  ## Links

  - [[Epic - System HTTP proxy service mode]]
  - [[Add mixed SOCKS5 and HTTP CONNECT inbound listener]]

- [ ] #task Add mixed SOCKS5 and HTTP CONNECT inbound listener #repo/RIPDPI #area/system-http-proxy-service #status/backlog 🔼 [paperclip:POY-124]
  - Paperclip: POY-124 · assigned to: unassigned
  - Parent: POY-59 (Epic - System HTTP proxy service mode)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, proxy, inbound
  - **source:** `TaskNotes/Tasks/Add mixed SOCKS5 and HTTP CONNECT inbound listener.md`
  - **epic:** Epic - System HTTP proxy service mode

  ## Summary

  Extend the existing local-SOCKS5 inbound into a "mixed" inbound that also
  speaks HTTP CONNECT on the same port (protocol detected from the first
  bytes).

  ## Context

  NekoBox's `mixedPort` accepts SOCKS5 greeting and HTTP CONNECT on one TCP
  port. For local-only traffic from apps that honor the Android system
  proxy, this is the simplest path. First-byte switch: `0x05` → SOCKS5,
  `CONNECT ` prefix → HTTP.

  ## Acceptance criteria

  - [ ] Single listener binds a configurable port (default 2080) and
        dispatches per-connection to SOCKS5 or HTTP CONNECT handler.
  - [ ] HTTP CONNECT supports TLS tunnels only; no HTTP proxying of
        cleartext requests (no TLS interception anywhere).
  - [ ] No authentication; listener is bound to `127.0.0.1` by default.
        An opt-in "allow LAN" toggle binds to all interfaces with a stern
        warning modal.
  - [ ] Port collision surfaces a typed error with suggested next port.
  - [ ] Both SOCKS5 and CONNECT paths route through the existing outbound
        dispatch; no parallel supervisor.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt` — the sing-box `mixed` inbound generation (search for `"mixed"` as `type:` value). NekoBox delegates the actual listener to sing-box; `mixedPort` from `DataStore` flows into the generated JSON config.
  - `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt` — `mixedPort` property (default 2080), `socksPort`, `httpPort`. Port the default port, offset-by-user-index pattern (for multi-user Android support).

  **Outbound engine (NOT from NekoBox):** sing-box's `protocol/mixed` inbound in Go handles the first-byte dispatch (`0x05` → SOCKS5, `CONNECT ` → HTTP). RIPDPI implements this in Rust — simple state machine, ~50 lines. Reuse the existing SOCKS5 inbound code in `ripdpi-runtime`; add HTTP CONNECT branch.

  **Adapt:** Default port 2080, multi-user port-offset pattern. **Skip:** sing-box Go implementation.

  ## Links

  - [[Epic - System HTTP proxy service mode]]

- [ ] #task Add service-mode picker to Settings and onboarding #repo/RIPDPI #area/system-http-proxy-service #status/backlog 🔼 [paperclip:POY-141]
  - Paperclip: POY-141 · assigned to: unassigned
  - Parent: POY-59 (Epic - System HTTP proxy service mode)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, settings, onboarding
  - **source:** `TaskNotes/Tasks/Add service-mode picker to Settings and onboarding.md`
  - **epic:** Epic - System HTTP proxy service mode

  ## Summary

  Surface the TUN VPN vs System Proxy choice in both Settings and the
  onboarding flow, with a clear trade-off explanation.

  ## Context

  The existing onboarding already validates the chosen mode before finish.
  Extend it with the new choice and keep the phrasing honest: VPN is
  higher coverage but requires TUN permission; Proxy is lower coverage but
  no TUN prompt. Default to VPN mode; users must deliberately opt into
  Proxy mode.

  ## Acceptance criteria

  - [ ] Settings / Advanced Settings exposes a "Service mode" radio with
        two options: "Full tunnel (VPN)" and "System proxy only".
  - [ ] Onboarding asks the same question with a short trade-off blurb
        and a "most users pick Full tunnel" steer.
  - [ ] Changing the mode while a session is running prompts for
        reconnect; the UI does not silently restart.
  - [ ] Chosen mode is persisted and restored on boot (coordinates with
        [[Epic - Boot autostart and session persistence]]).
  - [ ] Mode name localizes correctly in RTL layouts.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt` — `serviceMode: String` property, constants `MODE_VPN` and `MODE_PROXY` defined in `Key.kt`.
  - `app/src/main/java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt` — search for `serviceMode`; the picker is a `ListPreference` bound to `DataStore.configurationStore`.
  - `app/src/main/res/xml/global_preferences.xml` — preference XML for the picker.

  **Adapt:** The two-mode picker pattern, the mode-change-requires-reconnect UX (NekoBox reloads via broadcast, RIPDPI can do the same via its existing supervisor reload path). **Skip:** NekoBox's PreferenceFragment XML approach (RIPDPI is Compose).

  ## Links

  - [[Epic - System HTTP proxy service mode]]

- [ ] #task Add setHttpProxy integration for VpnService on Android 10+ #repo/RIPDPI #area/system-http-proxy-service #status/backlog 🔽 [paperclip:POY-142]
  - Paperclip: POY-142 · assigned to: unassigned
  - Parent: POY-59 (Epic - System HTTP proxy service mode)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, vpn, system-proxy
  - **source:** `TaskNotes/Tasks/Add setHttpProxy integration for VpnService on Android 10+.md`
  - **epic:** Epic - System HTTP proxy service mode

  ## Summary

  Allow the VpnService builder on Android 10+ to also advertise an HTTP
  proxy to the system via `setHttpProxy(ProxyInfo.buildDirectProxy(...))`.

  ## Context

  In VPN mode, most traffic goes through TUN. But a handful of apps (and
  Android system services) honor the system HTTP proxy out-of-band. Setting
  the proxy to the local mixed inbound port gives those paths a fast-lane
  without an extra service.

  ## Acceptance criteria

  - [ ] Optional toggle in Advanced Settings: "Also advertise HTTP proxy
        to system" (default off).
  - [ ] When on and API ≥ 29, the VPN builder calls
        `setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1",
        mixedPort))`.
  - [ ] When the mixed port changes, the VPN is NOT auto-reestablished;
        the toggle change takes effect on next connect.
  - [ ] Works only in VPN mode; in Proxy mode, system proxy comes from
        the user's Android network settings, not us.
  - [ ] Bypass list for the system proxy exclusion includes `localhost`
        and the loopback range.

  ## Source references

  **NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

  - `app/src/main/java/io/nekohasekai/sagernet/bg/VpnService.kt` — search for `ProxyInfo.buildDirectProxy`. The VPN builder calls `builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", DataStore.mixedPort))` gated on Android Q (API 29) via `Build.VERSION.SDK_INT >= 29` and `DataStore.appendHttpProxy` toggle.
  - `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt` — `appendHttpProxy: Boolean` property (default off).

  **Adapt:** The API-29 gate, the default-off toggle, the localhost-loopback proxy config. **Skip:** NekoBox's `mixedPort` coupling — RIPDPI's equivalent is whatever the mixed-inbound task ([[Add mixed SOCKS5 and HTTP CONNECT inbound listener]]) wires up.

  ## Links

  - [[Epic - System HTTP proxy service mode]]


## vpn-fleet-testing-matrix

- [ ] #task Add DNS IPv6 and kill-switch release gates #repo/RIPDPI #area/vpn-fleet-testing-matrix #status/backlog ⏫ [paperclip:POY-72]
  - Paperclip: POY-72 · assigned to: unassigned
  - Parent: POY-60 (Epic - VPN fleet testing matrix and release gates)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** ripdpi
  - **tags:** task, ripdpi, vps, testing, dns, ipv6, killswitch
  - **source:** `TaskNotes/Tasks/Add DNS IPv6 and kill-switch release gates.md`
  - **epic:** Epic - VPN fleet testing matrix and release gates

  ## Summary

  Make DNS leak, IPv6 leak, and kill-switch behavior mandatory release gates for
  fleet profiles and Android client releases.

  ## Context

  The fleet should not ship profiles that connect but leak DNS/IPv6 or fail open
  when the core crashes, the network changes, or the VPN is revoked.

  ## Acceptance criteria

  - [ ] DNS tests verify virtual VPN DNS, proxied DNS through tunneled resolver,
        direct RU DNS only for direct domains, allowlisted bootstrap resolution,
        no ISP fallback on encrypted resolver outage, network-switch behavior,
        core-crash behavior, and Android Private DNS conflict handling.
  - [ ] Synthetic authoritative DNS test verifies proxy, direct, and IPv6 query
        sources using unique random domains.
  - [ ] IPv4-only tests verify no IPv6 DNS/address/route, no direct IPv6, blocked
        IPv6-only connect, and empty/blocked AAAA behavior.
  - [ ] Dual-stack tests verify `::/0` through tunnel and AAAA through tunnel.
  - [ ] Kill-switch tests cover forced disconnect, core crash, Wi-Fi/LTE switch,
        sleep/wake, and Android Always-on + Block where applicable.
  - [ ] Any DNS leak, IPv6 leak in IPv4-only mode, or Android kill-switch failure
        is a no-ship failure.

  ## Notes

  This task coordinates existing Android DNS/IPv6/kill-switch tasks into release
  gates.

  ## Links

  - [[Add DNS interceptor and split DNS leak tests]]
  - [[Add explicit IPv6 policy modes and leak tests]]
  - [[Add authoritative DNS leak-test harness]]
  - [[Add Android lockdown onboarding and kill-switch health checks]]

- [ ] #task Add captive portal and whitelist-mode test cases #repo/RIPDPI #area/vpn-fleet-testing-matrix #status/backlog 🔼 [paperclip:POY-106]
  - Paperclip: POY-106 · assigned to: unassigned
  - Parent: POY-60 (Epic - VPN fleet testing matrix and release gates)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** ripdpi
  - **tags:** task, ripdpi, vps, testing, captive-portal, whitelist
  - **source:** `TaskNotes/Tasks/Add captive portal and whitelist-mode test cases.md`
  - **epic:** Epic - VPN fleet testing matrix and release gates

  ## Summary

  Add tests for captive portal assist and whitelist/shutdown classification so
  temporary local access does not become a general DNS/direct bypass.

  ## Context

  Captive portals and whitelist-mode shutdowns can look like broken VPN. The
  client and fleet tests must distinguish controlled portal access, blocked
  foreign endpoints, and legitimate fallback modes.

  ## Acceptance criteria

  - [ ] Captive tests cover Wi-Fi with VPN off, VPN with lockdown off, Always-on +
        Block, explicit portal login assist, return to strict DNS after login, no
        general browsing during assist, and subscription fetch policy.
  - [ ] Portal assist allows only portal host/network handling and expires
        automatically.
  - [ ] Whitelist-mode tests detect all foreign endpoints failing while expected
        local/RU services remain reachable.
  - [ ] UI/diagnostic result distinguishes captive portal, whitelist suspected,
        no connectivity, and normal VPN degradation.
  - [ ] Test results do not record user browsing destinations.

  ## Notes

  Use controlled networks or agreed testers only.

  ## Links

  - [[Add captive portal DNS assist via Network object]]
  - [[Add captive-portal and whitelist-mode connection states]]
  - [[Create protocol degradation incident playbook]]

- [ ] #task Add client compatibility regression matrix for fleet profiles #repo/RIPDPI #area/vpn-fleet-testing-matrix #status/backlog 🔼 [paperclip:POY-108]
  - Paperclip: POY-108 · assigned to: unassigned
  - Parent: POY-60 (Epic - VPN fleet testing matrix and release gates)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** ripdpi
  - **tags:** task, ripdpi, vps, testing, clients
  - **source:** `TaskNotes/Tasks/Add client compatibility regression matrix for fleet profiles.md`
  - **epic:** Epic - VPN fleet testing matrix and release gates

  ## Summary

  Define a compatibility regression matrix for fleet profiles across custom
  Android, sing-box SFA, v2rayNG, NekoBox, husi, Streisand/V2Box, v2rayN, and
  sing-box CLI.

  ## Context

  Different clients parse subscriptions, route policy, DNS, TUN, IPv6, and core
  versions differently. Compatibility tests need to record both app and embedded
  core versions.

  ## Acceptance criteria

  - [ ] Shared matrix covers import, credential scope, selector, urltest, TUN,
        kill switch, DNS, IPv6, network transitions, revocation, logs, and
        update migration.
  - [ ] sing-box/SFA tests cover config check, selector/urltest, degraded
        Cloudflare exclusion, strict route, DNS hijack, rule-set update, and
        revoked profile removal.
  - [ ] v2rayNG tests cover VLESS+REALITY URI import, per-device subscription,
        VPN mode, Android lockdown, DNS/IPv6 leak checks, core update, and
        Hysteria2 fallback where present.
  - [ ] NekoBox/husi tests treat subscriptions as nodes-only unless full policy is
        explicitly supported and verify routing/DNS separately.
  - [ ] iOS tests cover URI/subscription import, manual fallback, DNS/IPv6 leak,
        sleep/wake, Wi-Fi/LTE, and app update persistence.
  - [ ] v2rayN tests cover Xray core, sing-box core if used, TUN elevation,
        Windows firewall kill switch, DNS/IPv6 leak, and core update behavior.
  - [ ] Custom Android tests cover VpnService lifecycle, `protect()` for tunnel
        sockets, `onRevoke()`, virtual DNS, no `allowBypass`, IPv4-only behavior,
        package visibility, foreground service resilience, no log secrets, and
        profile signature/expiry/revocation.

  ## Notes

  This matrix coordinates existing RIPDPI client tasks with fleet profile
  compatibility.

  ## Links

  - [[Add Xray VPN client regression matrix]]
  - [[Add Android VPN leak-test instrumentation matrix]]
  - [[Add per-device subscription token UX and shared-link warnings]]
  - [[Epic - Fail-closed Android VPN policy engine]]

- [ ] #task Add fleet release gating and cadence policy #repo/RIPDPI #area/vpn-fleet-testing-matrix #status/backlog ⏫ [paperclip:POY-117]
  - Paperclip: POY-117 · assigned to: unassigned
  - Parent: POY-60 (Epic - VPN fleet testing matrix and release gates)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** vps
  - **tags:** task, vps, ripdpi, testing, release
  - **source:** `TaskNotes/Tasks/Add fleet release gating and cadence policy.md`
  - **epic:** Epic - VPN fleet testing matrix and release gates

  ## Summary

  Define daily, weekly, release, staging, production, and client-release gates for
  fleet and RIPDPI profile rollouts.

  ## Context

  The final policy needs clear no-ship and warn-only conditions so degraded
  profiles are demoted instead of accidentally shipped as primary paths.

  ## Acceptance criteria

  - [ ] Daily cadence covers node/service health, external TCP/443, REALITY
        non-RU connect, HTTPS 64 KB payload, cert expiry, and backup age.
  - [ ] Weekly cadence covers RU fixed tests, RU mobile tests, DNS leak, IPv6
        leak, active-probe simulation, revoked credential, and delivery token
        expiry/revocation.
  - [ ] Every release/rotation requires full predeploy suite, staging deploy,
        non-RU smoke, RU fixed/mobile smoke, relevant client regression, old
        profile revocation, and fresh backup after deploy.
  - [ ] Production deploy requires staging success, non-RU smoke, at least one RU
        fixed pass, at least one RU mobile pass, DNS leak pass, IPv6 leak pass,
        Android kill-switch pass for primary Android profile, old revoked
        credential failure, and delivery token TTL/revocation pass.
  - [ ] Client release requires Android API matrix, Wi-Fi/LTE, captive portal,
        IPv6-enabled network, UDP-blocked network, app/core/schema migration,
        package visibility/per-app routing, logcat no secrets, and crash reports
        no secrets.
  - [ ] No-ship policy includes Xray validation, sing-box validation, firewall
        validation, DNS leak, IPv6 leak, kill-switch failure, revoked credential
        still connecting, token/full URL logs, public panel response, and primary
        plus fallback on same burned provider/ASN.
  - [ ] Warn-only policy covers partial Hysteria2 UDP failure, Cloudflare path
        failure with non-CF paths healthy, and one degraded RU operator with
        selector avoiding it.

  ## Notes

  The release gate should produce a short sanitized report, not raw probe logs.

  ## Links

  - [[vps-fleet-testing-matrix-2026-05-01]]
  - [[Add client compatibility regression matrix for fleet profiles]]
  - [[Add DNS IPv6 and kill-switch release gates]]


## xray-vpn-client-mode

- [ ] #task Add Xray VPN client regression matrix #repo/RIPDPI #area/xray-vpn-client-mode #status/backlog 🔼 [paperclip:POY-97]
  - Paperclip: POY-97 · assigned to: unassigned
  - Parent: POY-61 (Epic - Xray VPN client mode)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, testing, ripdpi, vpn, xray
  - **source:** `TaskNotes/Tasks/Add Xray VPN client regression matrix.md`
  - **epic:** Epic - Xray VPN client mode

  ## Summary

  Add focused automated coverage for the first Xray VPN client integration.

  ## Context

  The risky parts are lifecycle, config rendering, socket protection, DNS loops,
  provider telemetry, and Android VPN handoff. Tests should lock those down before
  Xray mode becomes a default or recommended fallback.

  ## Acceptance criteria

  - [ ] Config golden tests cover VLESS/REALITY, XHTTP, invalid combinations, and
        redaction.
  - [ ] Service tests cover Xray startup failure, readiness timeout, stop,
        restart, and handover behavior.
  - [ ] Protect-fd tests prove Xray dialer/listener sockets use the Android VPN
        protection path.
  - [ ] DNS-loop regression proves provider bootstrap DNS does not re-enter TUN.
  - [ ] Device/emulator smoke test verifies active VPN traffic exits through the
        Xray outbound path.
  - [ ] CI or documented manual lanes identify which Xray tests need network,
        emulator, or private fixture dependencies.

  ## Notes

  Keep private endpoints out of fixtures. Use local synthetic fixtures or
  operator-provided private test profiles outside the vault.

  ## Links

  - [[Epic - Xray VPN client mode]]
  - [[Bridge TUN traffic through Xray local inbound]]
  - [[Surface Xray diagnostics and telemetry]]
  - [[ripdpi-android-xray-vpn-client-plan-2026-04-24]]

- [ ] #task Add Xray profile UX and import flow #repo/RIPDPI #area/xray-vpn-client-mode #status/backlog 🔼 [paperclip:POY-98]
  - Paperclip: POY-98 · assigned to: unassigned
  - Parent: POY-61 (Epic - Xray VPN client mode)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, vpn, xray, ux
  - **source:** `TaskNotes/Tasks/Add Xray profile UX and import flow.md`
  - **epic:** Epic - Xray VPN client mode

  ## Summary

  Add the user-facing flow for selecting Xray VPN mode and importing or editing
  initial Xray profiles.

  ## Motivation

  VPN client support needs to fit the existing Mode Editor, Settings, and
  onboarding model without exposing low-level config trivia or secrets.

  ## Scope

  - In scope: provider selection, profile import, validation errors, selected
    route summary, onboarding validation, and localized copy.
  - Out of scope: subscription management, server purchase/provisioning, and
    multi-provider catalogs.

  ## Acceptance criteria

  - [ ] Mode Editor can select Xray-backed VPN mode separately from native
        RIPDPI direct/proxy modes.
  - [ ] Import supports at least the first approved share/config shapes and
        fails closed on unsupported or unsafe fields.
  - [ ] Validation errors are actionable but redact credentials and endpoints.
  - [ ] Onboarding can validate an Xray profile as the chosen mode before finish.
  - [ ] Compose/UI tests cover selection, validation failure, and successful
        imported-profile state.

  ## Design notes

  Use provider capability labels rather than protocol jargon wherever possible:
  VPN privacy, relay, split/full tunnel, anti-DPI, and DNS protection.

  ## Risks / open questions

  - Imported raw JSON can become an expert-only escape hatch; the first UX should
    prefer typed forms and known share links.

  ## Links

  - [[Epic - Xray VPN client mode]]
  - [[Render validated Xray client configs]]
  - [[ripdpi-android-xray-vpn-client-plan-2026-04-24]]

- [ ] #task Bridge TUN traffic through Xray local inbound #repo/RIPDPI #area/xray-vpn-client-mode #status/backlog ⏫ [paperclip:POY-160]
  - Paperclip: POY-160 · assigned to: unassigned
  - Parent: POY-61 (Epic - Xray VPN client mode)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, vpn, xray, tunnel
  - **source:** `TaskNotes/Tasks/Bridge TUN traffic through Xray local inbound.md`
  - **epic:** Epic - Xray VPN client mode

  ## Summary

  Route Android VPN TUN traffic through Xray's local inbound for the first Xray
  VPN client milestone.

  ## Motivation

  RIPDPI already has a well-tested TUN-to-SOCKS path with DNS interception,
  handover handling, and telemetry. Using Xray as the local inbound preserves
  that path while adding Xray outbound support.

  ## Scope

  - In scope: local Xray SOCKS/HTTP inbound selection, tunnel config handoff,
    auth/localhost hardening, DNS-loop avoidance, handover restart behavior, and
    traffic-smoke validation.
  - Out of scope: shipping direct `libXray.SetTunFd` until lifecycle and
    telemetry parity are proven.

  ## Acceptance criteria

  - [ ] VPN startup can select Xray as the tunnel's upstream local endpoint.
  - [ ] Xray outbound sockets and DNS are protected so provider traffic does not
        loop into the TUN fd.
  - [ ] Existing tunnel telemetry remains available when the upstream endpoint is
        Xray instead of RIPDPI-native proxy.
  - [ ] Network handover restarts both Xray and tunnel when the local inbound or
        provider route changes.
  - [ ] A local/device smoke test proves traffic exits through the Xray outbound.

  ## Design notes

  Keep the direct `SetTunFd` path as an explicit follow-up decision, not an
  accidental first implementation.

  ## Risks / open questions

  - Xray local inbound authentication support must be validated before exposing
    any localhost listener beyond the tunnel's private use.
  - DNS interception ownership needs one clear source of truth: RIPDPI tunnel,
    Xray DNS, or a deliberately split model.

  ## Links

  - [[Epic - Xray VPN client mode]]
  - [[Run Xray as managed VPN relay runtime]]
  - [[ripdpi-android-xray-vpn-client-plan-2026-04-24]]

- [ ] #task Package libXray for Android ABIs #repo/RIPDPI #area/xray-vpn-client-mode #status/backlog ⏫ [paperclip:POY-215]
  - Paperclip: POY-215 · assigned to: unassigned
  - Parent: POY-61 (Epic - Xray VPN client mode)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, vpn, xray, libxray, build
  - **source:** `TaskNotes/Tasks/Package libXray for Android ABIs.md`
  - **epic:** Epic - Xray VPN client mode

  ## Summary

  Add a reproducible build/import path for `libXray` Android artifacts across
  RIPDPI's supported ABIs.

  ## Motivation

  The app currently builds repo-owned Rust native libraries through Gradle. Xray
  will introduce Go/gomobile-built native artifacts, so the build needs a pinned,
  auditable path before runtime work begins.

  ## Scope

  - In scope: version pinning, build script or vendored artifact policy, ABI
    outputs, license notices, Gradle wiring, APK size checks, and CI smoke.
  - Out of scope: server provisioning and non-Xray provider packaging.

  ## Acceptance criteria

  - [ ] `libXray` and Xray-core versions are pinned with a documented stable vs
        canary update policy.
  - [ ] Android artifacts cover RIPDPI's release ABI set and local iteration ABI
        defaults without hardcoding SDK/NDK values outside existing build
        properties.
  - [ ] Build output is wired into `:core:engine` or an approved adjacent module
        without committing generated binary churn unexpectedly.
  - [ ] License/notice obligations for libXray, Xray-core, Go/gomobile output,
        and bundled geo assets are captured.
  - [ ] CI or a local verification task fails on missing ABI artifacts, version
        drift, or oversized native payloads.

  ## Design notes

  Official libXray recommends its build script and notes Android support through
  `gomobile`; keep the packaging path close to upstream unless there is a clear
  reproducibility problem.

  ## Risks / open questions

  - `libXray` compatibility is tied to the latest Xray-core release, which may
    conflict with a conservative stable app-release cadence.
  - Geo assets and MPH cache files can dominate size if bundled uncritically.

  ## Links

  - [[Epic - Xray VPN client mode]]
  - [[ripdpi-android-xray-vpn-client-plan-2026-04-24]]
  - [[Recurring upstream watch for xray-core REALITY ECH XHTTP changes]]

- [ ] #task Render validated Xray client configs #repo/RIPDPI #area/xray-vpn-client-mode #status/backlog ⏫ [paperclip:POY-227]
  - Paperclip: POY-227 · assigned to: unassigned
  - Parent: POY-61 (Epic - Xray VPN client mode)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, vpn, xray, vless, xhttp
  - **source:** `TaskNotes/Tasks/Render validated Xray client configs.md`
  - **epic:** Epic - Xray VPN client mode

  ## Summary

  Create the RIPDPI profile model, validation, and JSON renderer for initial
  Xray VPN client configs.

  ## Motivation

  Xray can run arbitrary JSON, but RIPDPI needs a safe product surface. The first
  implementation should render known-good VLESS/REALITY and XHTTP shapes, then
  gate raw JSON import behind validation and secret-safe error reporting.

  ## Scope

  - In scope: VLESS/REALITY, XHTTP, local inbound, DNS/protect settings,
    metrics/API choice, config validation, redaction, and golden tests.
  - Out of scope: paid provider catalogs, live endpoint storage in task/wiki
    notes, and automatic server provisioning.

  ## Acceptance criteria

  - [ ] Kotlin profile model covers the initial VLESS/REALITY and XHTTP fields
        needed for client startup.
  - [ ] Renderer emits local inbound and outbound config compatible with the
        chosen tunnel topology.
  - [ ] `libXray.TestXray` or equivalent validation is called before saving or
        starting imported profiles.
  - [ ] Diagnostics and logs redact UUIDs, private keys, passwords, server
        addresses, and live endpoints.
  - [ ] Golden tests cover valid profiles, invalid combinations, and redaction.

  ## Design notes

  Reuse the existing `:xray-protos` and Xray API scanner knowledge where it helps,
  but keep runtime config generation separate from external Xray API inspection.

  ## Risks / open questions

  - XHTTP and REALITY combinations have changed upstream before; keep validation
    version-aware.
  - Raw JSON import may need a restricted first release to avoid exposing unsafe
    routing or logging surfaces.

  ## Links

  - [[Epic - Xray VPN client mode]]
  - [[vless-reality-stack-research-2026-04-22]]
  - [[ripdpi-android-xray-vpn-client-plan-2026-04-24]]

- [ ] #task Run Xray as managed VPN relay runtime #repo/RIPDPI #area/xray-vpn-client-mode #status/backlog ⏫ [paperclip:POY-232]
  - Paperclip: POY-232 · assigned to: unassigned
  - Parent: POY-61 (Epic - Xray VPN client mode)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, vpn, xray, lifecycle
  - **source:** `TaskNotes/Tasks/Run Xray as managed VPN relay runtime.md`
  - **epic:** Epic - Xray VPN client mode

  ## Summary

  Implement a supervised Xray runtime that starts, reports readiness, exposes
  health, and stops cleanly inside RIPDPI's Android service layer.

  ## Motivation

  Xray must behave like the existing managed proxy/relay runtimes: no ambiguous
  "running" state before listeners bind, no silent crashes, no leaked native
  resources, and no recursive VPN socket loops.

  ## Scope

  - In scope: `RunXrayFromJSON` startup, `StopXray` shutdown, protect-controller
    registration, DNS initialization, readiness probing, state mapping, telemetry
    snapshots, and supervisor exit causes.
  - Out of scope: UI profile editing and non-Xray providers.

  ## Acceptance criteria

  - [ ] Runtime registers libXray dialer/listener protection before starting
        Xray.
  - [ ] Startup waits for a concrete listener or verified Xray state before VPN
        tunnel handoff.
  - [ ] Stop path is bounded, idempotent, and reports typed clean/failed stop
        causes.
  - [ ] Xray version and basic provider state flow into service telemetry without
        exposing profile secrets.
  - [ ] Unit or service tests cover startup failure, invalid config, late stop,
        and crash/exit mapping.

  ## Design notes

  Map Xray readiness and stop outcomes into the same service-level language used
  for proxy, relay, WARP, and tunnel runtimes.

  ## Risks / open questions

  - libXray wrapper calls may be process-global; the app should assume only one
    active Xray instance until proven otherwise.
  - Metrics/API mode may require a child process according to upstream notes;
    do not rely on it until tested on Android.

  ## Links

  - [[Epic - Xray VPN client mode]]
  - [[Package libXray for Android ABIs]]
  - [[Render validated Xray client configs]]
  - [[ripdpi-android-xray-vpn-client-plan-2026-04-24]]

- [ ] #task Surface Xray diagnostics and telemetry #repo/RIPDPI #area/xray-vpn-client-mode #status/backlog 🔼 [paperclip:POY-248]
  - Paperclip: POY-248 · assigned to: unassigned
  - Parent: POY-61 (Epic - Xray VPN client mode)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, feature, ripdpi, vpn, xray, diagnostics
  - **source:** `TaskNotes/Tasks/Surface Xray diagnostics and telemetry.md`
  - **epic:** Epic - Xray VPN client mode

  ## Summary

  Expose Xray provider state in Home, Diagnostics, exports, and service
  telemetry.

  ## Motivation

  The app should make Xray failures diagnosable without turning user profiles or
  live endpoints into logs. Existing diagnostics already distinguish native
  proxy, relay, WARP, and tunnel state; Xray needs the same typed treatment.

  ## Scope

  - In scope: runtime snapshot fields, Xray version, readiness, listener state,
    outbound health, config-validation errors, ping/stat probes where safe, and
    redacted export summaries.
  - Out of scope: full packet capture of tunneled traffic and endpoint disclosure
    in logs or task notes.

  ## Acceptance criteria

  - [ ] Home connection stages identify Xray provider readiness and provider
        failures distinctly from tunnel failures.
  - [ ] Diagnostics can run a provider-path check through the active Xray mode.
  - [ ] Export/share summaries redact profile credentials and live endpoints.
  - [ ] Xray API/stat probing is used only when enabled safely for the Android
        runtime topology.
  - [ ] Regression fixtures cover provider healthy, config invalid, protect
        failure, DNS-loop suspected, and outbound unreachable states.

  ## Design notes

  If Xray metrics/API mode is not safe in-process, prefer wrapper `Ping`,
  `XrayVersion`, listener readiness, and existing tunnel telemetry for the first
  build.

  ## Risks / open questions

  - Provider diagnostics can accidentally become a reachability scanner. Keep it
    user-triggered and tied to the active profile.

  ## Links

  - [[Epic - Xray VPN client mode]]
  - [[Run Xray as managed VPN relay runtime]]
  - [[ripdpi-android-xray-vpn-client-plan-2026-04-24]]
