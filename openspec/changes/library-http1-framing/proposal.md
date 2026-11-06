# Change: Replace manual HTTP/1 framing with maintained parsers

Task ID: `RST-1793903447995667`

## Why

Independent HTTP parsers disagree on interim replies, framing, and end of body. A relay can return a truncated successful response after a short pause. Standard protocol decoding does not need a separate implementation in each transport.

## What Changes

- Use library HTTP parsing and framing while retaining transport and diagnostic policy.
- Reject invalid response framing and incomplete bodies. Process informational responses and trailers.
- Let the library normalize ingress framing and close ambiguous connections after one exchange.
- Accept valid upgrade status text and preserve following tunnel bytes.
- BREAKING: malformed HTTP previously accepted by permissive parsers now fails. No JNI, configuration, or storage schema changes are required.

## Capabilities

### New Capabilities

- `library-http1-framing`: bounded HTTP exchanges with consistent library framing and explicit diagnostic evidence.

### Modified Capabilities

- None.

## Impact

- Rust Apps Script, DNS resolver, diagnostics HTTP, and WebTunnel crates and direct response consumers.
- Existing locked Hyper/http/body utilities and httparse; no library version upgrade.
