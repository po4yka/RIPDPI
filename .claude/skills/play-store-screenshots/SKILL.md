---
name: play-store-screenshots
description: "Generate Google Play screenshots, marketing images, and feature graphics for RIPDPI."
---

# Google Play Screenshots Generator (RIPDPI)

## Overview

Build or update the Next.js page in `play-store-screenshots/` that renders Google Play Store screenshots as **advertisements** (not UI showcases) and exports them via `html-to-image` + Puppeteer batch capture at Google Play's required resolutions.

**Google Play constraints:**
- Max 8 screenshots per device type (phone, 7" tablet, 10" tablet)
- Text overlay must not exceed 20% of the screenshot area
- No promotional text (pricing, rankings, awards)
- 24-bit PNG or JPEG only (no alpha transparency)
- Max 8 MB per image
- Minimum 2 screenshots to publish, 4+ recommended for visibility

## Core Principle

**Screenshots are advertisements, not documentation.** Every screenshot sells one idea. If you're showing UI, you're doing it wrong -- you're selling a *feeling*, an *outcome*, or killing a *pain point*.

## Existing Project Structure

The generator already exists at `play-store-screenshots/`. Check if it needs updating rather than scaffolding from scratch.

```
play-store-screenshots/
├── public/
│   ├── app-icon.png                  # Copied from app/src/main/ic_launcher-playstore.png
│   └── screenshots/                  # High-res app screenshots
│       ├── home-light.png            # From docs/screenshots/main.png (1080x2400)
│       ├── diagnostics.png           # From docs/screenshots/diagnostics.png (1080x2400)
│       └── settings.png             # From docs/screenshots/settings.png (1080x2400)
├── src/app/
│   ├── layout.tsx                    # Geist Sans + Geist Mono font setup
│   └── page.tsx                      # The screenshot generator (single file)
├── capture.mjs                       # Puppeteer batch capture script
└── package.json                      # next, html-to-image, puppeteer (devDep)
```

### Asset Sources

| Asset | Source | Resolution |
|-------|--------|-----------|
| App icon | `app/src/main/ic_launcher-playstore.png` | 512x512 |
| High-res screenshots | `docs/screenshots/*.png` | 1080x2400 |
| Low-res test screenshots | `app/src/test/screenshots/com.poyka.ripdpi.ui.screenshot.*.png` | 420x900 to 720x920 |

**Use only high-res screenshots (1080x2400) from `docs/screenshots/`.** The Roborazzi test screenshots are too low-resolution for Play Store quality. If a screen is only available as a test screenshot, use text-focused slides instead.

## Step 1: Confirm RIPDPI Defaults with the User

### Pre-Filled from DESIGN.md (confirm, don't ask from scratch)

All colors come from the project's design system (`DESIGN.md` / `RipDpiExtendedColors`). The Play Store screenshots use these tokens for brand consistency.

| Item | Default |
|------|---------|
| App name | RIPDPI |
| Design philosophy | Monochrome-first, semantic color for status only. Technical, precise, utilitarian. |
| Font families | Geist Sans (UI text), Geist Mono (values/configs), Geist Pixel Circle (brand mark only) |

#### Dark Theme Tokens (primary for screenshots)

```typescript
const BRAND = {
  bg: "#121212",           // background
  card: "#1A1A1A",         // card
  text: "#E8E8E8",         // foreground
  muted: "#1E1E1E",        // muted
  mutedFg: "#A3A3A3",      // mutedForeground
  accent: "#2A2A2A",       // accent
  border: "#2A2A2A",       // border
  success: "#34D399",      // success (connected, healthy)
  warning: "#FBBF24",      // warning
  error: "#F87171",        // destructive
  info: "#60A5FA",         // info
} as const;
```

#### Light Theme Tokens (for contrast slides)

```typescript
const BRAND_LIGHT = {
  bg: "#FAFAFA",           // background
  card: "#FFFFFF",         // card
  text: "#1A1A1A",         // foreground
  muted: "#F5F5F5",        // muted
  mutedFg: "#575757",      // mutedForeground
  border: "#E0E0E0",       // border
  success: "#047857",      // success
  warning: "#B45309",      // warning
  error: "#B91C1C",        // destructive
  info: "#1D4ED8",         // info
} as const;
```

