---
id: DGN-1795704974460888
title: Split selective-availability targets into domestic, foreign datacenter and CDN classes
kind: feature
status: doing
area: diagnostics
priority: medium
owner: Diagnostics engineer
parent: null
blocked_by: []
spec_mode: required
openspec_change: dgn-1795704974460888-split-selective-availability-targets-into-domestic-foreign-datacenter-and-cdn-classes
created: 2026-11-26
updated: 2026-11-26
---

## Goal

Freeze diagnostics can show whether connection_freeze results cluster by destination class and by destination AS, because the selective-availability targets carry a class.

## Context

Reports dated 2026-10-25 to 2026-11-05 describe HTTPS flows that stall after a few kilobytes on three mobile operators toward a foreign host. The same flows worked when the host was domestic. That clustering by destination AS is only inferred so far. The reports are public and single-source, and they are unverified here.

Pitfall: an endpoint that answers HTTP 403 to proxy addresses looks like a freeze if the check reads only the first bytes. The check must verify the exact bytes or the hash of the full response.

Extends DGN-1795361889557414 (transfer volume comparison) and the existing connection_freeze classification. Both stay as they are. This task adds the target class and the grouping.

## Acceptance criteria

- Each selective-availability target carries one class: domestic hosting, foreign datacenter or CDN.
- Results and redacted exports keep the target class and the destination AS number, and no destination address of a user network.
- The connection_freeze classification together with the transfer volume comparison can be grouped by class and by destination AS in one report.
- A response with a wrong status or a wrong byte count is reported as a server response, not as a freeze, because the check compares exact bytes or hash.

Step 3 (grouping): volume targets are user hosts and carry no catalog class. The user may declare a class and a destination AS on the volume comparison input; they travel on both legs and report under the `volume_declared` source, apart from `catalog` rows. They are declared, not observed. Legs without them stay `volume_unattributed`.

## Ownership

Not assigned yet. Before a writer starts, record the owner of the selective-availability target list and of the diagnostics result schema, and serialize any schema-version constant.
