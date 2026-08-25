# Localization

RIPDPI ships **10 locales** today: `en` (source), `ru`, `es`, `de`, `fr`, `fa`, `ar`, `zh-CN`, `hi`, and `pt-BR`. Source strings live across `app/src/main/res/values/*.xml` and `core/service/src/main/res/values/strings.xml`. Each locale has matching `values-<qualifier>/` resources, and the enabled set is mirrored in `app/src/main/res/xml/locales_config.xml`. A locale may split resources across multiple XML files in its directory; Hindi currently uses both `strings.xml` and `strings2.xml`.

This document records how translations are managed, why, and exactly how a new contributor adds one.

## Compared options

| Criterion | **PR-only GitHub workflow** | Self-hosted Weblate | SaaS Crowdin |
| --- | --- | --- | --- |
| Runtime service required | **None — only Git + GitHub PRs** | Yes — a continuously-running web app + DB | Yes — hosted SaaS platform |
| Ops cost | **≈ $0 (GitHub free tier)** | VPS + DB + upgrades + maintenance hours/month | Subscription (free OSS tier exists but is usage- and seat-limited) |
| Geofence risk | **Low — Git is mirrorable to any forge; no single endpoint** | Medium — your one VPS/domain can be blocked or seized | Medium/High — a single proprietary SaaS domain can be geofenced or sanctioned out |
| MT-pretranslate UX | **None built-in — translators use their own MT, then hand-edit** | Built-in MT/glossary, in-browser editor | Polished MT pretranslate + TM + in-context editor |
| Translator onboarding friction | **Higher — needs Git/PR literacy or a guided fork flow** | Low — web login, no Git knowledge needed | Low — web login, no Git knowledge needed |
| Escalation if it disappears | **Push the mirror to Codeberg/GitLab, continue identical PR flow** | Rebuild/migrate the server; risk of lost in-flight strings | Export and migrate off the platform; vendor lock-in on TM/glossary |

## Decision

**PR-only GitHub workflow is the chosen pipeline.** Not Weblate, not Crowdin.

Rationale:

- **Hard "No backend server" project rule.** RIPDPI deliberately runs no first-party backend. A self-hosted Weblate would violate that rule outright, and a SaaS like Crowdin reintroduces a third-party runtime dependency the project is built to avoid.
- **Solo maintainer.** A self-hosted Weblate is a standing VPS with patching, backups, and abuse exposure — recurring ops burden one maintainer should not carry. The PR flow has zero runtime service to operate.
- **Geofence resistance.** A self-hosted instance or a SaaS lives behind a single domain that can be priced-out, sanctioned, or geofenced. A fork-and-PR flow is just Git: it can be mirrored to any forge and keeps working from anywhere a contributor can reach a Git remote.

The tradeoff we accept is higher translator onboarding friction (contributors need basic Git/PR literacy) and no built-in machine-translation pretranslate. We mitigate both with the step-by-step guide below and a glossary; translators are free to use any external MT tool and hand-edit the result before opening a PR.

## Ops cost estimate

- **PR-only (chosen): ≈ $0/month.** Everything runs on GitHub's free tier — repository hosting, pull requests, and GitHub Actions CI minutes for public repos. There is no server to provision, patch, or pay for.
- **Rejected Weblate (for comparison):** roughly a small VPS (on the order of a few dollars per month) **plus** recurring maintenance — security updates, database backups, version upgrades, and spam/abuse moderation — i.e. a few maintenance hours per month of a single maintainer's time. That standing cost, not the dollar figure, is the disqualifier under a solo-maintainer constraint.

## How to contribute a translation

Everything is done through a normal GitHub fork and pull request — no account on any external translation platform is required.

1. **Create the resource directory.** Copy `app/src/main/res/values/strings.xml` to a new locale directory `app/src/main/res/values-<qualifier>/strings.xml`.

   Use the Android resource qualifier convention (BCP-47, `b+` form or the legacy region form):
   - Language only: `values-pt` (Portuguese), `values-it` (Italian).
   - Language + region: `values-pt-rBR` (Brazilian Portuguese), `values-zh-rCN` (Simplified Chinese). The region is written as `r` + uppercase ISO-3166 code, e.g. `pt-rBR`, `zh-rCN`.

   Note the directory qualifier (`zh-rCN`) differs from the BCP-47 tag used in `locales_config.xml` (`zh-CN`) — see step 4.

2. **Translate the values, and only the values.** Edit the text content of each `<string>`, `<plurals>`, and `<string-array>` entry. Do **not** translate or alter the `name="…"` attributes, and do **not** include any string marked `translatable="false"` — those are not user-facing and must stay out of locale files. Preserve all format specifiers (`%1$s`, `%d`), escaping (`\'`, `\n`), and inline markup exactly as in the source.