#### Shape Tokens (from `RipDpiShapeTokens`)

| Token | Radius | Usage in screenshots |
|-------|--------|---------------------|
| `xl` | 16dp | Buttons, cards, text fields |
| `xlIncreased` | 20dp | Expressive interactive surfaces |
| `xxl` | 28dp | Pills, FABs |
| `full` | 50% | Avatars, status dots |

Use these for screenshot frame corners (40px = ~3.7% of 1080px width) and UI element pills.

#### Typography Reference

| Element | Family | Weight | Play Store sizing |
|---------|--------|--------|-------------------|
| Category label | Geist Sans | 600 (semibold) | ~35px at 1080w |
| Headline | Geist Sans | 700 (bold) | 103-108px at 1080w |
| Pill/badge text | Geist Mono | 600 (semibold) | 26-30px at 1080w |
| Subtext | Geist Sans | 400 (normal) | 28px at 1080w |

**M3 Expressive principle**: Prefer weight promotion (400->500->700) over size increase for emphasis.

| Feature list | 1. path optimization (proxy + VPN modes) 2. No root required 3. Advanced strategy controls (TCP, QUIC, DNS) 4. Encrypted DNS (DoH/DoT/DNSCrypt) 5. Integrated diagnostics & monitoring 6. Per-network policies 7. Works with AdGuard 8. Session telemetry & export |
| Style direction | Dark/technical, monochrome-first with semantic color accents. Marketing slides may use subtle gradients for depth (the app UI itself forbids decorative gradients, but Play Store screenshots are advertisements). |

### Ask the User

1. **Number of slides** -- "How many screenshots do you want? (Google Play allows up to 8)"
2. **Feature Graphic** -- "Shall I generate a Feature Graphic (1024x500)? It's required for Play Store listings."
3. **Localized screenshots** -- "Do you want screenshots in multiple languages? If yes, which languages?"
4. **Additional instructions** -- "Any specific requirements or preferences?"

**IMPORTANT:** If the user says "figure it out" or similar, use the defaults and proceed without asking.

### Derived (do NOT ask)

- **Background style**: subtle gradients for depth on dark slides, flat light backgrounds for contrast slides
- **Decorative elements**: radial glow orbs with brand colors, subtle grid patterns -- never circuit-board or shield motifs (too literal for a utilitarian tool)
- **Dark vs light slides**: majority dark (matches app default), 1-2 light contrast slides for visual rhythm
- **Screenshot placement**: use `top` positioning (not `bottom + translateY`) to precisely control where screenshots start below headlines

## Step 2: Set Up / Update the Project

### If project already exists

```bash
cd play-store-screenshots
bun install  # or npm install
```

Check if `public/screenshots/` has the latest high-res screenshots from `docs/screenshots/`. Copy any updated ones.

### If scaffolding new

Package manager priority: **bun > pnpm > yarn > npm**

```bash
bunx create-next-app@latest play-store-screenshots --typescript --tailwind --app --src-dir --no-eslint --import-alias "@/*"
cd play-store-screenshots
bun add html-to-image
bun add -d puppeteer
bun pm trust puppeteer  # allow postinstall to download Chromium
```

Copy assets:
```bash
mkdir -p public/screenshots
cp ../app/src/main/ic_launcher-playstore.png public/app-icon.png
cp ../docs/screenshots/main.png public/screenshots/home-light.png
cp ../docs/screenshots/diagnostics.png public/screenshots/diagnostics.png
cp ../docs/screenshots/settings.png public/screenshots/settings.png
```

### Font Setup (Next.js 16+)

Next.js 16 ships Geist fonts natively via `next/font/google`:

```tsx
// src/app/layout.tsx
import { Geist, Geist_Mono } from "next/font/google";
const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });
```

Use `var(--font-geist-sans)` and `var(--font-geist-mono)` in slide styles.

### Next.js 16 Caveats

- **`useSearchParams` requires Suspense**: Wrap the main component in `<Suspense>` to avoid build failures during static prerendering.
- **Production build for capture**: `bun run build && bun run start` -- the dev server HMR websocket causes Puppeteer and Chrome DevTools navigation timeouts.

