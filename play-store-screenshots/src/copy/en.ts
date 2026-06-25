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
      { title: "Local DPI bypass", desc: "On-device — no server needed" },
      { title: "Or your own relay", desc: "VLESS, Hysteria2, Tor & more" },
      { title: "Encrypted DNS built in", desc: "DoH, DoT, DNSCrypt" },
    ],
    bottomBadge: "Works on any device",
  },

  slide3: {
    label: "Remote Relays",
    headline: ["Local bypass", "or your relay"],
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
      "Split tunnel",
      "Routing rules",
      "DPI detection",
      "Data transparency",
      "7 app icons",
      "Backup & restore",
      "Per-network policies",
      "Biometric lock",
    ],
    comingSoonLabel: "Coming Soon",
    comingSoon: ["Host packs", "Community stats"],
  },

  featureGraphic: {
    tagline: "Browse without borders",
  },
};
