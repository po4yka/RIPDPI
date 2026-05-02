# RIPDPI Paperclip — Org as Code

Canonical source for the RIPDPI Paperclip agent organization. Every role
definition (`AGENTS.md` instruction bundle plus its sanitized `agent.json`
metadata) lives in this tree. The running Paperclip instance is the runtime;
this tree is the source of truth.

## Layout

```
paperclip/
├── README.md
├── manifest.json                       # generated org index
├── agents/<urlKey>/AGENTS.md           # managed instruction bundle entry file
├── agents/<urlKey>/agent.json          # sanitized adapter/runtime/budget config
├── scripts/export.py                   # pull running Paperclip → tree
├── scripts/apply.py                    # push tree → running Paperclip
├── scripts/sync-check.py               # diff tree vs running Paperclip
└── launchd/com.po4yka.ripdpi.paperclip-sync.plist  # daily local drift check
```

## Conventions

- `agent.json.reportsTo` is the **target agent's `name`**, not its UUID. `apply.py`
  resolves the UUID against the live company at upload time.
- `adapterConfig` is reproduced verbatim *except* the per-machine fields
  Paperclip materializes at create time (`instructionsFilePath`,
  `instructionsRootPath`). Those are recomputed on apply.
- Bundle mode is always `managed` and entry file is always `AGENTS.md`.
- Legacy `promptTemplate` and `bootstrapPromptTemplate` are unused project-wide.

## Workflows

### Edit a role

1. Edit `agents/<urlKey>/AGENTS.md` and/or `agents/<urlKey>/agent.json`.
2. `python3 paperclip/scripts/apply.py --dry-run`  (verify intended changes).
3. `python3 paperclip/scripts/apply.py`            (push to live Paperclip).
4. `python3 paperclip/scripts/sync-check.py`       (confirm no drift).
5. Commit the diff.

### Add a new role

1. Pick a `urlKey` (lowercase-kebab); create `agents/<urlKey>/`.
2. Add `AGENTS.md` (mirror existing bundles for structure; ~15-25 KB) and
   `agent.json` (mirror the closest existing peer for adapter shape).
3. Apply, sync-check, commit (same as above).

### Pull live changes back into the tree

If someone edits an agent in the Paperclip UI, run:

```sh
python3 paperclip/scripts/export.py
git diff paperclip/
```

Decide whether to keep the change (commit) or revert it (re-apply the tree).

## Drift check (regular sync)

`scripts/sync-check.py` prints any difference between the tree and the live
Paperclip instance. Exit code:

- `0` — clean
- `1` — drift (metadata mismatch, bundle mismatch, untracked or missing agent)
- `2` — Paperclip API unreachable (treated as soft failure by the launchd job)

### Local schedule (macOS launchd)

Install once:

```sh
cp paperclip/launchd/com.po4yka.ripdpi.paperclip-sync.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/com.po4yka.ripdpi.paperclip-sync.plist
```

It runs daily at 09:00 local time. Output is appended to
`/tmp/ripdpi-paperclip-sync.log`.

To uninstall:

```sh
launchctl unload ~/Library/LaunchAgents/com.po4yka.ripdpi.paperclip-sync.plist
rm ~/Library/LaunchAgents/com.po4yka.ripdpi.paperclip-sync.plist
```

### Cron alternative

```cron
0 9 * * *  cd /Users/po4yka/GitRep/RIPDPI && python3 paperclip/scripts/sync-check.py >> /tmp/ripdpi-paperclip-sync.log 2>&1
```

### Manual one-shot

```sh
python3 paperclip/scripts/sync-check.py
```

## Environment overrides

All scripts honor:

- `PAPERCLIP_URL`        (default `http://127.0.0.1:3100`)
- `PAPERCLIP_COMPANY_ID` (default the RIPDPI company UUID)
- `PAPERCLIP_HOME`       (default `~/.paperclip/instances/default`)

## Org structure (current)

| Role | Reports to | Adapter | Model | Budget |
|---|---|---|---|---|
| CEO | – | codex_local | gpt-5.5 | – |
| CTO | CEO | codex_local | gpt-5.5 | $150 |
| Product Manager | CEO | codex_local | gpt-5.5 | $100 |
| QA Lead | CEO | claude_local | claude-opus-4-7 | $150 |
| Principal Android/Rust Architect | CTO | claude_local | claude-opus-4-7 | $200 |
| Security AppSec | CTO | claude_local | claude-opus-4-7 | $150 |
| Senior Android Engineer | CTO | claude_local | claude-sonnet-4-6 | $300 |
| Senior Rust Native Engineer | CTO | claude_local | claude-sonnet-4-6 | $300 |
| Senior Network Protocol Engineer | CTO | claude_local | claude-sonnet-4-6 | $300 |
| Senior Build Gradle CI Engineer | CTO | claude_local | claude-sonnet-4-6 | $250 |
| Documentation UX Engineer | Product Manager | claude_local | claude-sonnet-4-6 | $150 |
| Test Automation Engineer | QA Lead | claude_local | claude-sonnet-4-6 | $250 |

Total monthly envelope: $2,300 (CEO unbudgeted).
