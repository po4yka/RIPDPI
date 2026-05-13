import type { SlideCopy } from "./types";

export const en: SlideCopy = {
  locale: "en",
  dir: "ltr",

  slide1: {
    label: "Internet Freedom",
    headline: ["Browse", "without", "borders"],
  },

  slide2: {
    eyebrow: "No Root Required",
    headline: ["One tap.", "No root."],
    cards: [
      { title: "Works on any Android", desc: "No unlocking, no hacks" },
      { title: "Connect in one tap", desc: "Instant protection" },
      { title: "Local VPN or Proxy", desc: "Traffic never leaves your device" },
    ],
    bottomBadge: "Works on any device",
  },

  slide3: {
    label: "Privacy & Security",
    headline: ["Your privacy.", "Your rules."],
    pills: ["Encrypted DNS", "WebRTC block", "Bio lock"],
  },

  slide4: {
    label: "Advanced Controls",
    headline: ["Fine-tune", "every packet"],
    sectionEncryptedDns: "Encrypted DNS",
    sectionDpiBypass: "DPI Bypass",
    sectionModes: "Connection Modes",
    modeVpn: "Local VPN",
    modeProxy: "Local Proxy",
    footer: "Presets for beginners. Full control for experts.",
  },

  slide5: {
    label: "Built-in Diagnostics",
    headline: ["See what’s", "really", "happening"],
  },

  slide6: {
    headline: ["And so", "much more."],
    features: [
      "Per-network policies",
      "AdGuard compatible",
      "Session telemetry",
      "WebRTC protection",
      "Biometric lock",
      "Connection history",
      "Tethering support",
      "Data export",
    ],
    comingSoonLabel: "Coming Soon",
    comingSoon: ["Host packs", "Community stats"],
  },

  featureGraphic: {
    tagline: "Browse without borders",
  },
};
