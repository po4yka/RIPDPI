"use client";

import { useState, useRef, useEffect, useCallback, Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { toJpeg } from "html-to-image";
import { getCopy, LOCALES, DEFAULT_LOCALE, type Locale, type SlideCopy } from "@/copy";

// ── Constants ──────────────────────────────────────────────────────────
const PHONE_W = 1080;
const PHONE_H = 1920;
const FEATURE_GRAPHIC = { w: 1024, h: 500 };

// Light theme tokens — canonical brand palette (DESIGN.md monochrome-first).
const BRAND_LIGHT = {
  bg: "#FAFAFA",           // background
  card: "#FFFFFF",         // card
  text: "#1A1A1A",         // foreground
  muted: "#F5F5F5",        // muted
  mutedFg: "#575757",      // mutedForeground
  accent: "#E8E8E8",       // accent
  border: "#E0E0E0",       // border
  success: "#047857",      // success
  warning: "#B45309",      // warning
  error: "#B91C1C",        // destructive
  info: "#1D4ED8",         // info
  restricted: "#6B7280",   // restricted
} as const;

// Dark theme tokens — strict inversion of BRAND_LIGHT, same role mapping.
// Status colors are restrained (slightly lighter than light-theme variants for
// contrast on dark surfaces) — never bubblegum-bright.
const BRAND = {
  bg: "#1A1A1A",           // background
  card: "#1F1F1F",         // card
  text: "#FAFAFA",         // foreground
  muted: "#262626",        // muted
  mutedFg: "#A3A3A3",      // mutedForeground
  accent: "#2A2A2A",       // accent
  border: "#2A2A2A",       // border
  success: "#10B981",      // success
  warning: "#D97706",      // warning
  error: "#DC2626",        // destructive
  info: "#3B82F6",         // info
  restricted: "#6B7280",   // restricted
} as const;

// Marketing copy is separate from the unchanged Android pixels.
const marketingFont = (copy: SlideCopy) => copy.locale === "fa"
  ? "var(--font-vazirmatn), sans-serif"
  : "var(--font-geist-sans), Arial, sans-serif";

function Slide({ children, copy, dark = false }: {
  children: React.ReactNode; copy: SlideCopy; dark?: boolean;
}) {
  return <div data-marketing-slide data-locale={copy.locale} dir={copy.dir}
    style={{ width: PHONE_W, height: PHONE_H, background: dark ? BRAND.bg : BRAND_LIGHT.bg,
      color: dark ? BRAND.text : BRAND_LIGHT.text, position: "relative", overflow: "hidden",
      fontFamily: marketingFont(copy) }}>{children}</div>;
}

function Caption({ copy, index, bottom = false, align = "start", dark = false }: {
  copy: SlideCopy; index: number; bottom?: boolean; align?: "start" | "center" | "end"; dark?: boolean;
}) {
  return <header style={{ position: "absolute", top: bottom ? 1500 : 52, left: 72, right: 72,
    textAlign: align }}>
    <h1 data-overlay-text data-headline style={{ margin: 0, fontSize: 100, fontWeight: 700,
      lineHeight: 1.08, letterSpacing: copy.locale === "fa" ? 0 : "-0.035em",
      width: "fit-content", maxWidth: "100%", marginInline: align === "center" ? "auto" :
        align === "end" ? "auto 0" : "0 auto" }}>
      {copy.headlines[index].map((line, i) => <span key={line}>{line}{i === 0 && <br />}</span>)}
    </h1>
    <p data-overlay-text data-description style={{ margin: "40px 0 0", fontSize: 48,
      lineHeight: 1.25, width: "fit-content", maxWidth: "100%", color: dark ? BRAND.mutedFg : BRAND_LIGHT.mutedFg,
      marginInline: align === "center" ? "auto" : align === "end" ? "auto 0" : "0 auto" }}>
      {copy.descriptions[index]}
    </p>
  </header>;
}

function AppCapture({ copy, src, index, left = 120, width = 840, top = 454 }: {
  copy: SlideCopy; src: string; index: number; left?: number; width?: number; top?: number;
}) {
  // Height follows the real image's intrinsic ratio. The batch gate checks dimensions
  // against the capture manifest and requires the complete frame to fit on the canvas.
  return <img data-app-capture src={`/screenshots/${copy.locale}/${src}.png`}
    alt={`${copy.labels[index]} — Android`} draggable={false}
    style={{ position: "absolute", top, left, width, height: "auto", display: "block",
      boxShadow: "0 12px 32px rgba(26,26,26,0.14)" }} />;
}

function Slide1({ copy }: { copy: SlideCopy }) {
  return <Slide copy={copy}><Caption copy={copy} index={0} align="center" />
    <AppCapture copy={copy} src="home-light" index={0} /></Slide>;
}
function Slide2({ copy }: { copy: SlideCopy }) {
  return <Slide copy={copy}><AppCapture copy={copy} src="diagnostics" index={1} left={72} top={56} />
    <Caption copy={copy} index={1} bottom /></Slide>;
}
function Slide3({ copy }: { copy: SlideCopy }) {
  return <Slide copy={copy}><Caption copy={copy} index={2} />
    <AppCapture copy={copy} src="relay" index={2} left={168} /></Slide>;
}
function Slide4({ copy }: { copy: SlideCopy }) {
  return <Slide copy={copy}><AppCapture copy={copy} src="dns-settings" index={3} left={72} top={56} />
    <Caption copy={copy} index={3} bottom align="end" /></Slide>;
}
function Slide5({ copy }: { copy: SlideCopy }) {
  return <Slide copy={copy}><Caption copy={copy} index={4} align="end" />
    <AppCapture copy={copy} src="strategies" index={4} left={72} /></Slide>;
}
function Slide6({ copy }: { copy: SlideCopy }) {
  return <Slide copy={copy} dark><AppCapture copy={copy} src="backup" index={5} top={56} />
    <Caption copy={copy} index={5} bottom align="center" dark /></Slide>;
}
// Paths from app/src/main/res/drawable/ic_launcher_foreground_ripdpi_clean.xml.
// Preserve the mark geometry; exclude only its adaptive-icon padding.
function AppMark() {
  return <svg data-app-mark aria-hidden="true" viewBox="230 168 565 680" width="48" height="58"
    fill={BRAND_LIGHT.text} style={{ display: "block", flexShrink: 0 }}>
    <path d="M500.84 175.215C502.69 174.966 509.195 174.933 511.27 174.955C573.165 175.624 635.07 199.368 678.865 243.372C706.055 270.631 725.335 304.764 734.635 342.124C743.665 378.292 742.055 404.558 742.055 441.17L742.045 519.66V755.425C745.585 755.19 749.48 755.115 753.005 755.21C770.42 755.685 787.99 751.12 787.385 775.48C786.955 792.735 788.46 811.355 786.74 828.595C786.315 832.815 780.285 838.165 776.05 838.01C767.075 838.545 758.105 838.39 749.115 838.385L698.04 838.37L527.285 838.385L350.789 838.405L286.671 838.4C275.623 838.395 264.483 838.275 253.452 838.24C234.251 838.19 238.044 821.385 238.062 807.5L237.929 773.85C237.932 749.375 264.44 755.255 282.451 755.315L282.444 511.87L282.382 439.359C282.268 408.433 281.076 382.178 287.487 351.597C294.509 316.401 310.39 283.577 333.63 256.228C366.783 217.646 411.71 191.043 461.48 180.525C475.636 177.483 486.509 176.339 500.84 175.215ZM658.98 672.245C660.08 652.775 656.435 631.385 654.01 611.83L645.025 538.115C640.72 501.385 636.195 464.682 631.455 428.004C628.76 406.676 626.535 385.217 622.415 364.067C620.41 353.772 617.815 345.508 610.945 337.249C601.365 325.73 581.675 324.663 570.68 334.668C567.685 337.547 564.05 342.923 562.96 346.939C557.545 366.893 555.73 389.485 553.645 410.096C551.255 433.749 549.93 448.448 520.495 447.562C507.28 447.165 489.48 450.123 478.667 440.152C472.224 433.788 471.543 421.007 470.578 412.411C469.334 400.749 467.99 389.097 466.546 377.458C463.478 353.121 461.842 326.395 430.087 328.125C421.117 329.94 415.428 332.511 410.111 340.494C405.05 348.092 403.262 356.903 401.497 365.719C398.287 384.804 396.179 404.399 393.76 423.622L380.951 527.73L370.345 615.135C368.802 627.98 365.893 647.8 365.182 660.445C364.881 665.62 365.309 670.81 366.453 675.865C372.766 705.015 396.23 718.395 424.485 713.225C434.193 711.455 444.468 709.49 454.35 710.095C474.881 711.775 494.353 720.74 515.25 719.805C534.905 718.92 553.535 709.87 573.355 709.97C582.83 710.015 592.39 712.44 601.78 713.605C622.07 716.05 639.665 711.385 651.23 693.47C655.81 686.37 657.225 680.3 658.98 672.245Z" />
    <path d="M585.335 353.615C587.655 353.409 590.01 353.224 592.25 354C594.635 354.827 596.27 356.643 597.305 358.894C601.885 368.843 606.685 423.97 602.56 433.935C600.545 438.805 597.185 441.109 592.47 442.925C565.77 444.355 575.655 414.851 574.86 398.062C575.425 393.635 575.01 388.14 575.74 383.772C577.45 373.469 574.36 358.799 585.335 353.615Z" />
    <path d="M431.777 353.625C434.044 353.337 436.346 353.134 438.539 353.932C441.195 354.899 443.603 357.461 444.63 360.074C447.576 367.565 449.946 416.296 449.457 425.641C449.301 428.638 448.94 432.256 447.606 434.953C445.437 439.334 441.801 441.434 437.387 443.032C417.784 443.653 418.727 426.859 419.689 412.124C420.768 395.593 421.434 378.687 424.944 362.478C425.809 358.482 428.278 355.595 431.777 353.625Z" />
  </svg>;
}

function FeatureGraphicSlide({ copy }: { copy: SlideCopy }) {
  const rtl = copy.dir === "rtl";
  return <div data-marketing-slide data-locale={copy.locale} dir={copy.dir}
    style={{ width: FEATURE_GRAPHIC.w, height: FEATURE_GRAPHIC.h, background: BRAND_LIGHT.bg,
      color: BRAND_LIGHT.text, position: "relative", overflow: "hidden", fontFamily: marketingFont(copy) }}>
    <div style={{ position: "absolute", top: 64, insetInlineStart: 64, display: "flex",
      alignItems: "center", gap: 20 }}>
      <AppMark />
      <div data-overlay-text dir="ltr" style={{ fontFamily: "var(--font-geist-sans), sans-serif",
        fontWeight: 700, fontSize: 64, lineHeight: 1.1, letterSpacing: "-0.055em" }}>RIPDPI</div>
    </div>
    <p data-overlay-text data-description style={{ position: "absolute", top: 226,
      insetInlineStart: 64, width: "fit-content", maxWidth: 532, margin: 0,
      fontSize: 56, lineHeight: 1.2, textAlign: "start", fontWeight: 400 }}>
      {copy.featureGraphic.tagline.map((line, i) => <span key={line}>{line}{i === 0 && <br />}</span>)}
    </p>
    {/* Editorial path diagram. It does not represent an app screen or a measured result. */}
    <svg aria-hidden viewBox="0 0 320 240" style={{ position: "absolute", top: 154,
      insetInlineEnd: 64, width: 300, height: 225, transform: rtl ? "scaleX(-1)" : undefined }}>
      <g fill="none" stroke={BRAND_LIGHT.text} strokeWidth="6" strokeLinecap="round" strokeLinejoin="round">
        <rect x="7" y="63" width="66" height="112" rx="12" />
        <path d="M29 155h22M74 120h48M181 120h48" />
        <circle cx="152" cy="120" r="29" />
        <rect x="229" y="83" width="82" height="32" rx="6" />
        <rect x="229" y="127" width="82" height="32" rx="6" />
        <path d="M245 99h2M245 143h2" />
      </g>
    </svg>
  </div>;
}

type SlideComponent = React.ComponentType<{ copy: SlideCopy }>;
const SLIDES: ReadonlyArray<{ id: string; label: string; component: SlideComponent }> = [
  { id: "hero", label: "Connection", component: Slide1 },
  { id: "diagnostics", label: "Diagnostics", component: Slide2 },
  { id: "relays", label: "Relays", component: Slide3 },
  { id: "dns", label: "DNS", component: Slide4 },
  { id: "strategies", label: "Strategies", component: Slide5 },
  { id: "local-tools", label: "Backups", component: Slide6 },
];

// ── Preview with scaling ───────────────────────────────────────────────
function ScreenshotPreview({
  children,
  index,
  label,
  onExport,
  w,
  h,
}: {
  children: React.ReactNode;
  index: number;
  label: string;
  onExport: (el: HTMLElement, name: string, w: number, h: number) => void;
  w: number;
  h: number;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState(1);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const observer = new ResizeObserver((entries) => {
      const entry = entries[0];
      if (!entry) return;
      setScale(entry.contentRect.width / w);
    });
    observer.observe(container);
    return () => observer.disconnect();
  }, [w]);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      <div
        ref={containerRef}
        style={{
          width: "100%",
          aspectRatio: `${w}/${h}`,
          overflow: "hidden",
          borderRadius: 12,
          border: `1px solid ${BRAND_LIGHT.border}`,
          cursor: "pointer",
          position: "relative",
        }}
        onClick={() => {
          const el = containerRef.current?.querySelector<HTMLElement>("[data-slide-export]");
          if (el) onExport(el, `${String(index + 1).padStart(2, "0")}-${label}`, w, h);
        }}
      >
        <div
          style={{
            transform: `scale(${scale})`,
            transformOrigin: "top left",
            width: w,
            height: h,
          }}
        >
          {children}
        </div>
      </div>
      <div
        style={{
          fontSize: 13,
          color: BRAND_LIGHT.mutedFg,
          textAlign: "center",
          fontFamily: "var(--font-geist-mono), monospace",
        }}
      >
        {String(index + 1).padStart(2, "0")} -- {label} -- click to export
      </div>
    </div>
  );
}

