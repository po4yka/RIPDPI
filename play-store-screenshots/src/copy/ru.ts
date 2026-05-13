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
      { title: "Работает на любом Android", desc: "Без прошивок и хаков" },
      { title: "Подключение в одно касание", desc: "Мгновенная защита" },
      { title: "Локальный VPN или прокси", desc: "Трафик не покидает устройство" },
    ],
    bottomBadge: "Работает на любом устройстве",
  },

  slide3: {
    label: "Приватность и защита",
    headline: ["Ваша приватность.", "Ваши правила."],
    pills: ["Шифрованный DNS", "Блок WebRTC", "Био-замок"],
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
      "Политики для сетей",
      "Совместимо с AdGuard",
      "Телеметрия сессий",
      "Защита от WebRTC",
      "Биометрический замок",
      "История подключений",
      "Поддержка точки доступа",
      "Экспорт данных",
    ],
    comingSoonLabel: "Скоро",
    comingSoon: ["Пакеты хостов", "Статистика сообщества"],
  },

  featureGraphic: {
    tagline: "Интернет без границ",
  },
};
