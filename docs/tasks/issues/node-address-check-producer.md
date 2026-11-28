---
id: DGN-1795868945773730
title: Sign filtered-side node address checks for deploy admission
kind: feature
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: null
blocked_by: []
spec_mode: required
openspec_change: node-address-check-producer
created: 2026-11-28
updated: 2026-11-28
status_detail: "The contract is vendored and pinned (step 1 done; deploy contract-sync passes). The producer is not started: the deploy gate (po4yka/ripdpi-vpn-deploy#OPS-1795704918170150) refuses every RIPDPI bundle path until a signed attestation exists."
---

## Goal

An operator runs a RIPDPI diagnostics address check on a device in the filtered network against a new node listener. The check completes a TCP connect and a TLS handshake and exports one signed attestation in the deploy contract `node-address-check` version 1. The deployer uses the attestation to admit the node address into RIPDPI bundles. Server counterpart: `po4yka/ripdpi-vpn-deploy#OPS-1795704918170150`.

## Context

- The deploy gate is on in every RIPDPI bundle path: `make emit-bundle`, device issuance, device migration and subscription token delivery. It refuses when no trusted, fresh attestation for the exact address exists. Until this producer ships, the operator cannot produce an admissible attestation, so these paths refuse for every node.
- The contract source of truth is `contract/node-address-check.schema.json` with the signed fixture `contract/node-address-check.golden.json` in ripdpi-vpn-deploy. The deploy `contract-sync` job fails on main since PR 777 because `core/data/src/test/resources/contract/` here does not contain these two files.
- Deploy rules that the producer must satisfy: `vantage.side` is always `filtered`; `probes.tls` is `not_run` when `probes.tcp` did not pass; an ICMP result is a hint only and never admits an address; the signature is Ed25519 over the record; the deployer trusts one public key per vantage device and ignores attestations older than seven days.

## Acceptance criteria

- The two contract files are vendored byte-for-byte, `vendored-deploy-contracts.sha256` is updated, and the deploy `contract-sync` job passes.
- A diagnostics action runs a TCP connect and a TLS handshake to an operator-entered address and port and records each probe result by stage, with ICMP recorded only as a hint.
- The app generates one Ed25519 key per device in Android Keystore or an equivalent hardware-backed store, shows the public key for the operator trust file, and never exports the private key.
- The app exports one attestation file that validates against the vendored schema; a test signs a record and checks it against the vendored golden fixture format, and a tampered record fails verification.
- The attestation carries no data about the user network beyond the fields that the schema requires, and the action is not part of automatic diagnostics or field reports.

## Ownership

Diagnostics owns the probe and the export; the vendored contract directory is a serialized lane shared with other contract tasks.
