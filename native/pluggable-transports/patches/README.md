# Lyrebird OBFS4 build patch

The `ripdpi-obfs4` asset supports OBFS4. The app builds Snowflake from its
separate source entry and builds WebTunnel from the repository-owned Rust
package. The Lyrebird asset is not a general-purpose Lyrebird distribution.
Custom native callers that select another Lyrebird transport are outside this
asset contract.

The upstream Lyrebird registry and command import Snowflake and WebTunnel even
when the caller selects OBFS4. These imports bring Pion and `wlynxg/anet` into
Android builds. The pinned anet version links to private Go network symbols,
which Go 1.27.4 rejects with its default linkname checks.

`lyrebird-obfs4-only.patch` removes these unused transports and the command's
Snowflake-specific proxy branches. OBFS4 retains its existing direct and upstream
proxy paths. The build keeps Go's default linkname checks. It does not change
the separate Snowflake asset or the Rust WebTunnel asset.

The manifest pins the upstream commit, the patch digest, and the input and
output digests of all three files. `sources.json` pins the manifest digest. The build
checks the checked-out commit and all three input files before it applies the patch.
The patch has zero context, because the full input digests provide the context
check. It checks all three output files after the patch. Prebuilt source and artifact
receipts must include the manifest digest. Patch files are Gradle task inputs.
An upstream update requires a new review and new digests.

Primary source at commit `59ac7e400c384773f5584dbe366516ad04a33d9f`:

- [Transport registry](https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird/-/blob/59ac7e400c384773f5584dbe366516ad04a33d9f/transports/transports.go)
- [Command](https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird/-/blob/59ac7e400c384773f5584dbe366516ad04a33d9f/cmd/lyrebird/lyrebird.go)
- [Proxy setup](https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird/-/blob/59ac7e400c384773f5584dbe366516ad04a33d9f/cmd/lyrebird/pt_extras.go)
- [Module pins](https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird/-/blob/59ac7e400c384773f5584dbe366516ad04a33d9f/go.mod)

The source fixtures under
`build-logic/convention/src/test/resources/lyrebird-obfs4/` are exact copies of
the three upstream files. They retain the upstream copyright and license. The
unit test applies the real patch to these bytes and verifies the reviewed
output digests. It also tests revision, digest, mutation, output-scope, and
prebuilt-receipt failures.
