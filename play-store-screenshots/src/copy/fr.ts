import type { SlideCopy } from "./types";

// French uses narrow no-break space (U+202F) before « : » and « ? ».
const NBSP = " ";

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
      { title: "Marche sur tout Android", desc: "Ni déverrouillage, ni hack" },
      { title: "Connexion en un geste", desc: "Protection immédiate" },
      { title: "VPN ou proxy local", desc: "Vos données restent sur l’appareil" },
    ],
    bottomBadge: "Compatible avec tout appareil",
  },

  slide3: {
    label: "Vie privée & sécurité",
    headline: ["Votre vie privée.", "Vos règles."],
    pills: ["DNS chiffré", "Blocage WebRTC", "Verrou bio"],
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
      "Règles par réseau",
      "Compatible AdGuard",
      "Télémétrie de session",
      "Protection WebRTC",
      "Verrou biométrique",
      "Historique des connexions",
      "Partage de connexion",
      "Export des données",
    ],
    comingSoonLabel: "Bientôt",
    comingSoon: ["Listes d’hôtes", "Stats communauté"],
  },

  featureGraphic: {
    tagline: "Naviguez sans frontières",
  },
};
