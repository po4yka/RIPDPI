# Ponytail (vendored)

Verbatim copies of four skills from [Ponytail](https://github.com/DietrichGebert/ponytail) by Dietrich Gebert, MIT License (see `LICENSE` in this directory).

- Upstream commit: `09c434ab73ca43761c5d8b0c5d341926c2fba710` (2026-10-18, package version 5.3.2).
- Skills: `ponytail`, `ponytail-review`, `ponytail-audit`, `ponytail-debt`. They are exposed to Codex through the symlinks in `.agents/skills/`, and to Claude Code and GitHub Copilot through the `.claude/skills/` and `.github/skills/` mirrors.
- Not vendored: `ponytail-help` and `ponytail-gain` describe plugin-only features (statusline band, mode flag files, benchmark files). The lifecycle hooks are not used, because they write mode state and a statusline entry under the user's home directory.

`AGENTS.md` activates the `ponytail` skill for coding tasks in both runtimes. Levels (`lite`, `full`, `ultra`) are skill arguments for the current session; the default is `full`. Where a skill differs from the project test, verification, or review rules, `AGENTS.md` wins; put such overrides there, not in these files.

Do not edit these files. To update, copy the same four `skills/<name>/` directories and `LICENSE` from a newer upstream commit, then change the commit line above.
