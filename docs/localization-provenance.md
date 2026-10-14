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
| German (de) | `values-de/` (all XML files) | 5010 | 0 | **pre-existing; AI-authored audited corrections** | **yes for corrections** | Native-speaker review pending for this update | — |
| French (fr) | `values-fr/` | 4063 | 0 | pre-existing | unknown | Nikita Pochaev (maintainer) | 2026-05-30 |
| Persian (fa) | `values-fa/` | 4063 | 0 | pre-existing | unknown | Nikita Pochaev (maintainer) | 2026-05-30 |
| Arabic (ar) | `values-ar/` | 4063 | 0 | **machine-translated** | **yes** | Nikita Pochaev (maintainer, structural review) | 2026-05-31 |
| Simplified Chinese (zh-CN) | `values-zh-rCN/` | 4063 | 0 | pre-existing | unknown | Nikita Pochaev (maintainer) | 2026-05-30 |
| Hindi (hi) | `values-hi/` (all XML files) | 4948 | 0 | **machine-translated; audited corrections** | **yes** | Native-speaker review pending | — |
| Brazilian Portuguese (pt-BR) | `values-pt-rBR/` (all app and service XML files) | 5027 | 0 | **machine-translated; AI-authored audited corrections** | **yes** | Native-speaker review pending for this update | — |

Structural revalidation for `pt-BR` (2026-08-25): source grew since the 2026-07-14 pass; the current source set is 4,129 unique translated keys across `app` (4,112 translatable `<string>` entries plus 16 `<plurals>` in `values/strings.xml`, plus 67 keys across the actuator/home-diagnostics/initial-states/fallbacks/archive-disclosure topical files), with `core/service` contributing 4 more. The pt-BR locale was generated against this exact tree and passes `scripts/ci/check-locale-parity.sh` with 0 missing keys.

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

Hindi (`hi`) is machine-translated and structurally complete across its XML files; a native-speaker linguistic review is still pending.

## Hindi and shared presentation corrections — 2026-10-13

The audit baseline is `17172d4a1fe3bfc02faf6366efad868da2ad3dce`.
The change corrects 63 active and seven unused Hindi resource entries.
It also corrects 42 stale profile-editor references in English, German,
French, Persian, Arabic, and Brazilian Portuguese. These references now
use the current editor name.
It translates 46 descriptive settings array labels, two settings literals,
and 21 known diagnostic and built-in host-pack texts. The shared labels
use 45 new keys in all ten locales. Technical option values, array order,
raw evidence, and custom host-pack descriptions remain exact. Catalog dates
read the selected app locale after language changes. Known PT captions
also update when the app language changes and the probe result is retained.
The shared option reader preserves numeric values through Android typed
resources. The final app set has 4,948 strings and 23 plural resources;
the source string manifest has 4,958 keys across app and service.
Three disputed DNS
terms remain unchanged pending technical Hindi review.

The corrections and new translations were authored with an AI assistant.
Resource contracts and behavioral tests provide local evidence; they do not
record a new maintainer or native-speaker sign-off. Verification for
`UIX-1791880509070975` is recorded in
`openspec/changes/fix-audited-hindi-localization/verification.md`.

### MT-origin disclosure (Brazilian Portuguese)

The Brazilian Portuguese (`pt-BR`) locale was produced by machine translation
into natural Brazilian Portuguese and validated structurally (key parity,
format-specifier and escape preservation, plural quantity sets, pinned canon
terms asserted by `TerminologyCanonTest`, glossary do-not-translate tokens kept
verbatim). It has **not** yet had a native-speaker linguistic pass; this is
disclosed here and in the `values-pt-rBR/strings.xml` header comment.
Native-speaker review of `pt-BR` is tracked as follow-up and does not block the
locale shipping.

## Russian audit corrections (2026-10-12)

The source baseline was `15b634db6d68a152957d388ddcbce83cc0b9f539`.
This pass corrected 147 existing Russian strings from the confirmed audit.
It covers sensitive-settings warnings, permission privacy text, action labels,
update and profile-sharing flows, technical meanings, and browser remediation.
It also moves runtime event counts and active-session counts to Android plurals.

