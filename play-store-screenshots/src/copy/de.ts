import type { SlideCopy } from "./types";

export const de: SlideCopy = {
  locale: "de",
  dir: "ltr",

  slide1: {
    label: "Internet ohne Grenzen",
    headline: ["Surfen", "ohne", "Grenzen"],
  },

  slide2: {
    eyebrow: "Kein Root nötig",
    headline: ["Ein Tipp.", "Kein Root."],
    cards: [
      { title: "Lokale DPI-Umgehung", desc: "Auf dem Gerät, ohne Server" },
      { title: "Oder eigenes Relay", desc: "VLESS, Hysteria2, Tor & mehr" },
      { title: "Verschlüsseltes DNS", desc: "DoH, DoT, DNSCrypt" },
    ],
    bottomBadge: "Auf jedem Gerät einsetzbar",
  },

  slide3: {
    label: "Remote-Relays",
    headline: ["Lokal umgehen", "oder Relay"],
  },

  slide4: {
    label: "Feine Steuerung",
    headline: ["Jedes Paket", "im Griff"],
    sectionEncryptedDns: "Verschlüsseltes DNS",
    sectionDpiBypass: "DPI-Umgehung",
    sectionModes: "Verbindungsmodi",
    modeVpn: "Lokales VPN",
    modeProxy: "Lokaler Proxy",
    footer: "Presets für Einsteiger. Volle Kontrolle für Profis.",
  },

  slide5: {
    label: "Eingebaute Diagnose",
    headline: ["Sehen Sie,", "was wirklich", "passiert"],
  },

  slide6: {
    headline: ["Und vieles", "mehr."],
    features: [
      "Split-Tunnel",
      "Routing-Regeln",
      "DPI-Erkennung",
      "Datentransparenz",
      "7 App-Symbole",
      "Backup & Restore",
      "Regeln pro Netzwerk",
      "Biometrische Sperre",
    ],
    comingSoonLabel: "Demnächst",
    comingSoon: ["Host-Pakete", "Community-Statistiken"],
  },

  featureGraphic: {
    tagline: "Surfen ohne Grenzen",
  },
};
