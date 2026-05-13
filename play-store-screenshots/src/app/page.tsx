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

// ── Helpers ────────────────────────────────────────────────────────────
/** Render an array of headline lines as JSX, separated by <br />. */
function renderHeadline(lines: readonly string[]): React.ReactNode {
  return lines.map((line, i) => (
    <span key={i}>
      {line}
      {i < lines.length - 1 && <br />}
    </span>
  ));
}

// ── Screenshot (Frameless) ─────────────────────────────────────────────
function Screenshot({
  src,
  alt,
  style,
  bgColor = "#ffffff",
}: {
  src: string;
  alt: string;
  style?: React.CSSProperties;
  bgColor?: string;
}) {
  return (
    <div style={{ position: "relative", ...style }}>
      <div
        style={{
          width: "100%",
          height: "100%",
          borderRadius: 40,
          overflow: "hidden",
          boxShadow: "0 12px 60px rgba(0,0,0,0.18)",
          background: bgColor,
        }}
      >
        <img
          src={src}
          alt={alt}
          style={{
            display: "block",
            width: "100%",
            height: "100%",
            objectFit: "cover",
            objectPosition: "top",
          }}
          draggable={false}
        />
      </div>
    </div>
  );
}

// ── Caption ────────────────────────────────────────────────────────────
function Caption({
  label,
  headline,
  dark = false,
  /**
   * Whether this slide is the one diagnostic/info slide that justifies using
   * the info accent on the eyebrow. Defaults to false: eyebrows should use
   * the foreground color, not a saturated accent.
   */
  accent = false,
  style,
}: {
  label: string;
  headline: React.ReactNode;
  dark?: boolean;
  accent?: boolean;
  style?: React.CSSProperties;
}) {
  const palette = dark ? BRAND : BRAND_LIGHT;
  return (
    <div
      style={{
        position: "absolute",
        top: 65,
        left: 70,
        right: 70,
        zIndex: 10,
        ...style,
      }}
    >
      <div
        style={{
          fontSize: 35,
          fontWeight: 600,
          color: accent ? palette.info : palette.mutedFg,
          letterSpacing: "0.08em",
          textTransform: "uppercase",
          marginBottom: 12,
          fontFamily: "var(--font-geist-sans)",
        }}
      >
        {label}
      </div>
      <div
        style={{
          fontSize: 108,
          fontWeight: 700,
          color: palette.text,
          lineHeight: 1.0,
          letterSpacing: "-0.025em",
          fontFamily: "var(--font-geist-sans)",
        }}
      >
        {headline}
      </div>
    </div>
  );
}

// ── Decorative: Grid (monochrome, subtle) ──────────────────────────────
function Grid({
  opacity = 0.04,
  dark = false,
}: {
  opacity?: number;
  dark?: boolean;
}) {
  const stroke = dark ? "rgba(255,255,255,0.18)" : "rgba(26,26,26,0.18)";
  return (
    <div
      style={{
        position: "absolute",
        inset: 0,
        opacity,
        pointerEvents: "none",
        backgroundImage: `
          linear-gradient(${stroke} 1px, transparent 1px),
          linear-gradient(90deg, ${stroke} 1px, transparent 1px)
        `,
        backgroundSize: "60px 60px",
      }}
    />
  );
}

// ── Pill badge ─────────────────────────────────────────────────────────
function Pill({
  children,
  color = BRAND_LIGHT.text,
  bg = BRAND_LIGHT.muted,
  border = BRAND_LIGHT.border,
  fontSize = 30,
}: {
  children: React.ReactNode;
  color?: string;
  bg?: string;
  border?: string;
  fontSize?: number;
}) {
  return (
    <div
      style={{
        background: bg,
        color,
        fontSize,
        fontWeight: 600,
        padding: "14px 26px",
        borderRadius: 14,
        border: `1px solid ${border}`,
        fontFamily: "var(--font-geist-mono)",
      }}
    >
      {children}
    </div>
  );
}

