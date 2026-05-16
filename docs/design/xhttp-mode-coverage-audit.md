# xHTTP Mode Coverage Audit (audit H3)

**Date:** 2026-05-16
**Status:** Research only — no Rust changes yet. Implementation deferred.
**Scope:** xray-core `transport/internet/splithttp` (Go, HEAD via GitHub API) vs.
`ripdpi-xhttp` (Rust, `native/rust/crates/ripdpi-xhttp`).

The audit finding H3 flagged that our crate implements only one of xray-core's
four xHTTP transport modes. This document records the per-mode wire shape from
upstream, the gap relative to our crate, and the recommended implementation
order. Cite xray-core line ranges relative to HEAD at the time of the audit
(file names + function names are stable).

---

## Default mode resolution

`dialer.go` `Dial`, mode-resolution block:

```go
mode := transportConfiguration.Mode
if mode == "" || mode == "auto" {
    mode = "packet-up"
    if realityConfig != nil {
        mode = "stream-one"
        if transportConfiguration.DownloadSettings != nil {
            mode = "stream-up"
        }
    }
}
```

| Transport context | Effective default |
| --- | --- |
| Plain TLS / H2 / H3 | `packet-up` |
| REALITY, no `downloadSettings` | `stream-one` |
| REALITY + `downloadSettings` set | `stream-up` |

Our crate **never reads** `Config.mode`; `XhttpMode` in `config.rs` is a
TLS-vs-Reality enum, not a protocol-mode enum. `open_stream_from_mode` in
`relay.rs:90-176` always executes the stream-up wire shape.

---

## Mode 1 — `stream-up`

**Upload.** One streaming POST whose body is an unbounded append-only byte
stream. The POST is fired once per session and keeps the H2 stream open for the
lifetime of the session. `Content-Type: application/grpc` unless
`noGRPCHeader` is set.

**Download.** Separate GET on `requestURL2` (which may be a different
host/path when `downloadSettings` is set) with `body=nil` → `method="GET"`.

**URL templates (default placement = `path`):**
- Upload POST: `<path>/<session-uuid>`
- Download GET: `<path2>/<session-uuid>`

**Seq encoding:** none.

**Governing config fields:** `scStreamUpServerSecs`, `noGRPCHeader`,
`noSSEHeader`, plus the common padding fields.

**Server compatibility:** server accepts the `seqStr == ""` POST branch.
Server enforces mode allowlists: any non-`""/auto/stream-up` mode rejects
with HTTP 400.

**Status in our crate:** Implemented (only mode). Gaps within stream-up:
- No split-endpoint (`downloadSettings`) support.
- No configurable placement — path-only is hardcoded (`relay.rs:202-209`).
- No `scStreamUpServerSecs` keepalive handling.

---

## Mode 2 — `packet-up`

**Upload.** One short POST per chunk. Each POST carries a bounded payload
(`scMaxEachPostBytes`, default 1 MB). Successive POSTs are pipelined at rate
`scMinPostsIntervalMs` (default 30 ms). Implemented via
`client.go:PostPacket`.

**Upload URL template (default placement = `path`):**

```
<path>/<session-uuid>/<seq>
```

`<seq>` is a base-10 integer (`strconv.FormatInt(seq, 10)`), starting at 0,
incrementing by 1 per chunk.

**Payload placement** (governed by `uplinkDataPlacement`, default `body`):
- `body` — raw bytes as request body.
- `header` — base64url, chunked across `<UplinkDataKey>-0`, `-1`, … headers,
  chunk size from `uplinkChunkSize` (default 3 000–4 000 bytes).
- `cookie` — base64url, chunked across `<UplinkDataKey>_0`, `_1`, … cookies,
  chunk size 2 048–3 072 bytes.
- `auto` — server accepts any mix.

**Download.** Separate GET stream, same shape as stream-up.

**Server-side reassembly.** `upload_queue.go` keeps a min-heap reordering by
`Packet.Seq`. Reassembly buffer bounded by `scMaxBufferedPosts` (default 30).

**Governing config fields:** `scMaxEachPostBytes`, `scMinPostsIntervalMs`,
`scMaxBufferedPosts`, `uplinkDataPlacement`, `uplinkDataKey`,
`uplinkChunkSize`, `seqPlacement`, `seqKey`, `sessionPlacement`,
`sessionKey`.

**Status in our crate:** Missing. No `PostPacket` equivalent, no seq counter,
no per-chunk POST loop, no uploadQueue-compatible reordering on receive.

---

## Mode 3 — `stream-one`

**Fully bidirectional, single HTTP/2 request.** No separate download GET.
Upload pipe is the request body; response body is the download stream.

`dialer.go` stream-one branch:

```go
if mode == "stream-one" {
    requestURL.Path = transportConfiguration.GetNormalizedPath()
    conn.reader, conn.remoteAddr, conn.localAddr, err =
        httpClient.OpenStream(ctx, requestURL.String(), sessionId, reader, false)
    return stat.Connection(&conn), nil
}
```

Stream-one sets `sessionId = ""` (`dialer.go`: `if mode != "stream-one" { sessionId = uuid.New().String() }`), so the URL is just `<path>` with no session
segment.

**Seq encoding:** none.

**Governing config fields:** `noGRPCHeader`, `noSSEHeader`. No chunk-size or
interval fields apply.

**Server compatibility:** server requires `sessionId == ""` to enter the
stream-one path. Server rejects with HTTP 400 (`"stream-one mode is not
allowed"`) when locked to packet-up.

**Status in our crate:** Missing. Our crate always generates a session-id
(`random_session_id()` in `relay.rs:218`) and always sends GET + POST.

---

## Mode 4 — `stream-down` (split endpoint)

`stream-down` is the *download role* of a stream-up connection with a separate
`downloadSettings` endpoint — not a client-selectable `mode` string. It
appears in upstream logging as `"XHTTP is downloading from … mode
stream-down"`.

**Upload URL:** `<path>/<session-uuid>` (stream-up POST on primary endpoint).
**Download URL:** `<path2>/<session-uuid>` (GET on `downloadSettings`
endpoint — may be a CDN).

**Seq encoding:** none.

**Governing config fields:** `downloadSettings` (StreamConfig proto field 13)
— a full nested stream config for the download endpoint, with its own
`Config`, TLS, host, path.

**Status in our crate:** Missing. `XhttpTlsConfig` / `XhttpRealityConfig`
have no `download_settings` field. Stream-up uses the same H2 connection for
both GET and POST.

---

## Placement system (orthogonal to mode)

Upstream allows three metadata fields — session-id, seq, uplink data — to be
placed in `path` / `query` / `header` / `cookie` / `body`. Implementation:
`FillStreamRequest`, `FillPacketRequest`, `ApplyMetaToRequest` in `config.go`.

Our crate hardcodes path-only placement (`relay.rs:202-209`); `XhttpTlsConfig`
and `XhttpRealityConfig` carry none of the placement key/value fields.

---

## Gap analysis

| Mode | Status | Est. LOC | Config fields already present |
| --- | --- | --- | --- |
| stream-up | Present (partial) | ~60 (split endpoint + placement) | `path`, `host` |
| packet-up | Missing | 250–350 | none directly |
| stream-one | Missing | ~40 | none — but H2 already supports full-duplex |
| stream-down (split endpoint) | Missing | ~80 | none — needs `download_settings` |

---

## Recommendation

**Implement `stream-one` first.**

1. **Lowest cost (~40 LOC).** Two behavioral deltas from stream-up: skip the
   session-id, and pass the upload pipe as the POST body while reading the
   response body as the download stream — a single `send_request` instead of
   GET + POST. Hyper HTTP/2 supports full-duplex streams natively; the
   existing `ChannelBody` infrastructure is sufficient.

2. **Upstream default for REALITY.** Our primary deployment is REALITY
   (`XhttpMode::Reality`). Per `dialer.go`, the upstream REALITY default is
   `stream-one` (falling back to `stream-up` only when `downloadSettings` is
   present). A client advertising stream-up against a server defaulting to
   stream-one is a hard conformance failure: the server enters the stream-one
   path (no session-id, no upload queue) while we send GET + POST with a
   session-id, which the server rejects with HTTP 400 (`"stream-one mode is
   not allowed"`) when locked, or corrupts the session table when not.

3. **Unblocks the mode-dispatch skeleton.** Once a `XhttpMode` (in the
   protocol-mode sense, not the TLS/Reality sense) enum routes the connection
   path, packet-up and stream-down split-endpoint slot in without further
   refactor. Recommended implementation order:
   1. `stream-one`
   2. `packet-up` (largest delta — needs seq counter, interval timer, and
      receive-side reordering)
   3. `stream-down` split endpoint (adds dual `PooledConnection` management
      and a `download_config` field)

---

## Cross-references

- Audit finding H3 (xHTTP modes incomplete) — this document.
- Companion audit finding C3 (Vision flow unconditional) — closed by commit
  `a6f2cab2 feat(vless): per-profile flow selection (audit C3)`.
- Upstream source files (xray-core HEAD):
  - `transport/internet/splithttp/dialer.go`
  - `transport/internet/splithttp/client.go`
  - `transport/internet/splithttp/hub.go`
  - `transport/internet/splithttp/upload_queue.go`
  - `transport/internet/splithttp/config.go`
  - `transport/internet/splithttp/config.proto`