3. **Mirror the service module.** Repeat steps 1–2 for `core/service/src/main/res/values/strings.xml` → `core/service/src/main/res/values-<qualifier>/strings.xml`. It is only 4 translatable strings, but it is required — a missing key there fails CI the same way.

4. **Register the locale.** Add the locale to `app/src/main/res/xml/locales_config.xml` as a `<locale android:name="…" />` entry. Use the **BCP-47 tag** here (e.g. `pt-BR`, `zh-CN`), which uses a hyphen and no `r` prefix — distinct from the resource directory qualifier in step 1.

5. **Run Android lint for both resource owners.** `MissingTranslation` is configured as an error, understands every XML file in a locale directory, and ignores source entries marked `translatable="false"`:

   ```bash
   ./gradlew :app:lintGithubFullDebug :core:service:lintDebug
   ```

6. **Open a pull request** with all of the above in a single commit/PR: the new `values-<qualifier>/` resources for both `:app` and `:core:service`, plus the `locales_config.xml` entry. CI runs the parity and lint gates automatically.

## String freeze

Source strings **freeze 2 weeks before each release.** During the freeze window, no new keys are added to and no existing keys are renamed in `app/src/main/res/values/strings.xml` or `core/service/src/main/res/values/strings.xml`. This gives translators a stable source set to work against so a translation PR cannot be invalidated mid-flight by a moving source. String additions queued during the freeze land in the next cycle after the release ships.

## Glossary

Canonical terminology and do-not-translate terms (product name, protocol names, UI nouns) live in [`docs/localization-glossary.md`](localization-glossary.md). Consult it before translating to keep terms consistent across locales.

## CI export gate

A CI gate prevents new source strings from silently shipping untracked for translation. The script `scripts/ci/check-translation-export.sh`, wired into the workflow `.github/workflows/i18n-export.yml`, fails the build when a new source string is added to `app/src/main/res/values/strings.xml` (or the service module) **without** being recorded in `config/i18n/translatable-keys.txt`. The keys file is the authoritative manifest of strings that are in scope for translation; updating it is the signal that a new key has been acknowledged and is ready to be picked up by translators.

> These three paths — `scripts/ci/check-translation-export.sh`, `.github/workflows/i18n-export.yml`, and `config/i18n/translatable-keys.txt` — are created by a sibling task and are forward-referenced here by path. If they are not yet present in the tree, that task owns adding them.

## Font & glyph coverage

The bundled brand font family is **Geist** (`Geist Sans`, `Geist Mono`, and the `Geist Pixel Circle` display face), shipped under `app/src/main/res/font/`. Geist covers **Latin and Cyrillic** scripts — sufficient for `en`, `ru`, `es`, `de`, and `fr`.

Geist does **not** ship Arabic-script or CJK glyphs. RIPDPI does **not** bundle a CJK or Arabic font:

- **Persian (`fa`)** and **Arabic (`ar`)** glyphs fall back automatically to the Android platform's **Noto Naskh Arabic** chain. Compose's font stack resolves unmapped code points through the platform fallback chain, so Persian renders correctly without any bundled Arabic face.
- **Simplified Chinese (`zh-CN`)** and **Hindi (`hi`)** similarly resolve through the platform Noto CJK and Devanagari fallback chains.

The practical rule: adding a Latin- or Cyrillic-script locale needs no font work. A new script that the platform fallback chain does not cover would need investigation, but for the currently shipped and near-term locales the platform fallback is sufficient — no bundled CJK/Arabic font is required.

## Escalation plan

Because the pipeline is *only* Git plus GitHub PRs, it has **no single proprietary point of failure**. If GitHub itself becomes unavailable (outage, geofence, account action):

1. **Mirror the repository to a secondary forge** — Codeberg or GitLab. The full history, source strings, and all locale files travel with the Git history; nothing lives only inside a proprietary platform.
2. **Continue the identical PR flow** on the secondary forge. The contribute steps above are forge-agnostic — fork, branch, edit `values-<qualifier>/`, run the parity check and lint locally, open a merge/pull request.
3. **CI portability.** The parity check and lint commands run locally and in any CI that can execute Gradle and a shell script; the gates in the CI export section are plain scripts, re-wirable on GitLab CI / Woodpecker (Codeberg) with no platform-specific logic.

Contrast with the rejected options: a self-hosted Weblate's single VPS/domain or a SaaS Crowdin's single proprietary domain *is* the single point of failure. The PR-only flow degrades to "use a different Git remote," which is the cheapest possible failover.
