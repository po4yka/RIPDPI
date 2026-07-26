# Localization provenance & review ledger

This ledger records, per locale, the source revision the translation was
verified against, key-parity status, translation origin (human vs. machine),
and reviewer sign-off. It is the audit trail behind the
[localization pipeline](localization.md).

Update this ledger in the same PR that lands or refreshes a locale.

## Review ledger

Structural revalidation date: **2026-07-14**. `app/src/main/res/values/strings.xml` contains 4,005 translatable `<string>` entries plus 17 `<plurals>` entries; `diagnostics_fallbacks.xml` and `diagnostics_initial_states.xml` contribute another 41 translated strings, for 4,063 app keys. `core/service/src/main/res/values/strings.xml` contributes 4 more, for 4,067 translated keys across both modules. The app has 47 technical entries marked `translatable="false"`, and the service module has 2; these 49 entries are intentionally excluded from locale resources. Every shipped locale has structural key parity across every XML file in its resource directory. This revalidation checks XML/key structure only; it does not replace the linguistic review dates below.

| Locale | Resource dir | Keys | Missing | Origin | MT-origin | Reviewer | Review date |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Russian (ru) | `values-ru/` | 4063 | 0 | pre-existing | unknown | Nikita Pochaev (maintainer) | 2026-05-30 |
| Spanish (es) | `values-es/` | 4063 | 0 | pre-existing | unknown | Nikita Pochaev (maintainer) | 2026-05-30 |
| German (de) | `values-de/` | 4063 | 0 | pre-existing | unknown | Nikita Pochaev (maintainer) | 2026-05-30 |
| French (fr) | `values-fr/` | 4063 | 0 | pre-existing | unknown | Nikita Pochaev (maintainer) | 2026-05-30 |
| Persian (fa) | `values-fa/` | 4063 | 0 | pre-existing | unknown | Nikita Pochaev (maintainer) | 2026-05-30 |
| Arabic (ar) | `values-ar/` | 4063 | 0 | **machine-translated** | **yes** | Nikita Pochaev (maintainer, structural review) | 2026-05-31 |
| Simplified Chinese (zh-CN) | `values-zh-rCN/` | 4063 | 0 | pre-existing | unknown | Nikita Pochaev (maintainer) | 2026-05-30 |
| Hindi (hi) | `values-hi/` (`strings.xml` + `strings2.xml`) | 4063 | 0 | **machine-translated** | **yes** | Native-speaker review pending | — |

`core/service` strings (4 translatable) mirror the same locales with 0 missing keys.

### Sign-off

The recorded maintainer sign-off applies to the locale snapshots and dates shown above; it does not imply that strings added later have received a new linguistic pass. Key parity is enforced continuously by
Android lint (`MissingTranslation severity="error"`) and the
[CI export gate](localization.md#ci-export-gate).

### MT-origin disclosure (Arabic)

The Arabic (`ar`) locale was produced by machine translation into Modern
Standard Arabic and reviewed by the maintainer for structural correctness
(placeholder/entity preservation, RTL rendering, key parity). It has **not**
yet had a native-speaker linguistic pass. This is disclosed here and in the
`values-ar/strings.xml` header comment so a future native reviewer (and the
Play Store Data Safety / QA trail) can see the provenance. Native-speaker
review of `ar` is tracked as follow-up; it does not block the locale shipping,
which is the maintainer's explicit decision for first-wave Arabic coverage.

Hindi (`hi`) is likewise machine-translated and structurally complete, split across `strings.xml` and `strings2.xml`; a native-speaker linguistic review is still pending.

## Font & glyph coverage

The brand typeface (Geist Sans / Geist Mono, bundled under
`app/src/main/res/font/`) covers Latin and Cyrillic — so `en`, `ru`, and the
Latin-script locales (`es`, `de`, `fr`) render entirely in Geist. Geist has
**no Arabic-script coverage**, which affects `fa` (Persian) and `ar` (Arabic).

`RipDpiTheme` declares its families as
`FontFamily(Font(R.font.geist_sans_*))` (see
`app/src/main/kotlin/com/poyka/ripdpi/ui/theme/Type.kt`). Android/Compose font
resolution falls back **per missing glyph** to the platform font chain — on
Android that is Noto Naskh Arabic / Noto Sans Arabic — so Persian and Arabic
text renders correctly through the system fallback without bundling an extra
Arabic webfont. This keeps the APK lean (no ~400 KB Arabic font asset) while
guaranteeing glyph coverage.

If brand-consistent Arabic shaping is later desired, bundle Vazirmatn or Noto
Naskh Arabic under `res/font/` and add it as a fallback face — but that is an
enhancement, not a correctness requirement.

## Freshness

When a source string changes, the translation falls out of date silently
(parity stays green because the key still exists). Current workflows detect
key additions/removals, not changed English values under an existing key;
reviewers must inspect source-value changes explicitly. Until a native pass
lands for `ar`, treat its strings as MT drafts subject to revision.