## Step 3: Plan the Slides

### Screenshot Framework (Narrative Arc)

| Slot | Purpose | RIPDPI Suggestion |
|------|---------|-------------------|
| #1 | **Hero / Main Benefit** | Home screen on dark bg. "Browse without borders" |
| #2 | **Differentiator** | Text-focused, app icon. "One tap. No root." |
| #3 | **Core Feature** | Settings screenshot on light bg. "Your privacy. Your rules." |
| #4 | **Core Feature** | Protocol pills, text-focused. "Fine-tune every packet" |
| #5 | **Core Feature** | Diagnostics screenshot on dark bg. "See what's really happening" |
| #6 | **More Features** | Feature pills + icon. "And so much more." |

**Rules:**
- Each slide sells ONE idea
- Vary layouts -- never repeat the same template structure in adjacent slides
- Include 1-2 light contrast slides for visual rhythm
- Text overlay must not exceed 20% of the screenshot area
- Slides with no high-res screenshot available should be text-focused (feature cards, protocol pills, etc.)

## Step 4: Write Copy FIRST

### The Iron Rules

1. **One idea per headline.** Never join two things with "and."
2. **Short, common words.** 1-2 syllables. No jargon unless domain-specific.
3. **3-5 words per line.** Readable at thumbnail size.
4. **Line breaks are intentional.** Control with `<br />`.
5. **Max 20% text overlay.**

### Three Approaches (pick one per slide)

| Type | What it does | Example |
|------|-------------|---------|
| **Paint a moment** | You picture yourself doing it | "Open any site. No extra steps." |
| **State an outcome** | What your life looks like after | "The internet you were promised." |
| **Kill a pain** | Name a problem and destroy it | "No more blocked pages." |

## Step 5: Build the Page

### Architecture

The entire generator is a single `page.tsx` file:

```
page.tsx
├── Constants (PHONE_W/H, FEATURE_GRAPHIC, BRAND tokens)
├── Screenshot component (frameless, 40px border-radius, bgColor prop)
├── Caption component (label + headline)
├── Decorative components (Glow, Grid, Pill)
├── Slide container component
├── Slide1..N components (one per slide)
├── FeatureGraphicSlide component (1024x500)
├── SLIDES array (registry)
├── ScreenshotPreview (ResizeObserver scaling + click-to-export)
├── ScreenshotsPage (grid + export logic)
└── Page wrapper (Suspense boundary)
```

### Key Dimensions

```typescript
const PHONE_W = 1080;
const PHONE_H = 1920;
const FEATURE_GRAPHIC = { w: 1024, h: 500 };
```

### Screenshot Component (Frameless)

```tsx
function Screenshot({ src, alt, style, bgColor = "#ffffff" }: {
  src: string; alt: string; style?: React.CSSProperties; bgColor?: string;
}) {
  return (
    <div style={{ position: "relative", ...style }}>
      <div style={{
        width: "100%", height: "100%",
        borderRadius: 40,  // ~3.7% of 1080px, close to xl (16dp) scaled up
        overflow: "hidden",
        boxShadow: "0 12px 60px rgba(0,0,0,0.5)",
        background: bgColor,
      }}>
        <img src={src} alt={alt}
          style={{ display: "block", width: "100%", height: "100%",
            objectFit: "cover", objectPosition: "top" }}
          draggable={false} />
      </div>
    </div>
  );
}
```

### Phone Placement (Critical)

The high-res screenshots are 1080x2400 (taller than the 1080x1920 canvas). **Use `top` positioning** to control exactly where the phone starts below the headline:

```tsx
// 3-line headline (~400px) + gap = top: 520
<Screenshot src="/screenshots/home-light.png" alt="Home"
  style={{
    position: "absolute",
    top: 520,           // precise control -- no overlap
    left: "50%",
    transform: "translateX(-50%)",
    width: "76%",
    aspectRatio: "1080/2400",
  }}
/>
```

**Never use `bottom: 0` + `translateY(N%)` for 1080x2400 screenshots** -- the percentage math is hard to get right and leads to overlap or excessive gaps.

### Single-Slide Mode

Support `?slide=N` (1-6) and `?slide=fg` for headless capture:

