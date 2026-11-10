# Apps Script HTTP relay

The `google_apps_script` relay uses an in-process native SOCKS listener. It is available in Proxy mode. VPN mode is rejected because this carrier does not support socket protection.

## Trust and setup

Use a deployment that you own and inspect its source. The operator and Google can read the requested URL, HTTP headers, cookies, credentials and body. HTTPS ends at the local relay CA and starts again at the script's fetch service. Front certificate verification does not prove that the script verifies its origin certificates.

Start the relay once to create its CA in app-private persistent storage. Export the public CA from the Apps Script profile editor. Import it only into a client that supports a user CA. Review its fingerprint before trust. The export must contain a certificate, never the private signing key. Remove client trust when you stop using this mode. Certificate pinning remains enforced; clients that reject user CAs cannot use this MITM path.

The local SOCKS listener uses credentials for each service session. A client must not fall back to NO-AUTH when credentials are configured.

## Peer contract

These field names define the existing application envelope. They are not a Google API. A compatible deployment MUST implement this contract and check the shared secret before any fetch.

Request: JSON object with `k` (secret), `m` (method), `u` (absolute HTTP or HTTPS URL), optional `h` (request header map), optional `b` (standard base64 body), optional `ct` (content type), and `r` (origin redirect policy).

- Accept only the configured key; reject missing or wrong keys before URL resolution or fetch. Keep the key in private script properties. Do not log requests or secret values.
- Validate the target URL and restrict it to intended destinations. Reject credentials in URLs and unsupported schemes. Do not use a public deployment as an unrestricted fetch service.
- Use verified origin TLS. Do not set `validateHttpsCertificates` to false.
- The native client sends `r=false`. Return origin redirects to the client with their status and Location. Do not follow them under the original client URL.
- Preserve binary bytes. Decode request base64 before fetch. Return response bytes in standard base64 without text conversion. Preserve Content-Encoding if those bytes remain encoded. The outer envelope's gzip encoding is independent.
- Use `muteHttpExceptions=true` or equivalent so origin 4xx and 5xx remain origin responses. Return all response header values, using arrays for repeated values such as Set-Cookie.
- Document the supported method set. Google UrlFetch documents GET, POST, PUT, PATCH and DELETE. Parser recognition does not establish support for HEAD, TRACE or a nested CONNECT. OPTIONS is rejected locally; the relay must not invent CORS permission.

Successful response: `{"s":200,"h":{"Content-Type":"application/octet-stream"},"b":"YWJj"}`. `s` is a final HTTP status. Error response: `{"e":"bounded non-sensitive error"}`. Do not include keys, target credentials or response payloads in error text. An unrelated JSON document is not a relay response.

There is no WebSocket, HTTP/2, HTTP/3, arbitrary TCP or UDP tunnel in this envelope. Unsupported traffic is rejected. Explicit direct-host TLS rewriting is a separate user-selected path that bypasses Apps Script.

## Carrier assumptions and acceptance

The client connects to the configured Google IP on TCP/443, verifies the certificate against its front SNI, and uses HTTP/1.1 with Host script.google.com. This requires the selected Google edge to accept the SNI/Host combination. Google service support for a particular front is not inferred from a successful TLS handshake.

ContentService's redirect to script.googleusercontent.com is a carrier redirect, separate from an origin HTTP redirect. Test it with the chosen deployment version. Record the source revision, deployment ID, Google IP and front SNI privately. No public peer source or deployed revision is implied by a saved profile.

Local tests prove framing and state transitions. External acceptance needs explicit permission and an owner-controlled deployment: correct and wrong key, binary response, repeated cookies, origin redirects, origin TLS failure, non-2xx, quotas and timeout. Inspect the origin's received bytes and side effects. Physical-device acceptance must also verify app-private CA creation, trust/export, stop and restart. Do not use real user cookies or credentials for these tests.
