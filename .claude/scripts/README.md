# `.claude/scripts/`

Shell scripts wired into Claude Code hooks. `.claude/settings.json` is per-developer
(gitignored), so each developer enables hooks by editing their own settings to
reference the scripts here.

## Available scripts

### `rust-postedit-check.sh`
PostToolUse hook. Runs `cargo check -p <crate> --locked --message-format=short` on
the touched crate after any `Edit` / `Write` / `MultiEdit` of a `*.rs` file.
Exits 2 on compile error so Claude Code injects the error into the model's
next-turn context. Timeout 90s. Skips silently for non-Rust files.

### `rust-stop-verify.sh`
Stop hook. Runs `cargo fmt --all --check` then `cargo clippy --workspace
--all-targets --locked -- -D warnings` if any `*.rs` file is dirty in the working
tree. Exits 2 on failure so the model sees gaps before the turn ends. Timeout
60s for fmt-check, 180s for clippy. Skips silently when no Rust changes pending.

## Wiring (add to your `.claude/settings.json`)

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write|MultiEdit",
        "hooks": [
          {
            "type": "command",
            "command": "ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd); bash \"$ROOT/.claude/scripts/rust-postedit-check.sh\""
          }
        ]
      }
    ],
    "Stop": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd); bash \"$ROOT/.claude/scripts/rust-stop-verify.sh\""
          }
        ]
      }
    ]
  }
}
```

If you also have hooks for Kotlin / legal-check / baseline-block, merge the new
`command` entries into the existing `hooks` array for the matching matcher —
don't replace.

## Disabling temporarily

Both scripts honour `RIPDPI_RUST_HOOKS=off`. Export it in your shell before a
session if you want the hooks dormant for a debugging stint:

```bash
RIPDPI_RUST_HOOKS=off claude
```

## Rationale

See `.claude/rules/llm-rust-prompts.md` and `.claude/rules/rust-toolchain-pin.md`
for why these checks run on the AI-generation path specifically. Empirical
finding: PostToolUse blocking with `exit 2` is the single biggest behaviour
shift for Opus 4.7 on Rust — the model sees its own clippy error inline and
fixes on the next turn, without a separate user prompt.
