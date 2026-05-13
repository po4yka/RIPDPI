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
      { title: "Funciona en cualquier Android", desc: "Sin desbloqueos ni hacks" },
      { title: "Conecta con un toque", desc: "Protección al instante" },
      { title: "VPN o proxy local", desc: "El tráfico no sale de tu dispositivo" },
    ],
    bottomBadge: "Compatible con cualquier dispositivo",
  },

  slide3: {
    label: "Privacidad y seguridad",
    headline: ["Tu privacidad.", "Tus reglas."],
    pills: ["DNS cifrado", "Bloqueo WebRTC", "Bloqueo bio"],
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
      "Reglas por red",
      "Compatible con AdGuard",
      "Telemetría de sesión",
      "Protección WebRTC",
      "Bloqueo biométrico",
      "Historial de conexiones",
      "Soporte para tethering",
      "Exportar datos",
    ],
    comingSoonLabel: "Próximamente",
    comingSoon: ["Listas de hosts", "Stats de la comunidad"],
  },

  featureGraphic: {
    tagline: "Navega sin fronteras",
  },
};
