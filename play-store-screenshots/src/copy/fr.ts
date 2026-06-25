import type { SlideCopy } from "./types";

// French uses narrow no-break space (U+202F) before « : » and « ? ».
const NBSP = " ";

export const fr: SlideCopy = {
  locale: "fr",
  dir: "ltr",

  slide1: {
    label: "Internet libre",
    headline: ["Naviguez", "sans", "frontières"],
  },

  slide2: {
    eyebrow: "Sans root",
    headline: ["Un geste.", "Sans root."],
    cards: [
      { title: "Contournement DPI local", desc: "Sur l’appareil, sans serveur" },
      { title: "Ou votre propre relais", desc: "VLESS, Hysteria2, Tor & plus" },
      { title: "DNS chiffré intégré", desc: "DoH, DoT, DNSCrypt" },
    ],
    bottomBadge: "Compatible avec tout appareil",
  },

  slide3: {
    label: "Relais distants",
    headline: ["Contourner", "ou relayer"],
  },

  slide4: {
    label: "Contrôle avancé",
    headline: ["Réglez chaque", "paquet"],
    sectionEncryptedDns: "DNS chiffré",
    sectionDpiBypass: "Contournement DPI",
    sectionModes: `Modes de connexion`,
    modeVpn: "VPN local",
    modeProxy: "Proxy local",
    footer: `Presets pour débutants${NBSP}: contrôle total pour experts.`,
  },

  slide5: {
    label: "Diagnostic intégré",
    headline: ["Voyez ce qui", "se passe", "vraiment"],
  },

  slide6: {
    headline: ["Et bien", "plus encore."],
    features: [
      "Tunnel divisé",
      "Règles de routage",
      "Détection DPI",
      "Transparence des données",
      "7 icônes d’app",
      "Sauvegarde & restauration",
      "Règles par réseau",
      "Verrou biométrique",
    ],
    comingSoonLabel: "Bientôt",
    comingSoon: ["Listes d’hôtes", "Stats communauté"],
  },

  featureGraphic: {
    tagline: "Naviguez sans frontières",
  },
};
