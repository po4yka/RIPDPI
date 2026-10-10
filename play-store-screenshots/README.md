# RIPDPI Play Store Screenshots

Next.js + Puppeteer renderer that produces Google Play Store marketing assets for the RIPDPI Android app:

- Six 1080×1920 phone screenshots
- One 1024×500 feature graphic
- Localized in 7 locales: `en`, `ru`, `es`, `de`, `fr`, `fa`, `zh-CN`

Marketing output is committed to `../docs/screenshots/`. README galleries use the direct Android frames in `../docs/screenshots/ui/<locale>/`.

## Quick start

```bash
bun install
bun pm trust puppeteer    # one-time, allows Chromium download
bun run dev               # iterate locally at localhost:3000
```

Render a single slide at full resolution:

```
http://localhost:3000/?slide=N&lang=XX
```

Where `N` is `1..6` or `fg` (feature graphic), and `XX` is one of the 7 locale codes.

## Capture the Android sources

Use a dedicated emulator. The capture command resets RIPDPI app data and changes the emulator locale, theme, motion settings, and status bar. Do not use a personal device or an existing acceptance emulator.

Create a fresh Pixel 10 Pro XL AVD with the installed API 37 system image. Keep its physical 1344×2992 screen and 480 dpi density. Start it without snapshots. Build or obtain the pinned libXray producer artifacts first; see [the native bootstrap](../native/xray/README.md). The command verifies the AAR before it builds and installs the current `githubFullDebug` APK. It uses the emulator ABI.

From this directory:

```bash
python3 scripts/capture-android.py \
  --serial emulator-5580 \
  --xray-artifacts /absolute/path/to/native/xray/artifacts
python3 scripts/validate-source-captures.py
```

Inspect all 21 frames at full resolution before rendering. The command captures `en`, `ru`, `es`, `de`, `fr`, `fa`, and `zh-CN`. Each marketing locale uses its own Android UI. Some bundled profile names and transport group labels remain English in the current app; the captures retain them. The direct README copies retain the captured pixels. Hindi and Brazilian Portuguese README galleries use an explicitly labelled English fallback.

The captured states are UI illustrations:

- **Home:** disconnected, with the actual setup advisory visible.
- **Diagnostics:** the Scan setup before a run, including the direct-network interruption and restoration warning. The view scrolls when needed to show the scan action. No measured results are shown.
- **Relay:** the Profile editor at the relay fields, with the relay toggle enabled as an unsaved edit. This is a scrolled view of the editor, not its complete form. No credentials are entered and no connection is started.

The debug automation contract selects the route, grants its permission preset, loads `settings_ready`, keeps the service idle, and disables motion. Android demo mode sets 12:00, battery 100%, and hides notifications. These frames do not establish VPN, network, server, or physical-device acceptance.

`public/screenshots/source-capture.json` records the build revision, APK hash and variant, verified libXray AAR and manifest hashes, device, API, theme, locales, routes, capture time, state, and raw-file hashes. The current capture uses a dedicated API 37 emulator; API 37 is a capture environment, not the minimum supported Android version.

## Batch capture for release

```bash
bun run capture:prod
```

This first checks the Android source manifest and UI input hashes, then builds the prod bundle, boots a server on port 3099, captures all 56 assets (7 slides × 8 paths — 7 locales + root English fallback) into `../docs/screenshots/`, validates them against Google Play constraints (RGB no-alpha, ≤8 MB, correct dimensions), and tears down the server.

## Project layout

| Path | Role |
|------|------|
| `src/app/page.tsx` | Single-file slide generator + `SLIDES` registry |
| `src/copy/{lang}.ts` | Per-locale copy dictionaries |
| `capture.mjs` | Puppeteer driver — loops over all locales and slides |
| `scripts/capture-prod.mjs` | Orchestrates build → server → capture → teardown |
| `scripts/validate-play-store.mjs` | Zero-dep PNG header validator |
| `scripts/capture-android.py` | Builds and captures the real app on a dedicated emulator |
| `scripts/validate-source-captures.py` | Checks raw-frame integrity, locale selection, and UI input freshness |
| `public/screenshots/<locale>/` | Current Stage-1 raw Home, Scan setup, and Profile editor captures |
| `public/screenshots/source-capture.json` | Device and producer provenance for the current three routes |
| `../docs/screenshots/ui/<locale>/` | Direct copies of those Android captures for the README galleries |
| `public/app-icon.png` | Copied from `app/src/main/ic_launcher-playstore.png` |
| `../docs/screenshots/` | Final Puppeteer output (committed to git) |

## Brand and design

Marketing slides honor the root `DESIGN.md` monochrome-first system: `#FAFAFA` background, `#1A1A1A` foreground, restrained status color, no decorative gradients. The dark `BRAND` token set is reserved for at most 1–2 rhythm-break slides as a strict inversion (currently Slide 6 only).

See [`AGENTS.md`](AGENTS.md) for the entry-point cheat sheet and [`../.claude/skills/play-store-screenshots/SKILL.md`](../.claude/skills/play-store-screenshots/SKILL.md) for the full design rules, Google Play constraints, copy framework, and narrative arc.

## Caveats

- Production build is mandatory for Puppeteer — `bun run dev` HMR hangs the headless browser.
- Only the three routes listed in the source manifest are current device captures. Other root PNGs (`home-dark`, `settings`, `history`, and other unused files) are retained legacy inputs. The renderer does not use them. Recapture them before any future use.
- UI freshness uses sorted source paths and SHA-256 content hashes from app main/full/debug/github resources, core main sources, and Android build inputs. It requires no historical Git object and works in shallow CI checkouts. A changed input fails preflight with the paths and the recapture command. Test sources and prose are excluded. The build revision remains provenance; it is not a freshness dependency.
- Hash validation detects changed sources and files. It does not replace the full-resolution visual review after capture.
- Google Play rejects RGBA — the `backgroundColor: "#FAFAFA"` in `toPng` options and the Puppeteer `clip` flatten alpha. Do not remove either.
