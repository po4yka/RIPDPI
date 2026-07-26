# reality-vector

Cross-implementation oracle for the Reality session_id seal in `ripdpi-vless::reality_seal::seal_session_id` (audit findings C1 + C2; see `docs/design/reality-boringssl-patch.md`).

This is a Go program that reproduces xray-core's `transport/internet/reality/reality.go:139-176` over a deterministic set of inputs and prints the resulting 32-byte sealed session_id. The same inputs are baked into the Rust frozen-vector test (`seal_session_id_matches_frozen_vector`); if the Rust seal ever differs from upstream xray-core, the Go and Rust outputs disagree.

## Run

```sh
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cp test-lab/reality-vector/main.go "$tmp/"
cd "$tmp"
go mod init ripdpi-reality-vector
go get golang.org/x/crypto@v0.52.0
go run main.go
```

The temporary module pins `x/crypto` to the same reviewed version used by the
local QUIC fixture and leaves the repository tree unchanged.

Expected output (also the frozen bytes in the Rust test):

```
17a7af6b73367933c34ddc9a7a3afbc17b75fa063c98b6aada107dc590853de0
```

If the Go program prints anything else, either upstream xray-core changed the seal contract or the Rust implementation drifted; reconcile both before re-blessing the frozen vector.
