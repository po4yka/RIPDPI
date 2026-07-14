---
paths:
  - "app/**/*.kt"
  - "app/src/main/res/**"
  - "docs/design/**"
  - "DESIGN.md"
---

## RIPDPI Design System (RDS) — visual contract

The persisted spec deck at `docs/design/rds/` is the **read-only visual contract** for every UI surface in the app. It is generated from the Compose token tree under `app/src/main/kotlin/com/poyka/ripdpi/ui/theme/` plus the brand assets in `app/src/main/res/`. The deck includes 146 preview HTMLs (`docs/design/rds/preview/`), three full-screen mockups (`docs/design/rds/mobile-*.html`), the token CSS export (`colors_and_type.css`), the agent contract (`SKILL.md`), and the deck README (`README.md`). The coverage roadmap lives at `docs/design/rds/COVERAGE.md`.

### Rule

1. **Every UI PR that adds or changes a screen, component, motion spec, or surface MUST link the matching `docs/design/rds/preview/<slug>.html`** in its description. If the spec does not exist for the change, that itself is a deviation and requires a one-line justification.

2. **Any visual deviation from the spec card requires a one-line justification in the PR body**, formatted as:
   ```
   RDS deviation: <slug> — <why> (token | content | layout | motion | gesture)
   ```
   No deviation is "implementation detail." Color shift, padding drift, motion-curve substitution, copy change — all need the line.

3. **The deck is read-only from agent edits.** Agents do not modify HTML under `docs/design/rds/preview/` or any other file under `docs/design/rds/`. Regenerating the deck from the codebase is a manual operation invoked by the spec owner; until then, the codebase is the moving variable, not the spec.

4. **`docs/design/rds/COVERAGE.md` is the backlog for the spec→code adoption work.** The audit is heuristic — verify per-row before implementing. Update STATUS in the same PR that closes the gap (`missing`/`partial` → `have`), and link the PR.

5. **Tokens come from `RipDpiTheme` / `RipDpiMotion` / `RipDpiSurface` / `RipDpiState`.** No `Color(0x…)`, `.dp` literals outside `ui/theme/`, `tween(N)` / `spring(…)` with literal constants outside `RipDpiMotion`, or direct `MaterialTheme.colorScheme.*` reads in components. The token-consumption tests under `app/src/test/kotlin/com/poyka/ripdpi/ui/theme/` enforce this floor.

6. **Glance widget theme parity.** Any new color / shape / surface token added to `RipDpiTheme` MUST mirror into `app/src/main/kotlin/com/poyka/ripdpi/widget/theme/` in the **same commit**.

7. **All 7 locales in every commit.** New strings ship into `values/`, `values-ru/`, `values-es/`, `values-de/`, `values-fr/`, `values-fa/`, `values-zh-rCN/strings.xml`. `lint.xml` sets `MissingTranslation severity="error"`.

### What the deck is NOT

- Not a Roborazzi golden source. Preview-render PNGs under `app/build/compose-previews/` are throwaway agent-legibility artifacts (see `compose-preview.md`); they MUST NEVER be copied into `app/src/test/screenshots/` or any path under `tests/golden/` / `src/test/resources/golden/`. The bless discipline (`golden-bless-discipline.md`) is unaffected by this rule.
- Not a Material You / dynamic-color system. Brand identity overrides system accents. Do not introduce `dynamicColorScheme(…)` or `MaterialTheme(useDynamicColor = true)`.
- Not a decorative system. No parallax, scroll-jacking, hero animations, gradients on primary surfaces, or tertiary-color flourishes.
- Not multi-platform. Android phone (and the Glance widget on the same device) only. iOS / Wear OS / KMP work is explicitly out of scope of this contract.

### Audit

Quick PR-time check that a UI change consumes RDS tokens:

```bash
# In the PR diff, no new literal Compose tokens outside ui/theme/.
git diff --cached -- 'app/src/main/kotlin/**/*.kt' \
  | grep -E '^\+' \
  | grep -vE '^\+\+\+|app/src/main/kotlin/com/poyka/ripdpi/ui/theme/' \
  | grep -E 'Color\(0x[0-9a-fA-F]{8}\)|[0-9]+\.dp|tween\([0-9]|spring\(' \
  && echo "BLOCKED: literal token in component layer" && exit 2

# Strings parity across 7 locales (sourced from CLAUDE.md verification command).
for XX in ru es de fr fa zh-rCN; do
  diff=$(comm -23 \
    <(grep -oE 'name="[^"]+"' app/src/main/res/values/strings.xml | sort -u) \
    <(grep -oE 'name="[^"]+"' "app/src/main/res/values-${XX}/strings.xml" | sort -u) | wc -l)
  [ "$diff" -eq 0 ] || { echo "BLOCKED: $XX missing $diff string(s)"; exit 2; }
done
```

The token-consumption tests under `app/src/test/kotlin/com/poyka/ripdpi/ui/theme/` (`RipDpiColorContrastTest`, `RipDpiSurfaceTokensTest`, `RipDpiStateTokensTest`, `RipDpiMotionTest`, `RipDpiLayoutTest`) are the runtime gate; the grep above is the pre-commit gate.

### Where things live

| Artifact | Path | Notes |
| --- | --- | --- |
| Spec deck | `docs/design/rds/` | Read-only visual contract |
| Spec cards | `docs/design/rds/preview/*.html` | One file per component / screen / motion spec |
| Full-screen mockups | `docs/design/rds/mobile-*.html` | Three context-rich mocks |
| Token CSS export | `docs/design/rds/colors_and_type.css` | Cross-reference for non-Kotlin tooling |
| Agent contract | `docs/design/rds/SKILL.md` | How agents are expected to consume the deck |
| Deck README | `docs/design/rds/README.md` | Voice, vocabulary, guardrails |
| Coverage roadmap | `docs/design/rds/COVERAGE.md` | Backlog for spec→code adoption |
| Token source (Compose) | `app/src/main/kotlin/com/poyka/ripdpi/ui/theme/` | The formal contract |
| Token source (Glance) | `app/src/main/kotlin/com/poyka/ripdpi/widget/theme/` | Parallel parity tree |
| Token tests | `app/src/test/kotlin/com/poyka/ripdpi/ui/theme/` | Enforce the floor |

### Cross-references

- `compose-preview.md` — the agent-legibility preview pipeline, governed by the same "never become goldens" rule.
- `golden-bless-discipline.md` — Roborazzi bless protocol; unaffected by the deck but adjacent.
- `vpnservice-protect-invariant.md` — out-of-domain for UI work, but any new screen that opens an outbound socket MUST honor it.
- `android-vpn-lifecycle.md` — applies to any UI surface whose state must survive `SIGKILL` from LMK.
- `network-fingerprint-privacy.md` — applies to any new diagnostic UI that may render network identifiers.
