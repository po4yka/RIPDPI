"use client";

import { useState, useRef, useEffect, useCallback, Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { toPng } from "html-to-image";
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
function Slide({ children, copy, dark = false }: {
  children: React.ReactNode; copy: SlideCopy; dark?: boolean;
}) {
  return <div data-marketing-slide data-locale={copy.locale} dir={copy.dir}
    style={{ width: PHONE_W, height: PHONE_H, background: dark ? BRAND.bg : BRAND_LIGHT.bg,
      color: dark ? BRAND.text : BRAND_LIGHT.text, position: "relative", overflow: "hidden",
      fontFamily: "var(--font-geist-sans), Arial, sans-serif" }}>{children}</div>;
}

function Caption({ copy, index, dark = false }: { copy: SlideCopy; index: number; dark?: boolean }) {
  const palette = dark ? BRAND : BRAND_LIGHT;
  return <header style={{ position: "absolute", top: 54, left: 72, right: 72 }}>
    <div style={{ display: "flex", alignItems: "center", gap: 20, marginBottom: 20 }}>
      <span data-overlay-text style={{ fontSize: 26, fontWeight: 600, color: palette.mutedFg,
        letterSpacing: "0.025em" }}>{copy.labels[index]}</span>
      <span style={{ flex: 1, height: 1, background: palette.border }} />
      <span data-overlay-text dir="ltr" style={{ fontSize: 24, fontFamily: "var(--font-geist-mono), monospace",
        color: palette.mutedFg }}>RIPDPI / {String(index + 1).padStart(2, "0")}</span>
    </div>
    <h1 data-overlay-text style={{ margin: 0, fontSize: 76, fontWeight: 700, lineHeight: 1.2,
      letterSpacing: "-0.035em", width: "fit-content", maxWidth: "100%" }}>
      {copy.headlines[index].map((line, i) => <span key={line}>{line}{i === 0 && <br />}</span>)}
    </h1>
  </header>;
}

function AppCapture({ copy, src, left = 204, width = 672, top = 350 }: {
  copy: SlideCopy; src: string; left?: number; width?: number; top?: number;
}) {
  return <img data-app-capture src={`/screenshots/${copy.locale}/${src}.png`}
    alt={`${copy.labels[src === "home-light" ? 0 : src === "diagnostics" ? 1 : 2]} — Android`}
    draggable={false} style={{ position: "absolute", top, left, width,
      height: width * 2992 / 1344, display: "block", objectFit: "contain",
      boxShadow: "0 16px 48px rgba(26,26,26,0.12)" }} />;
}

function Slide1({ copy }: { copy: SlideCopy }) {
  return <Slide copy={copy}><Caption copy={copy} index={0} />
    <AppCapture copy={copy} src="home-light" /></Slide>;
}
function Slide2({ copy }: { copy: SlideCopy }) {
  return <Slide copy={copy}><Caption copy={copy} index={1} />
    <AppCapture copy={copy} src="diagnostics" left={294} /></Slide>;
}
function Slide3({ copy }: { copy: SlideCopy }) {
  return <Slide copy={copy}><Caption copy={copy} index={2} />
    <AppCapture copy={copy} src="relay" left={114} /></Slide>;
}

// These editorial diagrams describe settings; they do not imitate app UI.
function CapabilityRows({ tokens, dark = false }: { tokens: readonly string[]; dark?: boolean }) {
  const palette = dark ? BRAND : BRAND_LIGHT;
  return <div dir="ltr" style={{ position: "absolute", top: 590, left: 96, right: 96 }}>
    {tokens.map((token, i) => <div key={token} style={{ height: 240, borderTop: `2px solid ${palette.border}`,
      display: "flex", alignItems: "center", justifyContent: "space-between" }}>
      <span data-overlay-text style={{ fontFamily: "var(--font-geist-mono), monospace", fontSize: 22,
        color: palette.mutedFg }}>{String(i + 1).padStart(2, "0")}</span>
      <span data-overlay-text style={{ fontSize: 100, lineHeight: 1, letterSpacing: "-0.05em",
        fontWeight: 600 }}>{token}</span>
      <div aria-hidden style={{ width: 120, height: 2, background: palette.text }} />
    </div>)}
  </div>;
}
function EditorialNote({ children }: { children: React.ReactNode }) {
  return <p data-overlay-text style={{ position: "absolute", top: 1690, left: 96, right: 96,
    width: "fit-content", maxWidth: 888, margin: 0, fontSize: 32, lineHeight: 1.45,
    color: BRAND_LIGHT.mutedFg }}>{children}</p>;
}
function Slide4({ copy }: { copy: SlideCopy }) {
  return <Slide copy={copy}><Caption copy={copy} index={3} />
    <CapabilityRows tokens={["DoH", "DoT", "DNSCrypt"]} />
    <EditorialNote>{copy.dnsDescription}</EditorialNote></Slide>;
}
function Slide5({ copy }: { copy: SlideCopy }) {
  return <Slide copy={copy}><Caption copy={copy} index={4} />
    <CapabilityRows tokens={["TCP", "TLS", "HTTP", "QUIC"]} />
    <EditorialNote>{copy.strategyDescription}</EditorialNote></Slide>;
}
function Slide6({ copy }: { copy: SlideCopy }) {
  return <Slide copy={copy} dark><Caption copy={copy} index={5} dark />
    <AppCapture copy={copy} src="home-light" width={500} left={96} top={600} />
    <div style={{ position: "absolute", top: 770, left: 650, right: 72 }}>
      {copy.localTools.map((tool, i) => <div key={tool} style={{ minHeight: 240,
        borderTop: `1px solid ${BRAND.mutedFg}`, paddingTop: 24 }}>
        <span data-overlay-text dir="ltr" style={{ display: "block", width: "fit-content",
          fontFamily: "var(--font-geist-mono), monospace", fontSize: 22, color: BRAND.mutedFg,
          marginBottom: 22 }}>{String(i + 1).padStart(2, "0")}</span>
        <span data-overlay-text style={{ display: "block", width: "fit-content", maxWidth: "100%",
          fontSize: 34, fontWeight: 500, lineHeight: 1.3 }}>{tool}</span>
      </div>)}
    </div></Slide>;
}
function FeatureGraphicSlide({ copy }: { copy: SlideCopy }) {
  return <div data-marketing-slide data-locale={copy.locale} dir={copy.dir} style={{ width: FEATURE_GRAPHIC.w,
    height: FEATURE_GRAPHIC.h, background: BRAND_LIGHT.bg, color: BRAND_LIGHT.text,
    position: "relative", overflow: "hidden", fontFamily: "var(--font-geist-sans), Arial, sans-serif" }}>
    <div style={{ position: "absolute", inset: 40, border: `1px solid ${BRAND_LIGHT.border}` }} />
    <img src="/app-icon.png" alt="" style={{ position: "absolute", left: 96, top: 82,
      width: 72, height: 72 }} />
    <img data-app-capture src={`/screenshots/${copy.locale}/home-light.png`} alt="Android"
      style={{ position: "absolute", left: 746, top: 75, width: 156,
        height: 156 * 2992 / 1344, display: "block", objectFit: "contain",
        boxShadow: "0 8px 24px rgba(26,26,26,0.12)" }} />
    <div style={{ position: "absolute", left: 96, top: 178, width: 570 }}>
      <div data-overlay-text dir="ltr" style={{ fontWeight: 700, fontSize: 88, lineHeight: 1.2,
        letterSpacing: "-0.065em", width: "fit-content" }}>RIPDPI</div>
      <p data-overlay-text style={{ fontSize: 30, lineHeight: 1.25, margin: "24px 0 0",
        width: "fit-content", maxWidth: "100%", color: BRAND_LIGHT.mutedFg }}>{copy.featureGraphic.tagline}</p>
    </div>
  </div>;
}

type SlideComponent = React.ComponentType<{ copy: SlideCopy }>;
const SLIDES: ReadonlyArray<{ id: string; label: string; component: SlideComponent }> = [
  { id: "hero", label: "Connection", component: Slide1 },
  { id: "diagnostics", label: "Diagnostics", component: Slide2 },
  { id: "relays", label: "Relays", component: Slide3 },
  { id: "dns", label: "DNS", component: Slide4 },
  { id: "strategies", label: "Strategies", component: Slide5 },
  { id: "local-tools", label: "Local tools", component: Slide6 },
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
      try {
        el.style.position = "fixed";
        el.style.left = "0px";
        el.style.top = "0px";
        el.style.zIndex = "-1";
        el.style.opacity = "1";

        const opts = { width: w, height: h, pixelRatio: 1, cacheBust: true, backgroundColor: "#FAFAFA" };
        await toPng(el, opts);
        const dataUrl = await toPng(el, opts);

        el.style.position = "";
        el.style.left = "";
        el.style.top = "";
        el.style.zIndex = "";
        el.style.opacity = "";

        const link = document.createElement("a");
        link.download = `${name}-${w}x${h}.png`;
        link.href = dataUrl;
        link.click();
      } catch (err) {
        console.error("Export failed:", err);
      } finally {
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

      el.style.position = "fixed";
      el.style.left = "0px";
      el.style.top = "0px";
      el.style.zIndex = "-1";
      el.style.opacity = "1";

      const opts = { width: w, height: h, pixelRatio: 1, cacheBust: true, backgroundColor: "#FAFAFA" };
      try {
        await toPng(el, opts);
        const dataUrl = await toPng(el, opts);
        const link = document.createElement("a");
        const prefix = w === FEATURE_GRAPHIC.w ? "feature-graphic" : `${String(i + 1).padStart(2, "0")}-${name}`;
        link.download = `${prefix}-${w}x${h}.png`;
        link.href = dataUrl;
        link.click();
      } catch (err) {
        console.error(`Export failed for ${name}:`, err);
      }

      el.style.position = "";
      el.style.left = "";
      el.style.top = "";
      el.style.zIndex = "";
      el.style.opacity = "";
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
