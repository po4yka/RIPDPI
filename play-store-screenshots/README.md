# RIPDPI Play Store artwork

The Next.js + Puppeteer renderer produces six 1080×1920 phone posters and one 1024×500 feature graphic in `en`, `ru`, `es`, `de`, `fr`, `fa`, and `zh-CN`.

Final RGB PNGs are committed to `../docs/screenshots/`. Root files are exact English aliases. All nine README galleries use the posters. Unchanged Android frames remain in `public/screenshots/<locale>/` and `../docs/screenshots/ui/<locale>/` for inspection.

## Preview and export

```bash
bun install --frozen-lockfile
bun run dev
```

Open `http://localhost:3000/?lang=ru`. A single full-size asset uses `?slide=N&lang=XX`, where `N` is `1..6` or `fg`.

Click a preview or **Export All** to download full-size quality-1 JPEGs. Each export restores the preview's original style on success and failure. Browser canvas PNGs can include an alpha channel even with an opaque background, so interactive export uses JPEG. Batch export uses verified RGB PNG.

For batch output:

```bash
bun run capture:prod
```

This validates the source manifest and current UI input hashes, builds the production bundle, starts a server on port 3099, checks and captures all 49 locale pages, publishes 56 files including English aliases, validates the PNGs, and stops the server. A failed locale layout does not replace existing output. The owning driver removes only known obsolete generated names.

If the Puppeteer browser is unavailable, use an installed compatible Chromium through `PUPPETEER_EXECUTABLE_PATH`; do not modify the dependency lockfile to bypass a local browser cache problem.

## Capture real Android screens

Use a dedicated emulator. The first capture route clears historical measurements and seeds RIPDPI settings. Later routes and locales reseed settings but retain the new real measurements. The command also changes the emulator locale, theme, and motion settings. The screenshots retain the natural Android status bar and capture time. It does not reset operating-system VPN consent. Do not use a personal device or an acceptance emulator.

Build the pinned libXray artifacts as described in [the native bootstrap](../native/xray/README.md). The capture command verifies the AAR, builds and installs the current `githubFullDebug` APK, then captures the actual app. Grant actual Android VPN consent through the normal app prompt before capture. The script requires the Android `ACTIVATE_VPN` operation to be allowed. The debug permission preset does not grant operating-system consent. On Android API 37, the script also grants the local-network permission. After the connected Home frame, it uses the normal Home Disconnect control before the raw network check. The current artwork uses a physical 1080×1800 Android viewport. The renderer follows each source image's intrinsic aspect ratio; it does not crop, stretch, or repaint UI pixels.

```bash
python3 scripts/capture-android.py \
  --serial emulator-5556 \
  --xray-artifacts /absolute/path/to/native/xray/artifacts
python3 scripts/validate-source-captures.py
```

The base display uses 360 dpi and font scale 1.0. Diagnostics and strategy configuration use that profile. Home uses 290 dpi and font scale 0.8 in all locales. Review each frame to confirm that the real strategy description and all measurement captions fit in the phone viewport. The relay picker uses 260 dpi / 1.5 by default, with localized overrides. DNS and backup profiles also depend on the locale. [The owning profile constants](scripts/source_captures.py) define these settings; the manifest records each frame's actual readback. Capture cleanup restores the base density and font scale on success and failure.

| Locale | Relay: dpi / font scale | DNS: dpi / font scale | Backup: dpi / font scale |
|---|---|---|---|
| en | 264 / 1.5 | 360 / 1 | 480 / 0.75 |
| ru | 260 / 1.5 | 300 / 1.18 | 460 / 0.7826087 |
| es | 260 / 1.5 | 290 / 1.24 | 460 / 0.7826087 |
| de | 260 / 1.5 | 290 / 1.2 | 430 / 0.8372093 |
| fr | 260 / 1.5 | 290 / 1.2 | 460 / 0.7826087 |
| fa | 250 / 1.56 | 300 / 1 | 440 / 0.8181818 |
| zh-CN | 255 / 1.5294118 | 360 / 0.8571429 | 500 / 0.72 |

Home capture requires at least two observed RTT samples. Home starts at the top of its content. The manifest records its observed native scroll offset, which must remain within four pixels of zero. DNS capture focuses the complete custom DoH form and IPv6 controls. Its prefilled values are not applied. Native scroll focus is recorded per locale. Backup capture scrolls French by 12 pixels and Chinese by 16 pixels to keep the restore card whole. The import button must move by the requested distance within four pixels; the manifest records the observed offset for every locale.

Inspect all 42 source frames at full size. Check the captured state and whole content blocks, not just the PNG dimensions. In particular, the diagnostics poster must show an actual completed check, not demo results or the run warning screen. Connection data must come from the real service. Do not use a simulated connected preset. A warning that applies to the captured state must stay visible.

`public/screenshots/source-capture.json` records the APK/build revision, verified libXray hashes, device/API/viewport, routes, states, capture time, UI input hashes, and source file hashes. It is the source of truth for the observed connection and check results. Emulator results do not establish physical-device or carrier-level acceptance. A failed check must retain its actual classification; artwork must not turn it into a success.

Display-only frame retakes retain the original APK/build producer and the other frame hashes. The manifest records each retake in `frameRecaptures`, including the tool revision, actual time, and old/new image hashes. The source validator checks this receipt history as well as all retained images.

