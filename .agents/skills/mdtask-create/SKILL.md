---
name: mdtask-create
description: Create a RIPDPI portfolio task and its mdtask or OpenSpec execution scaffold.
---

# Create tracked work

1. Search with `./taskctl list --json` to avoid duplicates.
2. Choose `spec_mode: required` for features, behavioral epics, user-visible behavior, schemas, contracts, security, network/protocol, lifecycle, or cross-module work.
3. Use `./taskctl new` with title, kind, area, priority, owner, and explicit spec mode/reason.
4. For a required change, complete proposal, delta specs, design, mdtask `tasks.md`, and verification through `$openspec-propose` before committing.
5. Regenerate the board with `./taskctl generate-board` and run `./taskctl validate`.

Do not invent IDs, create execution files outside the canonical directories, or use upstream mdtask ID assignment.
