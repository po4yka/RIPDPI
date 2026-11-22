# Design

The form accepts one HTTPS URL and a size of 1 to 1,048,576 bytes. The existing manual scan selects the path. URLs with credentials, a query, or a fragment are rejected. The native request has an optional `uploadBodyBytes` field. Only the dedicated connectivity profile can use it, and it must match the work budget for one target and one run.

Generate fresh random data and send it in one HTTPS POST with Content-Length. Compute SHA-256 locally. The control server returns HTTP 200 and JSON with `bytes` and `sha256` computed from the body it read. The client does not send the digest in the request. Reuse the existing protected or in-path TLS connector, response parser, cancellation, and deadlines. Stop at the first byte beyond the 512-byte receipt limit. Record the verdict, sizes, stage, and timings. Network or route changes revoke the verdict.