// ── Slide Container ────────────────────────────────────────────────────
function Slide({
  children,
  bg,
  dir,
}: {
  children: React.ReactNode;
  bg: string;
  dir?: "ltr" | "rtl";
}) {
  return (
    <div
      dir={dir ?? "ltr"}
      style={{
        width: PHONE_W,
        height: PHONE_H,
        background: bg,
        position: "relative",
        overflow: "hidden",
        fontFamily: "var(--font-geist-sans)",
      }}
    >
      {children}
    </div>
  );
}

// ══════════════════════════════════════════════════════════════════════
// SLIDE 1: Hero -- "Browse without borders"
// Light bg, centered phone (home screen, light 1080x2400)
// ══════════════════════════════════════════════════════════════════════
function Slide1({ copy }: { copy: SlideCopy }) {
  return (
    <Slide bg={BRAND_LIGHT.bg} dir={copy.dir}>
      <Grid opacity={0.05} />
      <Caption
        label={copy.slide1.label}
        headline={renderHeadline(copy.slide1.headline)}
      />
      <Screenshot
        src="/screenshots/home-light.png"
        alt="Home screen"
        style={{
          position: "absolute",
          top: 520,
          left: "50%",
          transform: "translateX(-50%)",
          width: "76%",
          aspectRatio: "1080/2400",
        }}
      />
    </Slide>
  );
}

// ══════════════════════════════════════════════════════════════════════
// SLIDE 2: Differentiator -- "One tap. No root."
// Light bg, brutalist logo on white, text-focused, no phone
// ══════════════════════════════════════════════════════════════════════
function Slide2({ copy }: { copy: SlideCopy }) {
  return (
    <Slide bg={BRAND_LIGHT.bg} dir={copy.dir}>
      <Grid opacity={0.04} />

      {/* App icon — brutalist black silhouette on white card */}
      <div
        style={{
          position: "absolute",
          top: 100,
          left: "50%",
          transform: "translateX(-50%)",
          width: 180,
          height: 180,
          borderRadius: 42,
          overflow: "hidden",
          background: BRAND_LIGHT.card,
          border: `1px solid ${BRAND_LIGHT.border}`,
        }}
      >
        <img
          src="/app-icon.png"
          alt="RIPDPI"
          style={{ width: "100%", height: "100%", objectFit: "cover" }}
        />
      </div>

      {/* Headline */}
      <div
        style={{
          position: "absolute",
          top: 340,
          left: 0,
          right: 0,
          textAlign: "center",
        }}
      >
        <div
          style={{
            fontSize: 35,
            fontWeight: 600,
            color: BRAND_LIGHT.mutedFg,
            letterSpacing: "0.08em",
            textTransform: "uppercase",
            marginBottom: 16,
          }}
        >
          {copy.slide2.eyebrow}
        </div>
        <div
          style={{
            fontSize: 120,
            fontWeight: 700,
            color: BRAND_LIGHT.text,
            lineHeight: 0.95,
            letterSpacing: "-0.025em",
          }}
        >
          {renderHeadline(copy.slide2.headline)}
        </div>
      </div>

      {/* Feature cards */}
      <div
        style={{
          position: "absolute",
          top: 720,
          left: 60,
          right: 60,
          display: "flex",
          flexDirection: "column",
          gap: 20,
        }}
      >
        {copy.slide2.cards.map((item) => (
          <div
            key={item.title}
            style={{
              background: BRAND_LIGHT.card,
              border: `1px solid ${BRAND_LIGHT.border}`,
              borderRadius: 20,
              padding: "28px 32px",
              display: "flex",
              flexDirection: "column",
              gap: 6,
            }}
          >
            <div style={{ fontSize: 32, fontWeight: 600, color: BRAND_LIGHT.text }}>
              {item.title}
            </div>
            <div style={{ fontSize: 26, color: BRAND_LIGHT.mutedFg }}>
              {item.desc}
            </div>
          </div>
        ))}
      </div>

      {/* Bottom badge — restrained success badge (matches DESIGN.md successBadge) */}
      <div
        style={{
          position: "absolute",
          bottom: 100,
          left: "50%",
          transform: "translateX(-50%)",
          background: BRAND_LIGHT.success,
          color: BRAND_LIGHT.bg,
          fontSize: 28,
          fontWeight: 700,
          padding: "16px 36px",
          borderRadius: 16,
          fontFamily: "var(--font-geist-mono)",
        }}
      >
        {copy.slide2.bottomBadge}
      </div>
    </Slide>
  );
}

