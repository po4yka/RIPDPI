# Design

The manual profile accepts an HTTPS URL, 1 to 1,048,576 expected body bytes, and a 64-digit SHA-256. The existing scan control supplies the chosen path. Endpoint URLs with user information, a query, or a fragment are rejected.

Use the existing HTTP response parser, transfer tracker, and deadline. The plaintext reader stops at the first excess body byte. TLS can buffer data at record granularity. Stream the hash over the decoded body. Keep numeric progress and a verdict in the report. Network or route changes revoke the verdict.
