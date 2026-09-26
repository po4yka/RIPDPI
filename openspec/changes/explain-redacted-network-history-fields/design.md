# Design

Task ID: `UIX-1790434646817990`

The session mapper distinguishes stored `redacted` sentinels from raw legacy values. When sensitive details are shown, it renders a localized not-stored label for redacted scalar fields and the label together with existing localized counts for redacted DNS/local address lists. When hidden, it hides raw legacy carrier/operator identifiers, Wi-Fi network IDs, private DNS hostnames, and ASN. The `unknown` sentinel remains unknown. Connection history has no sensitive-details toggle, so its snapshot mapper always reduces DNS to a count, masks public IP, and shows only known coarse private DNS modes. Both keep transport and network status visible. No network data is fetched to reconstruct omitted fields.