// ── Main Page ──────────────────────────────────────────────────────────
export default function Page() {
  return (
    <Suspense
      fallback={<div style={{ background: BRAND_LIGHT.bg, minHeight: "100vh" }} />}
    >
      <ScreenshotsPage />
    </Suspense>
  );
}

function ScreenshotsPage() {
  const searchParams = useSearchParams();
  const slideParam = searchParams.get("slide");
  const langParam = searchParams.get("lang");
  const copy = getCopy(langParam);

  // Single slide full-resolution mode: ?slide=1 through ?slide=6, or ?slide=fg
  if (slideParam) {
    if (slideParam === "fg") {
      return <FeatureGraphicSlide copy={copy} />;
    }
    const idx = parseInt(slideParam) - 1;
    const slide = SLIDES[idx];
    if (slide) {
      const C = slide.component;
      return <C copy={copy} />;
    }
  }

  return <ScreenshotsGrid copy={copy} />;
}

function ScreenshotsGrid({ copy }: { copy: SlideCopy }) {
  const [exporting, setExporting] = useState<string | null>(null);

  const exportSingle = useCallback(
    async (el: HTMLElement, name: string, w: number, h: number) => {
      setExporting(name);
      const originalStyle = el.getAttribute("style");
      try {
        el.style.position = "fixed";
        el.style.left = "0px";
        el.style.top = "0px";
        el.style.zIndex = "-1";
        el.style.opacity = "1";

        const opts = { width: w, height: h, pixelRatio: 1, cacheBust: true, backgroundColor: "#FAFAFA", quality: 1 };
        await toJpeg(el, opts);
        const dataUrl = await toJpeg(el, opts);

        const link = document.createElement("a");
        link.download = `${name}-${w}x${h}.jpg`;
        link.href = dataUrl;
        link.click();
      } catch (err) {
        console.error("Export failed:", err);
      } finally {
        if (originalStyle === null) el.removeAttribute("style");
        else el.setAttribute("style", originalStyle);
        setExporting(null);
      }
    },
    []
  );

  const exportAll = useCallback(async () => {
    setExporting("all");
    const cards = document.querySelectorAll<HTMLElement>("[data-slide-export]");
    for (let i = 0; i < cards.length; i++) {
      const el = cards[i];
      const w = parseInt(el.dataset.slideW || String(PHONE_W));
      const h = parseInt(el.dataset.slideH || String(PHONE_H));
      const name = el.dataset.slideExport!;
      const originalStyle = el.getAttribute("style");

      el.style.position = "fixed";
      el.style.left = "0px";
      el.style.top = "0px";
      el.style.zIndex = "-1";
      el.style.opacity = "1";

      const opts = { width: w, height: h, pixelRatio: 1, cacheBust: true, backgroundColor: "#FAFAFA", quality: 1 };
      try {
        await toJpeg(el, opts);
        const dataUrl = await toJpeg(el, opts);
        const link = document.createElement("a");
        const prefix = w === FEATURE_GRAPHIC.w ? "feature-graphic" : `${String(i + 1).padStart(2, "0")}-${name}`;
        link.download = `${prefix}-${w}x${h}.jpg`;
        link.href = dataUrl;
        link.click();
      } catch (err) {
        console.error(`Export failed for ${name}:`, err);
      } finally {
        if (originalStyle === null) el.removeAttribute("style");
        else el.setAttribute("style", originalStyle);
      }
      await new Promise((r) => setTimeout(r, 300));
    }
    setExporting(null);
  }, []);

  return (
    <div
      style={{
        minHeight: "100vh",
        background: BRAND_LIGHT.bg,
        color: BRAND_LIGHT.text,
        padding: "32px 24px",
        fontFamily: "var(--font-geist-sans), Arial, sans-serif",
      }}
    >
      {/* Toolbar */}
      <div
        style={{
          maxWidth: 1400,
          margin: "0 auto 32px",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          flexWrap: "wrap",
          gap: 16,
        }}
      >
        <div>
          <h1 style={{ fontSize: 24, fontWeight: 700, margin: 0 }}>
            RIPDPI Play Store Screenshots
          </h1>
          <p
            style={{
              fontSize: 14,
              color: BRAND_LIGHT.mutedFg,
              margin: "4px 0 0",
              fontFamily: "var(--font-geist-mono), monospace",
            }}
          >
            {SLIDES.length} phone slides + feature graphic | {PHONE_W}x{PHONE_H}px | Locale:{" "}
            {copy.locale} | Click to export
          </p>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <LocaleSwitcher current={copy.locale} />
          <button
            onClick={exportAll}
            disabled={!!exporting}
            style={{
              background: exporting ? BRAND_LIGHT.muted : BRAND_LIGHT.text,
              color: exporting ? BRAND_LIGHT.mutedFg : BRAND_LIGHT.bg,
              border: "none",
              padding: "12px 28px",
              borderRadius: 10,
              fontSize: 15,
              fontWeight: 600,
              cursor: exporting ? "wait" : "pointer",
            }}
          >
            {exporting ? `Exporting ${exporting}...` : "Export All"}
          </button>
        </div>
      </div>

      {/* Phone slides grid */}
      <div
        style={{
          maxWidth: 1400,
          margin: "0 auto",
          display: "grid",
          gridTemplateColumns: "repeat(auto-fill, minmax(280px, 1fr))",
          gap: 24,
        }}
      >
        {SLIDES.map((slide, i) => {
          const C = slide.component;
          return (
            <ScreenshotPreview key={slide.id} index={i} label={slide.label} onExport={exportSingle} w={PHONE_W} h={PHONE_H}>
              <div data-slide-export={slide.id} data-slide-w={PHONE_W} data-slide-h={PHONE_H}>
                <C copy={copy} />
              </div>
            </ScreenshotPreview>
          );
        })}
      </div>

      {/* Feature Graphic */}
      <div style={{ maxWidth: 1400, margin: "48px auto 0" }}>
        <h2 style={{ fontSize: 18, fontWeight: 600, marginBottom: 16 }}>
          Feature Graphic (1024x500)
        </h2>
        <div style={{ maxWidth: 600 }}>
          <ScreenshotPreview
            index={SLIDES.length}
            label="Feature Graphic"
            onExport={exportSingle}
            w={FEATURE_GRAPHIC.w}
            h={FEATURE_GRAPHIC.h}
          >
            <div data-slide-export="feature-graphic" data-slide-w={FEATURE_GRAPHIC.w} data-slide-h={FEATURE_GRAPHIC.h}>
              <FeatureGraphicSlide copy={copy} />
            </div>
          </ScreenshotPreview>
        </div>
      </div>
    </div>
  );
}

// ── Locale switcher (agent-facing) ─────────────────────────────────────
function LocaleSwitcher({ current }: { current: string }) {
  return (
    <div style={{ display: "flex", gap: 4, flexWrap: "wrap" }}>
      {LOCALES.map((loc) => {
        const isCurrent = loc === current || (loc === DEFAULT_LOCALE && current === DEFAULT_LOCALE);
        return (
          <a
            key={loc}
            href={loc === DEFAULT_LOCALE ? "?" : `?lang=${loc}`}
            style={{
              fontSize: 12,
              color: isCurrent ? BRAND_LIGHT.text : BRAND_LIGHT.mutedFg,
              fontFamily: "var(--font-geist-mono), monospace",
              textDecoration: isCurrent ? "underline" : "none",
              padding: "4px 8px",
              border: `1px solid ${isCurrent ? BRAND_LIGHT.text : "transparent"}`,
              borderRadius: 6,
            }}
          >
            {loc}
          </a>
        );
      })}
    </div>
  );
}

// Keep imports referenced even when types are otherwise unused inline.
// (No-op type aliases discourage tree-shaking from dropping the re-exports.)
export type { Locale };
