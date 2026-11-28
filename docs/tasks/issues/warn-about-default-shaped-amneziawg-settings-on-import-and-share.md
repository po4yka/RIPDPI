---
id: UIX-1795704986857984
title: Warn about default-shaped AmneziaWG settings on import and share
kind: feature
status: backlog
area: ui
priority: medium
owner: AmneziaWG editor engineer
parent: null
blocked_by: [UIX-1795661310559744, TRN-1792776081374051]
spec_mode: required
openspec_change: uix-1795704986857984-warn-about-default-shaped-amneziawg-settings-on-import-and-share
created: 2026-11-26
updated: 2026-11-26
status_detail: Waits for UIX-1795661310559744 (AmneziaWG .conf share) and TRN-1792776081374051 (AWG 3.1 header ranges and signature packets); both are in review and need their device runs. The server-side counterpart is ripdpi-vpn-deploy ANS-1795705936932681.
---

## Goal

Importing or sharing an AmneziaWG config warns the user when its handshake looks like plain WireGuard, when its header values are defaults, or when its I1 packet equals a publicly known default.

## Context

A public write-up dated 2026-11-14 reports many servers that carry the same default I1 packet, which imitates a DNS response that no real resolver would return. Byte-identical packets across servers can become a one-rule signature. This is a risk assessment, not a measured block. The source is single and unverified here.

Server counterpart: `po4yka/ripdpi-vpn-deploy#ANS-1795705936932681` (server-side refusal of repeated or default signature packets).

Related work: UIX-1795661310559744 (share AmneziaWG servers) adds the exporter that this lint hooks into. TRN-1792776081374051 (header ranges and signature packets) defines the fields. DAT-1791479983861691 validates subscription cohort metadata and does not check obfuscation values.

Design level: the lint compares against a short bundled list of digests of known default packets. The list ships with the app and holds no packet bytes in docs or in logs.

## Acceptance criteria

- Import warns when the parsed handshake shape is identical to plain WireGuard because no obfuscation field is set.
- Import and share warn when the header values equal the documented defaults.
- Import and share warn when the I1 packet matches the digest of a publicly known default packet.
- Warnings are advisory: the user can continue, and the warning text and logs hold no packet bytes or key material.

## Ownership

Not assigned yet. Before a writer starts, record the owner of the AmneziaWG parser and exporter in core data and of the import and share UI, and serialize all locale resource sets.
