---
name: mdtask-next
description: Select and start the next unblocked RIPDPI portfolio task.
---

# Select the next task

1. Run `./taskctl ready --json` and rank by critical, high, medium, then low priority.
2. Confirm the task has no unresolved portfolio blockers and review its exact execution file with `./taskctl show <task-id>`.
3. Declare file/module ownership and serialized shared-file lanes before parallel work begins.
4. Run `./taskctl start <task-id> --owner <role>` and `./taskctl generate-board`.
5. Execute through the relevant mdtask or OpenSpec skill in a dedicated worktree.

Do not auto-select blocked work or infer that a parent epic blocks its child.
