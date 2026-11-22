# Design

The manual profile takes one HTTPS URL with a DNS host, one literal IP, and a body size from 1 to 32,768 bytes with its SHA-256. All three legs use that IP, the URL port and origin, and the selected route. Each leg uses TLS 1.3, the same provider, HTTP/1.1, and normal certificate validation.

The real ECH leg uses the origin's published HTTPS DNS configuration and requires ECH acceptance. The comparison runs each mode once. The verified transfer reader counts decoded body bytes, stops at the first extra byte, and verifies the digest. Each leg has at most eight seconds within the scan deadline. Stage outcomes and byte counts form the result. A change of network or route makes the comparison inconclusive.
