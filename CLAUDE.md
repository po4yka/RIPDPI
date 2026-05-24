# CLAUDE.md -- RIPDPI

See [AGENTS.md](AGENTS.md) for the complete project reference: architecture, build commands, native code, CI/CD, coding rules, agent skills, and design system. Global workflow guardrails live in `~/.claude/CLAUDE.md`.

For architecture, start at [docs/architecture/ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md) -- the canonical "start here" map. It links the deeper references: [NATIVE_RUST.md](docs/architecture/NATIVE_RUST.md) (crate taxonomy), [JNI_CONTRACT.md](docs/architecture/JNI_CONTRACT.md) (Kotlin/Rust boundary), [CONFIG_CONTRACTS.md](docs/architecture/CONFIG_CONTRACTS.md) (settings/protobuf/config compatibility), and [FEATURE_EXTENSION_GUIDE.md](docs/architecture/FEATURE_EXTENSION_GUIDE.md) (adding features safely).

The `PreToolUse` hook blocks edits to `*baseline*` files -- the baseline policy in AGENTS.md § Project Rules is hook-enforced for Claude Code.

Long-form cross-tool rules live in `.claude/rules/` and apply to both Claude Code and Codex. Read them when their topic comes up: `vpnservice-protect-invariant.md` for outbound socket creation, `llm-rust-prompts.md` for AI-generated Rust review gates, `android-vpn-lifecycle.md` for LMK/Doze/Foreground-Service state persistence, `network-fingerprint-privacy.md` for per-network policy scope keys, `golden-bless-discipline.md` before any `RIPDPI_BLESS_GOLDENS=1`, `rust-toolchain-pin.md` for `--locked` cargo discipline, and `ansible-molecule.md` for molecule scenario authoring against the sibling `ripdpi-vpn-deploy` repo. See AGENTS.md § Project Rules for the routing table.

The app ships 7 locales (en, ru, es, de, fr, fa, zh-CN). Any new key added to `app/src/main/res/values/strings.xml` or `core/service/src/main/res/values/strings.xml` must land in all six matching `values-XX/strings.xml` files in the same commit. `lint.xml` has `MissingTranslation severity="error"` -- a missing key fails CI. `scripts/check-readme-selectors.sh` enforces selector-block parity across all 7 README files. See AGENTS.md § Project Rules for the full rule and verification commands.
