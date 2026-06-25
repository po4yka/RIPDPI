import type { SlideCopy } from "./types";

export const fa: SlideCopy = {
  locale: "fa",
  dir: "rtl",

  slide1: {
    label: "اینترنت آزاد",
    headline: ["بدون", "مرز", "بگردید"],
  },

  slide2: {
    eyebrow: "بدون نیاز به روت",
    headline: ["یک لمس.", "بدون روت."],
    cards: [
      { title: "دور زدن محلی DPI", desc: "روی دستگاه، بدون سرور" },
      { title: "یا رلهٔ خودتان", desc: "VLESS، Hysteria2، Tor و بیشتر" },
      { title: "DNS رمزنگاری‌شدهٔ داخلی", desc: "DoH، DoT، DNSCrypt" },
    ],
    bottomBadge: "روی هر دستگاهی",
  },

  slide3: {
    label: "رله‌های راه دور",
    headline: ["دور زدن محلی", "یا رلهٔ شما"],
  },

  slide4: {
    label: "کنترل پیشرفته",
    headline: ["تنظیم دقیق", "هر بسته"],
    sectionEncryptedDns: "DNS رمزنگاری‌شده",
    sectionDpiBypass: "دور زدن DPI",
    sectionModes: "حالت‌های اتصال",
    modeVpn: "VPN محلی",
    modeProxy: "پروکسی محلی",
    footer: "پیش‌فرض برای تازه‌کارها. کنترل کامل برای حرفه‌ای‌ها.",
  },

  slide5: {
    label: "تشخیص داخلی",
    headline: ["ببینید واقعاً", "چه", "می‌گذرد"],
  },

  slide6: {
    headline: ["و خیلی", "بیشتر."],
    features: [
      "تونل تقسیم‌شده",
      "قواعد مسیریابی",
      "تشخیص DPI",
      "شفافیت داده‌ها",
      "۷ آیکون برنامه",
      "پشتیبان‌گیری و بازیابی",
      "سیاست‌ها برای هر شبکه",
      "قفل بیومتریک",
    ],
    comingSoonLabel: "به‌زودی",
    comingSoon: ["بسته‌های میزبان", "آمار جامعه"],
  },

  featureGraphic: {
    tagline: "بدون مرز بگردید",
  },
};
