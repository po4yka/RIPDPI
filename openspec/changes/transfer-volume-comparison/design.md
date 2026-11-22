# Design

Use two existing verified download targets with dedicated IDs in one manual profile. Require a common HTTPS origin and one fixed numeric IP. Compare distinct paths with independent sizes and SHA-256 values. Use the existing transfer evidence for bytes and first and last body progress.

Run the short response first. Cap the pair at 30 seconds or the earlier scan deadline. Reserve two thirds of the initial budget for the long response. Cap the short object at 32 KiB and the long object at 1 MiB. Stop on cancellation and preserve any recorded transfer evidence.
