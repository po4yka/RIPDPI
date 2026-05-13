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
      { title: "روی هر اندرویدی کار می‌کند", desc: "بدون آنلاک یا هک" },
      { title: "اتصال با یک لمس", desc: "محافظت فوری" },
      { title: "VPN یا پروکسی محلی", desc: "ترافیک از دستگاه خارج نمی‌شود" },
    ],
    bottomBadge: "روی هر دستگاهی",
  },

  slide3: {
    label: "حریم خصوصی و امنیت",
    headline: ["حریم خصوصی شما.", "قواعد شما."],
    pills: ["DNS رمزنگاری‌شده", "مسدودسازی WebRTC", "قفل بیومتریک"],
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
      "سیاست‌ها برای هر شبکه",
      "سازگار با AdGuard",
      "تله‌متری نشست",
      "محافظت در برابر WebRTC",
      "قفل بیومتریک",
      "تاریخچه اتصال",
      "پشتیبانی از تترینگ",
      "خروجی داده‌ها",
    ],
    comingSoonLabel: "به‌زودی",
    comingSoon: ["بسته‌های میزبان", "آمار جامعه"],
  },

  featureGraphic: {
    tagline: "بدون مرز بگردید",
  },
};
