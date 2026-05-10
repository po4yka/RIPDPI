---
title: Bundle DPI Probe Target Assets (tcp16.json, domains.txt, whitelist_sni.txt)
type: task
status: backlog
area: data
priority: high
owner: unassigned
parent: dpi-probe-parity-epic
blocks: [add-dns-integrity-checker, add-domain-reachability-scanner, add-tcp16-fat-header-dpi-probe, add-whitelist-sni-finder]
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Bundle DPI Probe Target Assets (tcp16.json, domains.txt, whitelist_sni.txt) #repo/RIPDPI #area/data #status/backlog ⏫

## Objective

Bundle three asset files from dpi-detector into `core/diagnostics` and expose them via a typed `DpiAssetLoader` that loads each file at runtime with caching and supports user-provided overrides.

## Context

dpi-detector ships three data files that drive its probes. RIPDPI needs the same data bundled as Android assets:

- **`tcp16.json`** — 140 CDN/hosting IP targets across 20+ ASNs (Cloudflare, Akamai, AWS, Hetzner, Fastly, etc.) with fields: `id`, `asn`, `provider`, `ip`, `port`, optional `sni`
- **`domains.txt`** — 40 curated known-blocked/censored domains (Instagram, Meduza, Discord, Proton, etc.)
- **`whitelist_sni.txt`** — 188 Russian domestic SNIs known to bypass DPI blocks (`vk.com`, `gosuslugi.ru`, `sber.ru`, `avito.ru`, etc.)

**Reference files:**
- `/Users/po4yka/GitRep/dpi-detector/tcp16.json`
- `/Users/po4yka/GitRep/dpi-detector/domains.txt`
- `/Users/po4oya/GitRep/dpi-detector/whitelist_sni.txt`

**RIPDPI placement:**
- Assets: `core/diagnostics/src/main/assets/dpi/tcp16.json`, `dpi/domains.txt`, `dpi/whitelist_sni.txt`
- Loader: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DpiAssetLoader.kt`
- Models: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DpiAssetModels.kt`

**User override:** if a file exists at `Context.filesDir/dpi/<filename>`, it takes precedence over the bundled asset. This allows updating targets without an app update.

## Acceptance criteria

- [ ] All 3 files copied from dpi-detector to `core/diagnostics/src/main/assets/dpi/`
- [ ] `Tcp16Target` data class: `id: String`, `asn: String`, `provider: String`, `ip: String`, `port: Int`, `sni: String?`
- [ ] `DpiAssetLoader.loadTcp16Targets(): List<Tcp16Target>` — parses `tcp16.json`; returns all 140 entries; result cached after first load
- [ ] `DpiAssetLoader.loadDomains(): List<String>` — reads `domains.txt`; skips `#` comment lines; cached
- [ ] `DpiAssetLoader.loadWhitelistSni(): List<String>` — reads `whitelist_sni.txt`; skips comments; cached
- [ ] User override: if `filesDir/dpi/tcp16.json` exists, loader reads it instead of bundled asset (same for other files)
- [ ] `DpiAssetLoader` is injectable (takes `Context` + optional `FileProvider`); Hilt module provides singleton
- [ ] Unit tests: serialize a minimal JSON fixture; assert correct `Tcp16Target` parsing; assert comment line skipping; assert override path takes precedence

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DpiAssetLoaderTest.kt`:
     - `tcp16_targets_parsed_correctly()` — inject fake `InputStream` with 2-entry JSON; assert both `Tcp16Target` objects correct; fails until loader exists
     - `domains_comment_lines_skipped()` — inject stream with `# comment\nvk.com\n`; assert result = `["vk.com"]`
     - `whitelist_sni_loads_188_entries()` — inject real bundled file (test resources copy); assert `size == 188`
     - `user_override_takes_precedence_over_bundled_asset()` — inject fake `filesDir` path with custom file; assert loader reads override
     - `cached_after_first_load()` — call `loadDomains()` twice; assert same list instance returned (reference equality)
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 5 fail
3. **Copy assets** from dpi-detector; implement `DpiAssetLoader`, `Tcp16Target`, Hilt module
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract `loadAssetOrOverride(name)` into a private helper

## Definition of done

All 5 unit tests green. `DpiAssetLoader` injectable via Hilt. All 3 asset files present in APK (verify with `aapt dump`). Override mechanism tested.