```tsx
export default function Page() {
  return (
    <Suspense fallback={<div style={{ background: "#0a0a0a", minHeight: "100vh" }} />}>
      <ScreenshotsPage />
    </Suspense>
  );
}

function ScreenshotsPage() {
  const searchParams = useSearchParams();
  const slideParam = searchParams.get("slide");
  if (slideParam) {
    if (slideParam === "fg") return <FeatureGraphicSlide />;
    const idx = parseInt(slideParam) - 1;
    const slide = SLIDES[idx];
    if (slide) { const C = slide.component; return <C />; }
  }
  // ... grid view with export
}
```

## Step 6: Export

### Browser Export (interactive)

```typescript
import { toPng } from "html-to-image";
const opts = { width: w, height: h, pixelRatio: 1, cacheBust: true, backgroundColor: "#121212" };
await toPng(el, opts);  // warm-up call
const dataUrl = await toPng(el, opts);  // actual capture
```

### Puppeteer Batch Export (headless)

Use `capture.mjs` against the **production** build (dev server HMR causes timeouts):

```bash
bun run build && bun run start -- -p 3099 &
node capture.mjs
```

```javascript
// capture.mjs
import puppeteer from "puppeteer";
const browser = await puppeteer.launch({ headless: true });
const page = await browser.newPage();
for (const slide of SLIDES) {
  await page.setViewport({ width: slide.w, height: slide.h, deviceScaleFactor: 1 });
  await page.goto(`http://localhost:3099/?slide=${slide.param}`, { waitUntil: "load", timeout: 60000 });
  await new Promise(r => setTimeout(r, 2000));  // fonts + images
  await page.screenshot({ path: outPath, type: "png", clip: { x: 0, y: 0, width: slide.w, height: slide.h } });
}
```

Captured images go to `docs/screenshots/` for README usage.

### Key Export Rules

- **Double-call trick** for html-to-image: first call warms up fonts/images
- **backgroundColor**: Always set to strip alpha (Google Play rejects alpha PNGs)
- **Numbered filenames**: `01-hero.png`, `02-no-root.png`, etc.
- **Feature Graphic filename**: `feature-graphic.png`
- **Production server only** for Puppeteer -- dev server HMR websocket causes infinite loading

## Step 7: Final QA Gate

### Google Play Compliance

- [ ] No alpha transparency (`backgroundColor` set)
- [ ] Text overlay <= 20% of screenshot area
- [ ] No promotional pricing, rankings, or awards
- [ ] Aspect ratio valid (1920/1080 = 1.78, passes max 2:1)
- [ ] Minimum 4 screenshots
- [ ] Feature Graphic exactly 1024x500
- [ ] Each file under 8 MB
- [ ] All exports are 24-bit PNG

### Visual Quality

- [ ] No repeated layouts in adjacent slides
- [ ] No text/screenshot overlap
- [ ] Screenshots fully contained (no clipping at edges)
- [ ] At least 1 light contrast slide for rhythm
- [ ] Decorative elements don't cover app UI

### Design System Alignment

- [ ] Colors match DESIGN.md tokens (dark: `#121212` bg, `#E8E8E8` text, `#60A5FA` info; light: `#FAFAFA` bg, `#1A1A1A` text, `#1D4ED8` info)
- [ ] Font families are Geist Sans (headlines, labels) and Geist Mono (pills, badges, values)
- [ ] Weight emphasis follows M3 Expressive principle (400->500->700, not size increase)
- [ ] Pill/badge corners use design system radii (12-16px range)

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Text overlaps phone screenshot | Use `top` positioning, not `bottom + translateY` |
| Screenshot clipped at edges | Use `left/right: "4%"` not negative values |
| Blank Puppeteer captures | Must use production build (`bun run build && bun run start`) |
| useSearchParams build error | Wrap component in `<Suspense>` |
| Low-res screenshots look bad | Only use 1080x2400 from `docs/screenshots/`; text-focused slides for others |
| Decorative gradients feel wrong | DESIGN.md forbids decorative gradients in app UI but Play Store screenshots are marketing -- subtle gradients OK |
| All slides look the same | Vary: centered phone, right-offset, left-offset, text-only, pills-only |
| Copy too complex | "One second at arm's length" test; 3-5 words per line |
