# TLS record prelude

`tlsrec` changes TLS record boundaries. It does not set TCP segment boundaries.
The native prelude uses the packet parser's record spans to collect the first
plaintext ClientHello. It keeps existing input record boundaries, adds selected
boundaries, and leaves reassembled payload bytes unchanged.

A ClientHello can cross handshake record headers, session and cipher vectors,
SNI names, and opaque ECH extension bytes. Semantic markers are resolved on a
contiguous synthetic record. ECH activation uses the visible extension layout,
including when the SNI crosses an input record boundary. This path does not
create or decrypt ECH.

For a real stream, every record used by the prelude must have its complete
declared payload. The last record that contains ClientHello bytes is kept in
full, including any coalesced payload after that message. Later wire records
are passed through unchanged. A truncated contributing record leaves the input
unchanged and does not count as an applied operation.

The parser still accepts some incomplete single-record templates. This existing
behavior is separate from strict validation of TLS handshake and extension
lengths. Standalone fake templates also retain their explicit truncated-record
path. Inputs without the required semantic metadata, inputs above the synthetic
record's 16-bit length limit, and unsupported record layouts remain unchanged.

The production path is synchronous and uses an ordinary TCP stream. No root
operation, external endpoint, persisted field, or new wire schema is required.
Local tests cover captured TLS 1.2, TLS 1.3, and ECH-bearing ClientHello bytes;
those tests do not establish Android or external ECH compatibility.
