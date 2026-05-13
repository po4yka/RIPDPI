# CLAUDE.md -- RIPDPI

See [AGENTS.md](AGENTS.md) for the complete project reference: architecture, build commands, native code, CI/CD, coding rules, agent skills, and design system. Global workflow guardrails live in `~/.claude/CLAUDE.md`.

The `PreToolUse` hook blocks edits to `*baseline*` files -- the baseline policy in AGENTS.md § Project Rules is hook-enforced for Claude Code.

The app ships 7 locales (en, ru, es, de, fr, fa, zh-CN). Any new key added to `app/src/main/res/values/strings.xml` or `core/service/src/main/res/values/strings.xml` must land in all six matching `values-XX/strings.xml` files in the same commit. `lint.xml` has `MissingTranslation severity="error"` -- a missing key fails CI. `scripts/check-readme-selectors.sh` enforces selector-block parity across all 7 README files. See AGENTS.md § Project Rules for the full rule and verification commands.