// ══════════════════════════════════════════════════════════════════════
// SLIDE 3: Core Feature -- "Your privacy. Your rules."
// Light slide, settings screenshot (1080x2400), right-offset
// ══════════════════════════════════════════════════════════════════════
function Slide3({ copy }: { copy: SlideCopy }) {
  return (
    <Slide bg={BRAND_LIGHT.bg} dir={copy.dir}>
      <Grid opacity={0.04} />
      <Caption
        label={copy.slide3.label}
        headline={renderHeadline(copy.slide3.headline)}
      />

      {/* Feature badges on the left — restrained chip-default (muted bg, fg text) */}
      <div
        style={{
          position: "absolute",
          left: 60,
          bottom: 180,
          display: "flex",
          flexDirection: "column",
          gap: 12,
          zIndex: 10,
        }}
      >
        {copy.slide3.pills.map((f) => (
          <div
            key={f}
            style={{
              background: BRAND_LIGHT.muted,
              color: BRAND_LIGHT.text,
              fontSize: 24,
              fontWeight: 600,
              padding: "10px 20px",
              borderRadius: 12,
              border: `1px solid ${BRAND_LIGHT.border}`,
              fontFamily: "var(--font-geist-mono)",
            }}
          >
            {f}
          </div>
        ))}
      </div>

      <Screenshot
        src="/screenshots/settings.png"
        alt="Settings"
        style={{
          position: "absolute",
          top: 400,
          right: "4%",
          width: "72%",
          aspectRatio: "1080/2400",
        }}
      />
    </Slide>
  );
}

// ══════════════════════════════════════════════════════════════════════
// SLIDE 4: Core Feature -- "Fine-tune every packet"
// Light bg, text-focused with protocol pills, no phone
// ══════════════════════════════════════════════════════════════════════
function Slide4({ copy }: { copy: SlideCopy }) {
  return (
    <Slide bg={BRAND_LIGHT.bg} dir={copy.dir}>
      <Grid opacity={0.04} />

      <Caption
        label={copy.slide4.label}
        headline={renderHeadline(copy.slide4.headline)}
      />

      {/* Protocol section */}
      <div
        style={{
          position: "absolute",
          top: 480,
          left: 70,
          right: 70,
        }}
      >
        {/* Encrypted DNS */}
        <div style={{ marginBottom: 40 }}>
          <div
            style={{
              fontSize: 28,
              fontWeight: 600,
              color: BRAND_LIGHT.mutedFg,
              letterSpacing: "0.08em",
              textTransform: "uppercase",
              marginBottom: 16,
            }}
          >
            {copy.slide4.sectionEncryptedDns}
          </div>
          <div style={{ display: "flex", gap: 14, flexWrap: "wrap" }}>
            {["DoH", "DoT", "DNSCrypt"].map((proto) => (
              <Pill key={proto}>{proto}</Pill>
            ))}
          </div>
        </div>

        {/* Transport */}
        <div style={{ marginBottom: 40 }}>
          <div
            style={{
              fontSize: 28,
              fontWeight: 600,
              color: BRAND_LIGHT.mutedFg,
              letterSpacing: "0.08em",
              textTransform: "uppercase",
              marginBottom: 16,
            }}
          >
            {copy.slide4.sectionDpiBypass}
          </div>
          <div style={{ display: "flex", gap: 14, flexWrap: "wrap" }}>
            {["TCP desync", "QUIC", "TLS tricks", "HTTP split"].map((proto) => (
              <Pill key={proto}>{proto}</Pill>
            ))}
          </div>
        </div>

        {/* Modes — selected chip uses foreground-on-background pattern from DESIGN.md chipSelected */}
        <div>
          <div
            style={{
              fontSize: 28,
              fontWeight: 600,
              color: BRAND_LIGHT.mutedFg,
              letterSpacing: "0.08em",
              textTransform: "uppercase",
              marginBottom: 16,
            }}
          >
            {copy.slide4.sectionModes}
          </div>
          <div style={{ display: "flex", gap: 14, flexWrap: "wrap" }}>
            <Pill
              color={BRAND_LIGHT.bg}
              bg={BRAND_LIGHT.text}
              border={BRAND_LIGHT.text}
            >
              {copy.slide4.modeVpn}
            </Pill>
            <Pill>{copy.slide4.modeProxy}</Pill>
          </div>
        </div>
      </div>

      {/* Bottom subtext */}
      <div
        style={{
          position: "absolute",
          bottom: 100,
          left: 70,
          right: 70,
          textAlign: "center",
        }}
      >
        <div
          style={{
            fontSize: 28,
            color: BRAND_LIGHT.mutedFg,
            lineHeight: 1.5,
          }}
        >
          {copy.slide4.footer}
        </div>
      </div>
    </Slide>
  );
}

