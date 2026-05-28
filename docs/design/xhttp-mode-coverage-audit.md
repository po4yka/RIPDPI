# xHTTP Mode Coverage Audit (audit H3)

**Date:** 2026-05-16 **Status:** Partially implemented — `stream-one` has landed; `packet-up` and split-endpoint `stream-down` remain deferred. **Updated:** 2026-05-28. **Scope:** xray-core `transport/internet/splithttp` (Go, HEAD via GitHub API) vs. `ripdpi-xhttp` (Rust, `native/rust/crates/ripdpi-xhttp`).

The original audit finding H3 flagged one-mode xHTTP support in this crate. The crate now implements `stream-up` and `stream-one`; this document records the remaining gaps relative to upstream and the recommended implementation order. Cite xray-core line ranges relative to HEAD at the time of the audit (file names + function names are stable).

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

Current crate state: `config.rs` defines `XhttpProtocolMode`, accepts `stream-up`, `stream-one`, `""`, and `auto`, and rejects `packet-up` / `stream-down` as unsupported. `relay.rs` routes `open_stream_from_mode` to either `open_stream_up` or `open_stream_one`.

---

## Mode 1 — `stream-up`

**Upload.** One streaming POST whose body is an unbounded append-only byte stream. The POST is fired once per session and keeps the H2 stream open for the lifetime of the session. `Content-Type: application/grpc` unless `noGRPCHeader` is set.

**Download.** Separate GET on `requestURL2` (which may be a different host/path when `downloadSettings` is set) with `body=nil` → `method="GET"`.

**URL templates (default placement = `path`):**
- Upload POST: `<path>/<session-uuid>`
- Download GET: `<path2>/<session-uuid>`

**Seq encoding:** none.

**Governing config fields:** `scStreamUpServerSecs`, `noGRPCHeader`, `noSSEHeader`, plus the common padding fields.

**Server compatibility:** server accepts the `seqStr == ""` POST branch. Server enforces mode allowlists: any non-`""/auto/stream-up` mode rejects with HTTP 400.

**Status in our crate:** Implemented. Gaps within stream-up:
- No split-endpoint (`downloadSettings`) support.
- No configurable placement — path-only is hardcoded (`relay.rs:202-209`).
- No `scStreamUpServerSecs` keepalive handling.

---

## Mode 2 — `packet-up`

**Upload.** One short POST per chunk. Each POST carries a bounded payload (`scMaxEachPostBytes`, default 1 MB). Successive POSTs are pipelined at rate `scMinPostsIntervalMs` (default 30 ms). Implemented via `client.go:PostPacket`.

**Upload URL template (default placement = `path`):**

```
<path>/<session-uuid>/<seq>
```

`<seq>` is a base-10 integer (`strconv.FormatInt(seq, 10)`), starting at 0, incrementing by 1 per chunk.

**Payload placement** (governed by `uplinkDataPlacement`, default `body`):
- `body` — raw bytes as request body.
- `header` — base64url, chunked across `<UplinkDataKey>-0`, `-1`, … headers, chunk size from `uplinkChunkSize` (default 3 000–4 000 bytes).
- `cookie` — base64url, chunked across `<UplinkDataKey>_0`, `_1`, … cookies, chunk size 2 048–3 072 bytes.
- `auto` — server accepts any mix.

**Download.** Separate GET stream, same shape as stream-up.

**Server-side reassembly.** `upload_queue.go` keeps a min-heap reordering by `Packet.Seq`. Reassembly buffer bounded by `scMaxBufferedPosts` (default 30).

**Governing config fields:** `scMaxEachPostBytes`, `scMinPostsIntervalMs`, `scMaxBufferedPosts`, `uplinkDataPlacement`, `uplinkDataKey`, `uplinkChunkSize`, `seqPlacement`, `seqKey`, `sessionPlacement`, `sessionKey`.

**Status in our crate:** Missing. No `PostPacket` equivalent, no seq counter, no per-chunk POST loop, no uploadQueue-compatible reordering on receive.

---

## Mode 3 — `stream-one`

