import type { SlideCopy } from "./types";
import { en } from "./en";
import { ru } from "./ru";
import { es } from "./es";
import { de } from "./de";
import { fr } from "./fr";
import { fa } from "./fa";
import { zhCN } from "./zh-CN";

export const LOCALES = ["en", "ru", "es", "de", "fr", "fa", "zh-CN"] as const;
export type Locale = (typeof LOCALES)[number];

export const DEFAULT_LOCALE: Locale = "en";

const COPY: Record<Locale, SlideCopy> = {
  en,
  ru,
  es,
  de,
  fr,
  fa,
  "zh-CN": zhCN,
};

export function isLocale(value: string | null | undefined): value is Locale {
  return !!value && (LOCALES as readonly string[]).includes(value);
}

export function getCopy(locale: string | null | undefined): SlideCopy {
  return isLocale(locale) ? COPY[locale] : COPY[DEFAULT_LOCALE];
}

export type { SlideCopy } from "./types";