// ══════════════════════════════════════════════════════════════════════
// SLIDE 5: Core Feature -- "See what's really happening"
// Light bg, diagnostics screenshot (1080x2400), left-offset
// This is the one slide where info accent is legitimate: it shows the
// active diagnostic scan state ("DNS / HTTP / TLS / TCP / QUIC" probes).
// ══════════════════════════════════════════════════════════════════════
function Slide5({ copy }: { copy: SlideCopy }) {
  return (
    <Slide bg={BRAND_LIGHT.bg} dir={copy.dir}>
      <Grid opacity={0.04} />
      <Caption
        label={copy.slide5.label}
        headline={renderHeadline(copy.slide5.headline)}
        accent
        style={{ right: 200 }}
      />

      {/* Active-probe column — info badge style from DESIGN.md infoBadge */}
      <div
        style={{
          position: "absolute",
          right: 50,
          bottom: 160,
          display: "flex",
          flexDirection: "column",
          gap: 12,
          zIndex: 10,
        }}
      >
        {["DNS", "HTTP", "TLS", "TCP", "QUIC"].map((p) => (
          <div
            key={p}
            style={{
              background: BRAND_LIGHT.info,
              color: "#FFFFFF",
              fontSize: 24,
              fontWeight: 700,
              padding: "10px 22px",
              borderRadius: 12,
              textAlign: "center",
              fontFamily: "var(--font-geist-mono)",
            }}
          >
            {p}
          </div>
        ))}
      </div>

      <Screenshot
        src="/screenshots/diagnostics.png"
        alt="Diagnostics"
        style={{
          position: "absolute",
          top: 520,
          left: "4%",
          width: "72%",
          aspectRatio: "1080/2400",
        }}
      />
    </Slide>
  );
}

// ══════════════════════════════════════════════════════════════════════
// SLIDE 6: More Features -- "And so much more."
// Dark bg (rhythm break — 1 of 6 stays dark), app icon + feature pills,
// no phone screenshot. Dark surface keeps the role mapping inverted, not
// recolored.
// ══════════════════════════════════════════════════════════════════════
function Slide6({ copy }: { copy: SlideCopy }) {
  const features = copy.slide6.features;
  const comingSoon = copy.slide6.comingSoon;

  return (
    <Slide bg={BRAND.bg} dir={copy.dir}>
      <Grid opacity={0.05} dark />

      {/* App icon — invert: white card holds the brutalist black silhouette */}
      <div
        style={{
          position: "absolute",
          top: 120,
          left: "50%",
          transform: "translateX(-50%)",
          width: 160,
          height: 160,
          borderRadius: 36,
          overflow: "hidden",
          background: "#FFFFFF",
          border: `1px solid ${BRAND.border}`,
        }}
      >
        <img
          src="/app-icon.png"
          alt="RIPDPI"
          style={{ width: "100%", height: "100%", objectFit: "cover" }}
        />
      </div>

      {/* Headline */}
      <div
        style={{
          position: "absolute",
          top: 350,
          left: 0,
          right: 0,
          textAlign: "center",
        }}
      >
        <div
          style={{
            fontSize: 105,
            fontWeight: 700,
            color: BRAND.text,
            lineHeight: 1.0,
            letterSpacing: "-0.025em",
          }}
        >
          {renderHeadline(copy.slide6.headline)}
        </div>
      </div>

      {/* Feature pills — dark-inversion of chipDefault (muted bg, fg text). */}
      <div
        style={{
          position: "absolute",
          top: 680,
          left: 55,
          right: 55,
          display: "flex",
          flexWrap: "wrap",
          gap: 14,
          justifyContent: "center",
        }}
      >
        {features.map((f) => (
          <Pill
            key={f}
            fontSize={28}
            color={BRAND.text}
            bg={BRAND.muted}
            border={BRAND.border}
          >
            {f}
          </Pill>
        ))}
      </div>

      {/* Coming soon */}
      <div
        style={{
          position: "absolute",
          top: 1100,
          left: 0,
          right: 0,
          textAlign: "center",
        }}
      >
        <div
          style={{
            fontSize: 26,
            fontWeight: 600,
            color: BRAND.mutedFg,
            letterSpacing: "0.08em",
            textTransform: "uppercase",
            marginBottom: 16,
          }}
        >
          {copy.slide6.comingSoonLabel}
        </div>
        <div style={{ display: "flex", gap: 14, justifyContent: "center" }}>
          {comingSoon.map((f) => (
            <div
              key={f}
              style={{
                background: BRAND.bg,
                color: BRAND.mutedFg,
                fontSize: 26,
                fontWeight: 500,
                padding: "12px 24px",
                borderRadius: 14,
                border: `1px solid ${BRAND.border}`,
                fontFamily: "var(--font-geist-mono)",
              }}
            >
              {f}
            </div>
          ))}
        </div>
      </div>

      {/* Bottom tagline — foreground, not info accent */}
      <div
        style={{
          position: "absolute",
          bottom: 100,
          left: 0,
          right: 0,
          textAlign: "center",
        }}
      >
        <div style={{ fontSize: 32, fontWeight: 400, color: BRAND.text }}>
          RIPDPI
        </div>
      </div>
    </Slide>
  );
}

