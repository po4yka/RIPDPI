---
title: <% tp.system.prompt("Epic title (imperative phrase, e.g. 'Add AmneziaWG outbound support')") %>
type: epic
status: backlog
area: epic
priority: <% tp.system.suggester(["critical","high","medium","low"], ["critical","high","medium","low"]) %>
owner: <% tp.system.prompt("Owner role") %>
parent: null
blocks: []
blocked_by: []
created: <% tp.date.now("YYYY-MM-DD") %>
updated: <% tp.date.now("YYYY-MM-DD") %>
---

- [ ] #task Epic — <% tp.frontmatter["title"] %> #repo/RIPDPI #area/epic #status/backlog 🔼

## Goal

## Why now

## Key decisions

## Scope

## Ship definition

- [ ]

## Child tasks

## Dependencies

## Risks / open questions
