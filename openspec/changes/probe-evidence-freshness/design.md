# Design

Before a participating selector probe, capture the allowed report label between two reads of the physical network generation. The report context stays in memory. Under the ledger lock, check consent, its epoch, and the physical generation before adding the attempt. A return to the same network has a different generation. The wire format and stored network labels stay the same.

The direct-path learner compares strictly increasing native timestamps for the same authority, IP set, runtime run, and physical generation. Use a two-minute freshness window for the failure pair. A replay, delayed event, or a new generation cannot extend the previous confirmation. Apply the normal cooldown after a confirmed pair.