// ══════════════════════════════════════════════════════════════════════
// Feature Graphic (1024x500) — light, monochrome-first
// ══════════════════════════════════════════════════════════════════════
function FeatureGraphicSlide({ copy }: { copy: SlideCopy }) {
  return (
    <div
      dir={copy.dir}
      style={{
        width: FEATURE_GRAPHIC.w,
        height: FEATURE_GRAPHIC.h,
        background: BRAND_LIGHT.bg,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        flexDirection: "column",
        gap: 20,
        position: "relative",
        overflow: "hidden",
        fontFamily: "var(--font-geist-sans)",
      }}
    >
      <Grid opacity={0.04} />

      <div
        style={{
          width: 120,
          height: 120,
          borderRadius: 28,
          overflow: "hidden",
          background: BRAND_LIGHT.card,
          border: `1px solid ${BRAND_LIGHT.border}`,
          position: "relative",
          zIndex: 1,
        }}
      >
        <img
          src="/app-icon.png"
          alt=""
          style={{
            width: "100%",
            height: "100%",
            objectFit: "cover",
            display: "block",
          }}
        />
      </div>
      <div
        style={{
          color: BRAND_LIGHT.text,
          fontSize: 52,
          fontWeight: 700,
          letterSpacing: "-0.02em",
          position: "relative",
          zIndex: 1,
        }}
      >
        RIPDPI
      </div>
      <div
        style={{
          color: BRAND_LIGHT.mutedFg,
          fontSize: 22,
          fontWeight: 400,
          position: "relative",
          zIndex: 1,
        }}
      >
        {copy.featureGraphic.tagline}
      </div>
    </div>
  );
}

// ── Slide registry ─────────────────────────────────────────────────────
type SlideComponent = React.ComponentType<{ copy: SlideCopy }>;

const SLIDES: ReadonlyArray<{ id: string; label: string; component: SlideComponent }> = [
  { id: "hero", label: "Hero", component: Slide1 },
  { id: "no-root", label: "No Root", component: Slide2 },
  { id: "privacy", label: "Privacy", component: Slide3 },
  { id: "controls", label: "Controls", component: Slide4 },
  { id: "diagnostics", label: "Diagnostics", component: Slide5 },
  { id: "more", label: "More Features", component: Slide6 },
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
          fontFamily: "var(--font-geist-mono)",
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
        fontFamily: "var(--font-geist-sans)",
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
              fontFamily: "var(--font-geist-mono)",
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
              fontFamily: "var(--font-geist-mono)",
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
