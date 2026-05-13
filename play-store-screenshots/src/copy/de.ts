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
      { title: "Läuft auf jedem Android", desc: "Ohne Entsperren, ohne Hacks" },
      { title: "Mit einem Tipp verbinden", desc: "Sofort geschützt" },
      { title: "Lokales VPN oder Proxy", desc: "Daten bleiben auf Ihrem Gerät" },
    ],
    bottomBadge: "Auf jedem Gerät einsetzbar",
  },

  slide3: {
    label: "Privatsphäre & Sicherheit",
    headline: ["Ihre Daten.", "Ihre Regeln."],
    pills: ["DNS verschlüsselt", "WebRTC-Sperre", "Biometrie"],
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
      "Regeln pro Netzwerk",
      "AdGuard-kompatibel",
      "Sitzungstelemetrie",
      "WebRTC-Schutz",
      "Biometrische Sperre",
      "Verbindungsverlauf",
      "Tethering-Support",
      "Datenexport",
    ],
    comingSoonLabel: "Demnächst",
    comingSoon: ["Host-Pakete", "Community-Statistiken"],
  },

  featureGraphic: {
    tagline: "Surfen ohne Grenzen",
  },
};
