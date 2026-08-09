---
name: mdtask
description: Inspect and update RIPDPI execution steps through the repository-pinned mdtask wrapper.
---

# RIPDPI mdtask workflow

Portfolio state lives in `docs/tasks/issues/`; mdtask owns only execution checkboxes in `docs/tasks/work/` and active OpenSpec `tasks.md` files.

- Discover portfolio work with `./taskctl list`, `./taskctl ready`, `./taskctl show <task-id>`, and `./taskctl graph`.
- Inspect or update execution steps with `./taskctl steps <task-id> list|view|done|set|validate ...`.
- Never run `mdtask archive`, `move`, `ids`, or `install-skills`; taskctl owns IDs, paths, archive policy, and generated skills.
- Mark a step complete only after the behavior and named check were observed. Completed steps advance portfolio work at most to `review`.
- Run `./taskctl validate` before handoff.
