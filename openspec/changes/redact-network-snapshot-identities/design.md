# Design

Task ID: `DGN-1790433240059940`

`AndroidNetworkMetadataProvider` is the only production binding of `NetworkMetadataProvider`. The scan preparation, scan finalization, and runtime artifact paths all persist snapshots returned by it. Apply a pure privacy projection before the provider returns, so these paths receive safe values without changing their call sites. Preserve count and presence as coarse values. The active DNS target planner uses a separate `NativeNetworkSnapshot`, so probe targets are unaffected.

Keep raw public IP and ASN in this change because runtime usage-session and telemetry flows consume them. The product decision on external public-IP lookup is tracked separately. Existing stored rows remain a privacy residual and need a data migration; export redaction does not change stored history.
