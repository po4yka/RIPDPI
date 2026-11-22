# Design

Carry the selected comparison port through DNS AliasMode lookups. Select only compatible bindings for the pinned endpoint. Keep the DNS query and TLS certificate authority tied to the selected origin.

Record the reason as a stable per-leg outcome in the existing probe details. Use the typed TLS rejection after certificate verification. Keep DNS failure, invalid configuration, and missing configuration distinct. Reuse the saved details in history and the redacted archive. The three-leg scheduler keeps one attempt per mode and does not follow ECH retry configurations in a comparison.
