import type { SlideCopy } from "./types";

export const ru: SlideCopy = {
  locale: "ru",
  dir: "ltr",

  slide1: {
    label: "Свобода в сети",
    headline: ["Интернет", "без", "границ"],
  },

  slide2: {
    eyebrow: "Без root-доступа",
    headline: ["Одно касание.", "Без root."],
    cards: [
      { title: "Локальный обход DPI", desc: "На устройстве, без сервера" },
      { title: "Или свой релей", desc: "VLESS, Hysteria2, Tor и другие" },
      { title: "Шифрованный DNS внутри", desc: "DoH, DoT, DNSCrypt" },
    ],
    bottomBadge: "Работает на любом устройстве",
  },

  slide3: {
    label: "Удалённые релеи",
    headline: ["Локальный", "обход или", "релей"],
  },

  slide4: {
    label: "Тонкая настройка",
    headline: ["Каждый пакет —", "под контролем"],
    sectionEncryptedDns: "Шифрованный DNS",
    sectionDpiBypass: "Обход DPI",
    sectionModes: "Режимы работы",
    modeVpn: "Локальный VPN",
    modeProxy: "Локальный прокси",
    footer: "Пресеты для новичков. Полный контроль для гиков.",
  },

  slide5: {
    label: "Встроенная диагностика",
    headline: ["Видно всё,", "что", "происходит"],
  },

  slide6: {
    headline: ["И это", "ещё не всё."],
    features: [
      "Раздельный туннель",
      "Правила маршрутизации",
      "Обнаружение DPI",
      "Прозрачность данных",
      "7 иконок приложения",
      "Бэкап и восстановление",
      "Политики для сетей",
      "Биометрический замок",
    ],
    comingSoonLabel: "Скоро",
    comingSoon: ["Пакеты хостов", "Статистика сообщества"],
  },

  featureGraphic: {
    tagline: "Интернет без границ",
  },
};
