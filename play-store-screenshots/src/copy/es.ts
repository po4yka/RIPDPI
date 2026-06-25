import type { SlideCopy } from "./types";

export const es: SlideCopy = {
  locale: "es",
  dir: "ltr",

  slide1: {
    label: "Libertad en la red",
    headline: ["Navega", "sin", "fronteras"],
  },

  slide2: {
    eyebrow: "Sin root",
    headline: ["Un toque.", "Sin root."],
    cards: [
      { title: "Evasión de DPI local", desc: "En el dispositivo, sin servidor" },
      { title: "O tu propio relé", desc: "VLESS, Hysteria2, Tor y más" },
      { title: "DNS cifrado integrado", desc: "DoH, DoT, DNSCrypt" },
    ],
    bottomBadge: "Compatible con cualquier dispositivo",
  },

  slide3: {
    label: "Relés remotos",
    headline: ["Evasión local", "o tu relé"],
  },

  slide4: {
    label: "Controles avanzados",
    headline: ["Afina", "cada paquete"],
    sectionEncryptedDns: "DNS cifrado",
    sectionDpiBypass: "Evasión de DPI",
    sectionModes: "Modos de conexión",
    modeVpn: "VPN local",
    modeProxy: "Proxy local",
    footer: "Presets para principiantes. Control total para expertos.",
  },

  slide5: {
    label: "Diagnóstico integrado",
    headline: ["Mira lo que", "de verdad", "pasa"],
  },

  slide6: {
    headline: ["Y mucho", "más."],
    features: [
      "Túnel dividido",
      "Reglas de enrutado",
      "Detección de DPI",
      "Transparencia de datos",
      "7 iconos de app",
      "Copia y restauración",
      "Reglas por red",
      "Bloqueo biométrico",
    ],
    comingSoonLabel: "Próximamente",
    comingSoon: ["Listas de hosts", "Stats de la comunidad"],
  },

  featureGraphic: {
    tagline: "Navega sin fronteras",
  },
};