Generic user-visible section and built-in profile names are localized in the app. Protocol names and user-defined names remain unchanged. The seven artwork locales use their own Android frames. Hindi and Brazilian Portuguese README galleries retain an explicitly labelled English fallback.

## Hosted source refresh

Run **Play Store Screenshots** manually on the intended branch with
`recapture_android=true` to build the current app and pinned native artifacts,
boot a dedicated 1080×1800 / 360 dpi API 37 emulator, accept the normal Android
VPN consent dialog, and capture real source frames. This is an explicit,
resource-intensive refresh, not a nightly permission or automatic commit.
The job has read-only repository permissions and does not publish to a store.
It temporarily grants only the capture user access to the disposable runner’s
`/dev/kvm`, records the original owner/mode before mutation, and restores and
verifies them after emulator shutdown, including on failed capture runs.

Only a successful full capture and source validation produces the
`android-source-captures` artifact. The renderer consumes those exact frames and
produces the separate `play-store-screenshots` artifact. Download both artifacts
from the same successful run, preserve their repository-relative paths, inspect
all 42 source frames and 49 unique posters/banner assets, and commit the generated
files with their `source-capture.json` provenance. Then run the normal workflow
with the input disabled to verify the committed files. Never repair freshness by
editing hashes or reusing an unrelated APK.

The default PR and scheduled modes still fail closed on stale inputs; they never
implicitly grant consent, run Android captures, or bless old screenshots. A failed
render no longer reports retained committed files as successfully regenerated.

## Listing story

| Asset | Message | Real visual source |
|---|---|---|
| `01-hero.png` | Control your connection | Home connection state and traffic |
| `02-diagnostics.png` | Check your network | Completed network check results |
| `03-relays.png` | Choose your relay | Relay transport selection |
| `04-dns.png` | Set your DNS | Custom DoH form and IPv6 options |
| `05-strategies.png` | Tune your strategy | Strategy configuration editor |
| `06-local-tools.png` | Save your settings | Backup and restore settings |
| `feature-graphic.png` | Check your connection path | Brand group and an editorial phone → network → server diagram |

Every phone poster contains one complete, unchanged feature frame. All six features are shown in the app; protocol-only diagrams and repeated Home illustrations are not used. The source frame is 840 px wide on each 1080 px poster. Top and bottom captions alternate, with different alignments. The sixth poster uses the dark brand palette, while the real app frame retains its captured light theme.

Headlines use 100 px type, and explanatory copy uses 48 px type. At a 260 px poster width, the app frame is about 202 px wide and explanatory type is at least 11.5 px. README galleries use larger posters, two per row. The banner has one short action, a small brand icon, and a clear connection path diagram. It does not use a miniature app screen or imitate measured data.

Persian copy uses bundled **Vazirmatn v33.003**, regular and bold. Chinese copy uses a pinned local **Noto Sans SC** variable subset. The sources, SHA-256 values, and SIL OFL 1.1 licenses are in [`public/fonts`](public/fonts/README.md). The RTL brand group and banner composition mirror together. Other locales retain Geist Sans. These fonts are marketing assets, not Android dependencies.

## Verification

Capture waits for font readiness and image decoding. It rejects page errors, clipped text, text over real UI, image distortion, a mismatch with the source manifest viewport, and a frame smaller than 824 px. Each phone poster must contain one real frame. It also rejects small marketing text, headlines longer than two lines, and a missing Persian font. Chinese copy must pass the pinned font hash and glyph coverage checks. Chrome must report the local Chinese font for actual rendered Chinese text; system fallback fails the capture. Run `bun run test:fonts` for the focused positive and negative font checks.

The sum of visible marketing text bounding boxes must fit within 20% of each canvas. This conservative layout measure does not certify approval by Google Play. Strict output validation checks the complete 56-file set, 8-bit RGB without alpha, exact dimensions, a maximum size of 8 MB, and byte-identical root English aliases.

Before publication, review all 49 unique assets at full size and at 260 px phone / 360 px banner width. Check actual UI meaning, scroll boundaries, disabled states, language, spacing and readability. Automated geometry checks do not detect a badly chosen source viewport.

## Project layout

| Path | Role |
|---|---|
| `src/app/page.tsx` | Six posters, feature graphic, preview and export |
| `src/app/layout.tsx` | Geist and pinned local Persian and Chinese font loading |
| `src/copy/{lang}.ts` | Localized headings and descriptions |
| `capture.mjs` | Fail-closed layout gate and staged batch output |
| `scripts/capture-prod.mjs` | Production server lifecycle |
| `scripts/capture-android.py` | Actual Android capture flow |
| `scripts/validate-source-captures.py` | Frame integrity, locale and UI freshness |
| `scripts/validate-play-store.mjs` | Strict PNG and file-set checks |
| `scripts/marketing-fonts.mjs` | Pinned Chinese font coverage and actual Chrome font checks |
| `public/screenshots/source-capture.json` | Capture provenance and observed states |
| `public/fonts/` | Pinned Persian and Chinese fonts and licenses |
| `public/app-icon.png` | Canonical app icon |
| `../docs/screenshots/` | Generated store artwork |

Marketing follows `../DESIGN.md`: flat `#FAFAFA` and `#1A1A1A`, restrained status color, and no gradients. Legacy raw root PNGs are not used. Recapture any additional screen before using it. UI freshness checks sorted Android source paths and SHA-256 content hashes; a change fails preflight with the recapture command. Prose and test files are excluded.
