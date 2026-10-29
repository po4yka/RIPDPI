## Context

The spec describes deleted code. Task `RST-1793267609889013`.

## Goals / Non-Goals

- Goal: remove the stale normative spec through the archive sync.
- Non-goal: any code change, or a replacement traffic-shaping design.

## Decisions

- Use `## REMOVED Requirements` for every requirement so the sync removes the whole capability. The spec file is not edited by hand.

## Risks / Rollback

No runtime effect. Rollback is a revert of the archive commit.

## Gates

`./taskctl validate`, `./taskctl generate-board`, `just harness-check`, `./taskctl verify RST-1793267609889013 --archive-ready`.