The new diagnostic presentation resources have translations in all ten locales.
The translated source set now has 4,848 app entries (4,827 strings and 21 plural
resources) and 11 service entries (10 strings and one plural resource).
The source string manifest has 4,837 keys. These counts exclude resources marked
`translatable="false"` and the separate Quick Connect resource set.

The new translations were authored with an AI assistant. XML structure, key
parity, format arguments, Russian plural categories, and the audited safety and
action meanings were checked locally. The resource regression suite has four
passing tests; it reports 11 failures against the source baseline. This entry
does not record a new maintainer or native-speaker sign-off for other locales.
It does not claim a device test or a full linguistic review of the app.

## Chinese audit corrections (2026-10-12)

The source baseline was `76169878cae9b947e58140d2a2d788dbaf7466bc`.
This pass corrected 116 active Chinese resource entries from the confirmed audit,
plus the dormant redaction message and the Quick Connect tile label. It preserves
credential warnings, explicit-export consent, capture retention, protocol tokens,
and the visible names of recovery actions.

The diagnostic tool and connection-state presentation adds 57 source strings.
Each has a translation in all ten locales. The source set now has 4,905 app
entries (4,884 strings and 21 plural resources), 11 service entries (10 strings
and one plural resource), and 15 separate Quick Connect strings. The source
string manifest has 4,894 keys; it excludes plurals and the Quick Connect set.

The corrections and new translations were authored with an AI assistant.
Resource tests check safety meanings, action references, network terminology,
key types, and format arguments across every locale. The five Chinese resource
tests pass locally and report 116 failures against the audit baseline. Existing
Russian resource tests also pass. This entry does not record a new maintainer
or native-speaker sign-off. Device tests and a full linguistic review of other
locales are not claimed.

## Spanish audit corrections (2026-10-13)

The source baseline was `2f6803083d74880f3c47e209aa83dfe9d6953e33`.
This pass corrected 125 active Spanish resource entries and four unused entries
from the confirmed audit. It covers untranslated warnings and flows, privacy
terms, recovery action names, strategy grammar, and the Quick Connect tile.
Credential warnings, explicit export consent, packet capture retention, and
protocol tokens retain their source meaning.

The diagnostic presentation adds 19 source strings and two plural resources.
Each has a translation in all ten locales. The source set now has 4,926 app
entries (4,903 strings and 23 plural resources), 11 service entries (10 strings
and one plural resource), and 15 separate Quick Connect strings. The source
string manifest has 4,913 keys; it excludes plurals and the Quick Connect set.

The corrections and new translations were authored with an AI assistant.
The five Spanish resource tests check audited copy, safety meanings, action
references, grammar, and resource contracts across all ten locales. They pass
locally; the four copy checks report 123 failures against the audit baseline.
Existing Russian and Chinese resource tests also pass. This entry does not
record a new maintainer or native-speaker sign-off. Device tests and a full
linguistic review of other locales are not claimed.

## German audit corrections (2026-10-13)

The source baseline was `ddd0024d494fb2e734223b8c6e77ec239a255bbe`.
This pass corrects exactly 107 active German resource keys and five unused
keys from the confirmed audit. It covers English controls and warnings,
redaction and control-group meanings, recovery actions, metering labels,
option references, and decoding rather than decryption. Disputed URI
Authority/Tail terms and optional editorial preferences remain outside scope.

Diagnostic presentation adds 38 source strings and one plural resource.
Every key is translated in all ten locales. The app has 5,010 translatable
entries (4,986 strings and 24 plurals); service has 11 entries and Quick
Connect has 15 separate strings. The source manifest has 4,996 string keys.

Known Telegram summary captions and readable share prose use resources;
unknown values and raw evidence remain exact. Telegram native rates remain
bytes per second on the wire. UI and share output convert those rates to
bits per second without Long overflow. Telegram and sparkline numbers use
the selected app locale. Core summary headings accept localized text while
the default technical-export headings remain unchanged.

The six German resource regressions reproduced 107 failures on the audit
baseline. After correction, all 26 Russian, Chinese, Spanish, Hindi and
German resource tests pass. Parity/export, format arguments and plural
categories pass for all locales. Kotlin behavior and all eight German render
checks pass. The combined tree passed 2,521 app tests, 1,652 diagnostics tests,
static analysis, app/service lint and 297 Full screenshot tests. The owning
OpenSpec verification artifact records the existing RTL ignore, missing real
Simple bundle and hosted CI status. All local main commits, including the
14 Material 3 commits, were published with explicit user authorization.

