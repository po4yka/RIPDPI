# Change: Federate RIPDPI and deploy task portfolios

Task ID: `CIC-1786277494692459`

## Why

RIPDPI has a strict repository-native portfolio, while ripdpi-vpn-deploy still
uses an older Markdown convention. Relative or prose-only links cannot express
cross-project blockers safely, survive task closure, or produce a combined
ready frontier.

## What Changes

- Make taskctl repository-specific behavior data-driven through a versioned
  project configuration.
- Add qualified task references and remove the ambiguous `linked_task` field.
- Add a versioned JSON export plus strict two-repository federation commands.
- Resolve historical peer tasks from terminal Git history without a central
  service or tracked aggregate board.

## Capabilities

### New Capabilities

- `task-portfolio-federation`: Versioned, fail-closed task graph federation
  between autonomous Git repositories.

### Modified Capabilities

- `integrate-repository-task-management-and-openspec`: Repository task policy
  becomes configurable and supports qualified external relationships.

## Impact

- Affects taskctl schema parsing, graph construction, ready calculation,
  lifecycle history validation, generated board data, CI fixtures, and the
  task records that still carry `linked_task`.
- The deploy repository consumes the same contract in a separate worktree and
  retains its own areas, evidence axes, lifecycle, and Git history.
