---
id: UIX-1795663496674824
title: Offer Select and Connect after importing a RIPDPI bundle file
kind: feature
status: backlog
area: ui
priority: medium
owner: unassigned
parent: EPC-1795615697939690
blocked_by: []
spec_mode: required
openspec_change: uix-1795663496674824-offer-connect-after-bundle-file-import
created: 2026-11-26
updated: 2026-11-26
---

## Goal

After the user imports a RIPDPI bundle file, the app names the imported servers and lets the user select one and connect to it in one step.

## Context

Split from `UIX-1795615855953573`. Link, QR code, and a file that holds a single link already open the profile import confirm screen, which shows `Added: <name>` with Connect and Select. A RIPDPI bundle file goes through the subscription confirm flow instead: it saves a SUBSCRIPTION group and returns to Status with no Select or Connect. Members of a subscription group are selected through the selector group store, which has no single-member action; the unified server list (`UIX-1795615852980919`) now owns server selection on `main`.

## Acceptance criteria

- After a RIPDPI bundle file import succeeds, the result screen lists the imported server names.
- The result screen offers Select and Connect for an imported server; Select makes it the active server through the same selection path as the server list, and Connect also starts the connection when nothing runs.
- A running service follows the new selection without a second start.
- New text uses the term server, and new strings exist in all ten locales.
