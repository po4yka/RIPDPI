# Localization provenance & review ledger

This ledger records, per locale, the source revision the translation was
verified against, key-parity status, translation origin (human vs. machine),
and reviewer sign-off. It is the audit trail behind the
[localization pipeline](localization.md) and the
[Localization expansion epic](../docs/tasks/issues/epic-localization-expansion.md).

Update this ledger in the same PR that lands or refreshes a locale.

## Review ledger

Audit date: **2026-05-30** (refreshed 2026-05-31 after integration with the
extended-outbound-protocol and system-HTTP-proxy epics on `main`, which added
119 source strings). Source: `app/src/main/res/values/strings.xml`
(3,049 translatable app `<string>` + 4 app `<plurals>`; plus 4 `core/service`
`<string>` = 3,053 manifest keys). 42 technical tokens are `translatable="false"`
(40 app + 2 service; see [glossary](localization-glossary.md)) and are
intentionally excluded from every locale.

| Locale | Resource dir | Keys | Missing | Origin | MT-origin | Reviewer | Review date |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Russian (ru) | `values-ru/` | 3049 | 0 | pre-existing | unknown | Nikita Pochaev (maintainer) | 2026-05-30 |
| Spanish (es) | `values-es/` | 3049 | 0 | pre-existing | unknown | Nikita Pochaev (maintainer) | 2026-05-30 |
| German (de) | `values-de/` | 3049 | 0 | pre-existing | unknown | Nikita Pochaev (maintainer) | 2026-05-30 |
| French (fr) | `values-fr/` | 3049 | 0 | pre-existing | unknown | Nikita Pochaev (maintainer) | 2026-05-30 |
| Persian (fa) | `values-fa/` | 3049 | 0 | pre-existing | unknown | Nikita Pochaev (maintainer) | 2026-05-30 |
| Arabic (ar) | `values-ar/` | 3049 | 0 | **machine-translated** | **yes** | Nikita Pochaev (maintainer) | 2026-05-31 |
| Simplified Chinese (zh-CN) | `values-zh-rCN/` | 3049 | 0 | pre-existing | unknown | Nikita Pochaev (maintainer) | 2026-05-30 |

`core/service` strings (4 translatable) mirror the same locales with 0 missing keys.

### Sign-off

The maintainer (Nikita Pochaev) has reviewed and signed off the landed
locales above as of 2026-05-30. Key parity is enforced continuously by
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
(parity stays green because the key still exists). The pipeline's weekly
string-diff (see [localization.md](localization.md)) surfaces changed source
keys so reviewers can re-translate; until a native pass lands for `ar`, treat
its strings as MT drafts subject to revision.