**Fully bidirectional, single HTTP/2 request.** No separate download GET. Upload pipe is the request body; response body is the download stream.

`dialer.go` stream-one branch:

```go
if mode == "stream-one" {
    requestURL.Path = transportConfiguration.GetNormalizedPath()
    conn.reader, conn.remoteAddr, conn.localAddr, err =
        httpClient.OpenStream(ctx, requestURL.String(), sessionId, reader, false)
    return stat.Connection(&conn), nil
}
```

Stream-one sets `sessionId = ""` (`dialer.go`: `if mode != "stream-one" { sessionId = uuid.New().String() }`), so the URL is just `<path>` with no session segment.

**Seq encoding:** none.

**Governing config fields:** `noGRPCHeader`, `noSSEHeader`. No chunk-size or interval fields apply.

**Server compatibility:** server requires `sessionId == ""` to enter the stream-one path. Server rejects with HTTP 400 (`"stream-one mode is not allowed"`) when locked to packet-up.

**Status in our crate:** Implemented. `XhttpProtocolMode::StreamOne` is parsed in `config.rs`, `relay.rs::open_stream_one` uses one bidirectional HTTP/2 request, and tests cover the no-session-id URL behavior plus mode parsing.

---

## Mode 4 — `stream-down` (split endpoint)

`stream-down` is the *download role* of a stream-up connection with a separate `downloadSettings` endpoint — not a client-selectable `mode` string. It appears in upstream logging as `"XHTTP is downloading from … mode stream-down"`.

**Upload URL:** `<path>/<session-uuid>` (stream-up POST on primary endpoint). **Download URL:** `<path2>/<session-uuid>` (GET on `downloadSettings` endpoint — may be a CDN).

**Seq encoding:** none.

**Governing config fields:** `downloadSettings` (StreamConfig proto field 13) — a full nested stream config for the download endpoint, with its own `Config`, TLS, host, path.

**Status in our crate:** Missing. `XhttpTlsConfig` / `XhttpRealityConfig` have no `download_settings` field. Stream-up uses the same H2 connection for both GET and POST.

---

## Placement system (orthogonal to mode)

Upstream allows three metadata fields — session-id, seq, uplink data — to be placed in `path` / `query` / `header` / `cookie` / `body`. Implementation: `FillStreamRequest`, `FillPacketRequest`, `ApplyMetaToRequest` in `config.go`.

Our crate hardcodes path-only placement (`relay.rs:202-209`); `XhttpTlsConfig` and `XhttpRealityConfig` carry none of the placement key/value fields.

---

## Gap analysis

| Mode | Status | Est. LOC | Config fields already present |
| --- | --- | --- | --- |
| stream-up | Present (partial) | ~60 (split endpoint + placement) | `path`, `host` |
| packet-up | Missing | 250–350 | none directly |
| stream-one | Present | landed | no session segment; one bidirectional H2 request |
| stream-down (split endpoint) | Missing | ~80 | none — needs `download_settings` |

---

## Recommendation

`stream-one` has landed. The remaining implementation order is:

1. **Split-endpoint `stream-down` next** if CDN split download settings become a real profile-import requirement; this is smaller than packet-up and mostly adds `download_settings` config plus separate download endpoint construction.

2. **`packet-up` last** because it requires per-chunk POST sequencing, placement support, buffering semantics, and more tests.

---

## Cross-references

- Audit finding H3 (xHTTP modes incomplete) — this document.
- `native/rust/crates/ripdpi-xhttp/src/config.rs` and `relay.rs` are now the source of truth for landed mode support.
- Companion audit finding C3 (Vision flow unconditional) — closed by commit `a6f2cab2 feat(vless): per-profile flow selection (audit C3)`.
- Upstream source files (xray-core HEAD): - `transport/internet/splithttp/dialer.go` - `transport/internet/splithttp/client.go` - `transport/internet/splithttp/hub.go` - `transport/internet/splithttp/upload_queue.go` - `transport/internet/splithttp/config.go` - `transport/internet/splithttp/config.proto`
