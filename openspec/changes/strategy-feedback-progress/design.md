# Design

Forward TCP data as soon as the existing first-response check releases it. Keep the attempt receipt in the relay and commit it after clean completion. Discard provisional success on freeze, cancellation, and transport failure. A short completed exchange can still succeed.

Keep QUIC Retry distinct from a correlated server Initial after packet and client-offer validation. Forward Retry on the same socket. Keep the correlation context for later traffic. An expiry after Retry alone is inconclusive.