Corrections and new captions were authored with an AI assistant. The prior
2026-05-30 German maintainer review does not sign off these new translations.
Native-speaker, device and full linguistic acceptance of the other locales
are not claimed.

## French audit corrections (2026-10-14)

The source baseline is `3458ed9c1541d9a5217e5c4618845ecefd4a97bd`.
This pass corrects exactly 138 existing French resource keys from the audit:
134 have a discovered production call site, and four have no discovered
production call site. The four are `home_diagnostics_detection_permission_required`,
`home_mode_card_remote_relay_disabled_hint`, `support_settings_applied_action`,
and `share_link_preview_privacy_message`. They remain separate from active
UI findings. The corrections include 90 English fallback values, consent and
retention warnings, recovery controls, temporary policy suppression, backup
server selection, QUIC transport emphasis, throughput and French grammar.
Disputed metering terms and the RTT heading remain unchanged.

The protocol-filter hint also corrects a source error. It now explains that
clearing HTTP and HTTPS removes the TCP protocol filter, while UDP bypass has
separate settings. This one existing key changes in all ten locales; the nine
non-French changes are separate from the 138 French keys. Runtime matching,
host filters, protocol flags and exported values do not change.

Shared diagnostic presentation adds six source strings for known RTT ranges
and debug-card captions. Every key has a value in all ten locales. RTT bounds
keep the technical `ms` unit. Debug input counts use label-first templates.
The app has 5,016 translatable entries (4,992 strings and 24 plurals); service
has 11 entries, and Quick Connect has 15 separate strings. The regenerated
source manifest has 5,002 string keys.

The French resource tests first reproduced all 90 English fallback failures.
Separate RED and GREEN cycles then checked the confirmed semantic, action and
grammar repairs and the new shared templates. All 40 resource regression tests
for Russian, Chinese, Spanish, Hindi, German and French pass locally. Parity,
translation export, format arguments and native language names pass across
all ten locales. The owning OpenSpec verification artifact records Kotlin,
render, lint, combined-tree and hosted CI evidence separately.

Corrections and new captions were authored with an AI assistant. This entry
records no new maintainer or native-speaker sign-off. Resource tests do not
prove device acceptance or full linguistic correctness of the other locales.

## Brazilian Portuguese audit corrections (2026-10-14)

The source baseline is `af004adb89dbf443be8f190a3cead0e275026b24`.
This pass corrects 14 active Brazilian Portuguese resource keys from the audit.
The corrections distinguish cache misses from DNS errors, describe the Noise
prelude length, use the visible fake-ordering options, keep the geosite `full:`
type, name Android DNS particular, identify initial setup checks, restore the
Resume now action, distinguish retries from all attempts, and use the Quick
Connect product name in the connected tile.

Two related Brazilian Portuguese captions also use DNS particular for
consistency with the three audited Android-setting captions. These two
supporting changes are separate from the 14 confirmed findings.

The Noise help also corrects an ambiguous English source. It now states that
a prelude contains random bytes, is sent once before the real payload, and
has a length chosen from the configured range. This existing key changes in
all ten locales; the nine non-Portuguese changes are separate from the 14
Brazilian Portuguese keys. The native encoder and raw configuration do not
change. No resource keys are added or removed.

The app has 5,016 translatable entries (4,992 strings and 24 plurals); service
has 11 entries, for 5,027 entries across both modules. Quick Connect has 15
separate strings. The source string manifest remains at 5,002 keys. The glossary
now lists all ten locales and their nine translated resource directories.

The resource regressions check the audited meanings and action references.
Separate RED and GREEN cycles reproduce each corrected behavior. Resource
contracts check key types, format arguments and native language names across
all ten locales. The owning OpenSpec verification artifact records resource,
Kotlin, render, lint and hosted CI evidence separately.

Corrections and translations were authored with an AI assistant. This entry
records no new maintainer or native-speaker sign-off. Resource tests do not
prove device acceptance or full linguistic correctness of the other locales.

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
