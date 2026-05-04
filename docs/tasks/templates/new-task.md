---
title: <% tp.system.prompt("Task title (imperative verb phrase)") %>
type: task
status: backlog
area: <% tp.system.suggester(["engine","rust-native","diagnostics","transport","outbound","dns","routing","vpn","proxy","relay","android","ui","data","service","testing","ci"], ["engine","rust-native","diagnostics","transport","outbound","dns","routing","vpn","proxy","relay","android","ui","data","service","testing","ci"]) %>
priority: <% tp.system.suggester(["critical","high","medium","low"], ["critical","high","medium","low"]) %>
owner: <% tp.system.prompt("Owner role") %>
parent: <% tp.system.prompt("Parent epic slug (or leave blank)") %>
blocks: []
blocked_by: []
created: <% tp.date.now("YYYY-MM-DD") %>
updated: <% tp.date.now("YYYY-MM-DD") %>
---

- [ ] #task <% tp.frontmatter["title"] %> #repo/RIPDPI #area/<% tp.frontmatter["area"] %> #status/backlog 🔼

## Objective

## Context

## Acceptance criteria

- [ ]

## Definition of done
