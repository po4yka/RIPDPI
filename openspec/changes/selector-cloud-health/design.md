# Design

Read bundle health at the start of each pass. The active member follows the existing applied route and automatic eligibility rules. Probe ordinary reserves in their existing order. Down reserves come from eligible members only.

A payload pass still has two slots and reserves the existing shared budget. When the active transfer and all ordinary reserves have failed on the current scope, give the reserve slot to one down member. Successful transfers can confirm that same member on later admitted passes. A failed down probe ends the sweep and starts the existing outage backoff. Use the normal transfer verification, network checks, and switch thresholds.

The reachability pass can check one down member after all ordinary attempts fail. It skips the extra probe after an undecided result. Manual selections and members excluded from automatic selection keep their existing rules.
