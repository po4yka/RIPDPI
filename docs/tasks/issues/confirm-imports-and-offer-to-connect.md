---
id: UIX-1795615855953573
title: Confirm imports and offer to connect to the new server
kind: feature
status: review
area: ui
priority: medium
owner: import-confirm
parent: EPC-1795615697939690
blocked_by: []
spec_mode: required
openspec_change: uix-1795615855953573-confirm-imports-and-offer-to-connect
created: 2026-11-25
updated: 2026-11-25
status_detail: "Merged; local gates and remote CI passed (run 46009834608 on d9aa00b99f); a device run remains: import by link, QR code, and single-link file and see Added name with Select and Connect; type a link after an empty clipboard; run the subscription Check and Add journeys (the Kaspresso subscription journeys were not run) and see the server count and a failure cause."
---

## Goal

After an import the user knows what was added and can connect to it in one step; a failed import tells them why.

## Context

Found in the 2026-11-25 on-device user-flow audit (Pixel 8 Pro, githubDebug at 66673515dd, clean install). After `Add` on a VLESS link import, the app opens Status with `Disconnected · Direct`, with no confirmation, no selection, and no connect offer. With an empty clipboard, Paste link shows `Clipboard is empty` with one button, `Navigate back`. The Add subscription confirm screen shows `Unnamed subscription` with no server count, and a failed fetch shows `This profile could not be imported` with no reason.

## Acceptance criteria

- A successful link, QR, or single-link file import shows the imported server name and offers Select and Connect; a RIPDPI bundle file import is tracked by UIX-1795663496674824.
- An empty clipboard offers a field to paste or type the link.
- The subscription confirm step shows the fetched name and server count before Add.
- A failed subscription import names the cause (network, HTTP status, format) and uses the word subscription.

## Ownership

One writer in a dedicated worktree owns import flows and dialogs in `app`, the subscription fetch result in `core/data`, strings, and baselines. Locale resource sets are a serialized lane: only this writer changes them while the task is in progress..
