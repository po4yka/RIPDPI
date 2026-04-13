"use client";

import { useState, useRef, useEffect, useCallback, Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { toPng } from "html-to-image";

// ── Constants ──────────────────────────────────────────────────────────
const PHONE_W = 1080;
const PHONE_H = 1920;
const FEATURE_GRAPHIC = { w: 1024, h: 500 };

// Dark theme tokens from DESIGN.md / RipDpiExtendedColors
const BRAND = {
  bg: "#121212",           // background
  card: "#1A1A1A",         // card
  text: "#E8E8E8",         // foreground
  muted: "#1E1E1E",        // muted
  mutedFg: "#A3A3A3",      // mutedForeground
  accent: "#2A2A2A",       // accent
  border: "#2A2A2A",       // border
  success: "#34D399",      // success
  warning: "#FBBF24",      // warning
  error: "#F87171",        // destructive
  info: "#60A5FA",         // info
} as const;

// Light theme tokens for contrast slides
const BRAND_LIGHT = {
  bg: "#FAFAFA",           // background
  card: "#FFFFFF",         // card
  text: "#1A1A1A",         // foreground
  mutedFg: "#575757",      // mutedForeground
  border: "#E0E0E0",       // border
  success: "#047857",      // success
  info: "#1D4ED8",         // info
} as const;

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
          boxShadow: "0 12px 60px rgba(0,0,0,0.5)",
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
  dark = true,
  style,
}: {
  label: string;
  headline: React.ReactNode;
  dark?: boolean;
  style?: React.CSSProperties;
}) {
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
          color: dark ? BRAND.info : BRAND_LIGHT.info,
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
          color: dark ? BRAND.text : BRAND_LIGHT.text,
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

// ── Decorative: Glow ───────────────────────────────────────────────────
function Glow({
  x,
  y,
  size,
  color,
  opacity = 0.15,
}: {
  x: string;
  y: string;
  size: number;
  color: string;
  opacity?: number;
}) {
  return (
    <div
      style={{
        position: "absolute",
        left: x,
        top: y,
        width: size,
        height: size,
        borderRadius: "50%",
        background: `radial-gradient(circle, ${color} 0%, transparent 70%)`,
        opacity,
        pointerEvents: "none",
      }}
    />
  );
}

// ── Decorative: Grid ───────────────────────────────────────────────────
function Grid({ opacity = 0.04 }: { opacity?: number }) {
  return (
    <div
      style={{
        position: "absolute",
        inset: 0,
        opacity,
        pointerEvents: "none",
        backgroundImage: `
          linear-gradient(rgba(255,255,255,0.3) 1px, transparent 1px),
          linear-gradient(90deg, rgba(255,255,255,0.3) 1px, transparent 1px)
        `,
        backgroundSize: "60px 60px",
      }}
    />
  );
}

// ── Pill badge ─────────────────────────────────────────────────────────
function Pill({
  children,
  color = BRAND.text,
  bg = "rgba(255,255,255,0.08)",
  border = BRAND.border,
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
}: {
  children: React.ReactNode;
  bg: string;
}) {
  return (
    <div
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
// Dark bg, centered phone (home screen, light 1080x2400)
// ══════════════════════════════════════════════════════════════════════
function Slide1() {
  return (
    <Slide bg={`linear-gradient(170deg, ${BRAND.bg} 0%, #0a1628 50%, ${BRAND.bg} 100%)`}>
      <Glow x="10%" y="5%" size={450} color={BRAND.info} opacity={0.12} />
      <Glow x="55%" y="70%" size={350} color={BRAND.success} opacity={0.08} />
      <Grid opacity={0.03} />
      <Caption
        label="Internet Freedom"
        headline={
          <>
            Browse
            <br />
            without
            <br />
            borders
          </>
        }
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
// Dark bg, text-focused, app icon, no phone
// ══════════════════════════════════════════════════════════════════════
function Slide2() {
  return (
    <Slide bg={`linear-gradient(165deg, #0f172a 0%, ${BRAND.bg} 100%)`}>
      <Glow x="60%" y="15%" size={400} color={BRAND.info} opacity={0.1} />
      <Glow x="10%" y="55%" size={300} color={BRAND.success} opacity={0.1} />
      <Grid opacity={0.025} />

      {/* App icon */}
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
          boxShadow: "0 8px 40px rgba(96,165,250,0.3)",
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
            color: BRAND.info,
            letterSpacing: "0.08em",
            textTransform: "uppercase",
            marginBottom: 16,
          }}
        >
          No Root Required
        </div>
        <div
          style={{
            fontSize: 120,
            fontWeight: 700,
            color: BRAND.text,
            lineHeight: 0.95,
            letterSpacing: "-0.025em",
          }}
        >
          One tap.
          <br />
          No root.
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
        {[
          { icon: "shield", title: "Works on any Android", desc: "No unlocking, no hacks" },
          { icon: "zap", title: "Connect in one tap", desc: "Instant protection" },
          { icon: "lock", title: "Local VPN or Proxy", desc: "Traffic never leaves your device" },
        ].map((item) => (
          <div
            key={item.title}
            style={{
              background: "rgba(255,255,255,0.05)",
              border: `1px solid ${BRAND.border}`,
              borderRadius: 20,
              padding: "28px 32px",
              display: "flex",
              flexDirection: "column",
              gap: 6,
            }}
          >
            <div style={{ fontSize: 32, fontWeight: 600, color: BRAND.text }}>
              {item.title}
            </div>
            <div style={{ fontSize: 26, color: "rgba(255,255,255,0.5)" }}>
              {item.desc}
            </div>
          </div>
        ))}
      </div>

      {/* Bottom badge */}
      <div
        style={{
          position: "absolute",
          bottom: 100,
          left: "50%",
          transform: "translateX(-50%)",
          background: BRAND.success,
          color: "#fff",
          fontSize: 28,
          fontWeight: 700,
          padding: "16px 36px",
          borderRadius: 16,
          boxShadow: "0 4px 20px rgba(52,211,153,0.3)",
          fontFamily: "var(--font-geist-mono)",
        }}
      >
        Works on any device
      </div>
    </Slide>
  );
}

// ══════════════════════════════════════════════════════════════════════
// SLIDE 3: Core Feature -- "Your privacy. Your rules."
// Light contrast slide, settings screenshot (1080x2400), right-offset
// ══════════════════════════════════════════════════════════════════════
function Slide3() {
  return (
    <Slide bg={`linear-gradient(170deg, ${BRAND_LIGHT.bg} 0%, ${BRAND_LIGHT.border} 100%)`}>
      <Glow x="5%" y="55%" size={400} color={BRAND_LIGHT.info} opacity={0.1} />
      <Caption
        label="Privacy & Security"
        headline={
          <>
            Your privacy.
            <br />
            Your rules.
          </>
        }
        dark={false}
      />

      {/* Feature badges on the left */}
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
        {["Encrypted DNS", "WebRTC block", "Bio lock"].map((f) => (
          <div
            key={f}
            style={{
              background: BRAND_LIGHT.info,
              color: "#fff",
              fontSize: 24,
              fontWeight: 600,
              padding: "10px 20px",
              borderRadius: 12,
              boxShadow: "0 4px 16px rgba(29,78,216,0.25)",
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
// Dark bg, text-focused with protocol pills, no phone
// ══════════════════════════════════════════════════════════════════════
function Slide4() {
  return (
    <Slide bg={`linear-gradient(160deg, #071a12 0%, ${BRAND.bg} 50%, #0a1628 100%)`}>
      <Glow x="55%" y="5%" size={400} color={BRAND.success} opacity={0.15} />
      <Glow x="-5%" y="65%" size={350} color={BRAND.info} opacity={0.08} />
      <Grid opacity={0.03} />

      <Caption
        label="Advanced Controls"
        headline={
          <>
            Fine-tune
            <br />
            every packet
          </>
        }
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
              color: "rgba(255,255,255,0.5)",
              letterSpacing: "0.08em",
              textTransform: "uppercase",
              marginBottom: 16,
            }}
          >
            Encrypted DNS
          </div>
          <div style={{ display: "flex", gap: 14, flexWrap: "wrap" }}>
            {["DoH", "DoT", "DNSCrypt"].map((proto) => (
              <Pill
                key={proto}
                color={BRAND.success}
                bg="rgba(52,211,153,0.12)"
                border="rgba(52,211,153,0.3)"
              >
                {proto}
              </Pill>
            ))}
          </div>
        </div>

        {/* Transport */}
        <div style={{ marginBottom: 40 }}>
          <div
            style={{
              fontSize: 28,
              fontWeight: 600,
              color: "rgba(255,255,255,0.5)",
              letterSpacing: "0.08em",
              textTransform: "uppercase",
              marginBottom: 16,
            }}
          >
            DPI Bypass
          </div>
          <div style={{ display: "flex", gap: 14, flexWrap: "wrap" }}>
            {["TCP desync", "QUIC", "TLS tricks", "HTTP split"].map((proto) => (
              <Pill
                key={proto}
                color={BRAND.info}
                bg="rgba(96,165,250,0.12)"
                border="rgba(96,165,250,0.3)"
              >
                {proto}
              </Pill>
            ))}
          </div>
        </div>

        {/* Modes */}
        <div>
          <div
            style={{
              fontSize: 28,
              fontWeight: 600,
              color: "rgba(255,255,255,0.5)",
              letterSpacing: "0.08em",
              textTransform: "uppercase",
              marginBottom: 16,
            }}
          >
            Connection Modes
          </div>
          <div style={{ display: "flex", gap: 14, flexWrap: "wrap" }}>
            <Pill color="#fff" bg={BRAND.info} border={BRAND.info}>
              Local VPN
            </Pill>
            <Pill>Local Proxy</Pill>
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
            color: "rgba(255,255,255,0.35)",
            lineHeight: 1.5,
          }}
        >
          Presets for beginners. Full control for experts.
        </div>
      </div>
    </Slide>
  );
}

// ══════════════════════════════════════════════════════════════════════
// SLIDE 5: Core Feature -- "See what's really happening"
// Dark bg, diagnostics screenshot (1080x2400), left-offset
// ══════════════════════════════════════════════════════════════════════
function Slide5() {
  return (
    <Slide bg={`linear-gradient(175deg, #0f172a 0%, ${BRAND.bg} 60%, #0a1020 100%)`}>
      <Glow x="60%" y="10%" size={350} color={BRAND.info} opacity={0.12} />
      <Grid opacity={0.025} />
      <Caption
        label="Built-in Diagnostics"
        headline={
          <>
            See what&apos;s
            <br />
            really
            <br />
            happening
          </>
        }
        style={{ right: 200 }}
      />

      {/* Scan badge */}
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
              background: BRAND.info,
              color: "#fff",
              fontSize: 24,
              fontWeight: 700,
              padding: "10px 22px",
              borderRadius: 12,
              textAlign: "center",
              fontFamily: "var(--font-geist-mono)",
              boxShadow: "0 4px 16px rgba(96,165,250,0.2)",
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
// Dark bg, app icon + feature pills, no phone screenshot
// ══════════════════════════════════════════════════════════════════════
function Slide6() {
  const features = [
    "Per-network policies",
    "AdGuard compatible",
    "Session telemetry",
    "WebRTC protection",
    "Biometric lock",
    "Connection history",
    "Tethering support",
    "Data export",
  ];
  const comingSoon = ["Host packs", "Community stats"];

  return (
    <Slide bg={`linear-gradient(175deg, ${BRAND.bg} 0%, #0f172a 50%, ${BRAND.bg} 100%)`}>
      <Glow x="30%" y="8%" size={500} color={BRAND.info} opacity={0.08} />
      <Glow x="50%" y="55%" size={400} color={BRAND.success} opacity={0.06} />
      <Grid opacity={0.03} />

      {/* App icon */}
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
          boxShadow: "0 8px 40px rgba(96,165,250,0.25)",
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
          And so
          <br />
          much more.
        </div>
      </div>

      {/* Feature pills */}
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
          <Pill key={f} fontSize={28}>
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
            color: "rgba(255,255,255,0.35)",
            letterSpacing: "0.08em",
            textTransform: "uppercase",
            marginBottom: 16,
          }}
        >
          Coming Soon
        </div>
        <div style={{ display: "flex", gap: 14, justifyContent: "center" }}>
          {comingSoon.map((f) => (
            <div
              key={f}
              style={{
                background: "rgba(255,255,255,0.04)",
                color: "rgba(255,255,255,0.3)",
                fontSize: 26,
                fontWeight: 500,
                padding: "12px 24px",
                borderRadius: 14,
                border: "1px solid rgba(255,255,255,0.08)",
                fontFamily: "var(--font-geist-mono)",
              }}
            >
              {f}
            </div>
          ))}
        </div>
      </div>

      {/* Bottom tagline */}
      <div
        style={{
          position: "absolute",
          bottom: 100,
          left: 0,
          right: 0,
          textAlign: "center",
        }}
      >
        <div style={{ fontSize: 32, fontWeight: 400, color: BRAND.info }}>
          RIPDPI
        </div>
      </div>
    </Slide>
  );
}

// ══════════════════════════════════════════════════════════════════════
// Feature Graphic (1024x500)
// ══════════════════════════════════════════════════════════════════════
function FeatureGraphicSlide() {
  return (
    <div
      style={{
        width: FEATURE_GRAPHIC.w,
        height: FEATURE_GRAPHIC.h,
        background: `linear-gradient(135deg, ${BRAND.bg} 0%, #0f172a 50%, ${BRAND.card} 100%)`,
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
      <Grid opacity={0.03} />
      <Glow x="5%" y="10%" size={300} color={BRAND.info} opacity={0.12} />
      <Glow x="70%" y="40%" size={250} color={BRAND.success} opacity={0.08} />

      <img
        src="/app-icon.png"
        alt=""
        style={{
          width: 120,
          height: 120,
          borderRadius: 28,
          boxShadow: "0 8px 30px rgba(96,165,250,0.2)",
          position: "relative",
          zIndex: 1,
        }}
      />
      <div
        style={{
          color: BRAND.text,
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
          color: BRAND.info,
          fontSize: 22,
          fontWeight: 400,
          position: "relative",
          zIndex: 1,
        }}
      >
        Browse without borders
      </div>
    </div>
  );
}

// ── Slide registry ─────────────────────────────────────────────────────
const SLIDES = [
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
          border: `1px solid ${BRAND.border}`,
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
          color: "#888",
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
    <Suspense fallback={<div style={{ background: "#0a0a0a", minHeight: "100vh" }} />}>
      <ScreenshotsPage />
    </Suspense>
  );
}

function ScreenshotsPage() {
  const searchParams = useSearchParams();
  const slideParam = searchParams.get("slide");

  // Single slide full-resolution mode: ?slide=1 through ?slide=6, or ?slide=fg
  if (slideParam) {
    if (slideParam === "fg") {
      return <FeatureGraphicSlide />;
    }
    const idx = parseInt(slideParam) - 1;
    const slide = SLIDES[idx];
    if (slide) {
      const C = slide.component;
      return <C />;
    }
  }

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

        const opts = { width: w, height: h, pixelRatio: 1, cacheBust: true, backgroundColor: "#121212" };
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

      const opts = { width: w, height: h, pixelRatio: 1, cacheBust: true, backgroundColor: "#121212" };
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
        background: "#0a0a0a",
        color: BRAND.text,
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
              color: "#666",
              margin: "4px 0 0",
              fontFamily: "var(--font-geist-mono)",
            }}
          >
            {SLIDES.length} phone slides + feature graphic | {PHONE_W}x{PHONE_H}px | Click to
            export
          </p>
        </div>
        <button
          onClick={exportAll}
          disabled={!!exporting}
          style={{
            background: exporting ? "#333" : BRAND.info,
            color: "#fff",
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
                <C />
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
              <FeatureGraphicSlide />
            </div>
          </ScreenshotPreview>
        </div>
      </div>
    </div>
  );
}
