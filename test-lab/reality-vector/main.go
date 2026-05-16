// Reality session_id frozen-vector generator.
//
// Mirrors xray-core's transport/internet/reality/reality.go:139-176 over a
// fixed set of inputs so that the Rust frozen-vector test in
// `native/rust/crates/ripdpi-vless/src/reality_seal.rs::seal_session_id_matches_frozen_vector`
// can be cross-validated against the upstream Go implementation.
//
// Build:
//
//	cd test-lab/reality-vector
//	go run main.go
//
// Output is a single hex string — the 32 bytes the Rust test asserts on. If
// the Rust seal differs from upstream xray-core, this program will produce
// different bytes and the cross-check fails.
//
// The inputs intentionally use deterministic seeds (no system entropy) so
// the output is byte-identical across runs:
//
//   - X25519 priv_key  = [0x11; 32] (raw scalar; not BoringSSL-clamped here
//     because xray-core's `ecdhe.ECDH` accepts the raw key; if you set
//     priv_key via BoringSSL key_share, the Rust side reads the
//     post-clamping value, which still ECDHs to the same shared secret
//     because X25519 is clamp-invariant for valid scalars).
//   - X25519 server_pub = derived from [0x22; 32] (StaticSecret-style).
//   - client_random    = [1, 2, 3, ..., 32].
//   - short_id         = [0xAB, 0xCD].
//   - raw_hello        = 80-byte stub ClientHello with type=0x01,
//     body_len=0x4c, legacy_version=0x0303,
//     random=client_random, session_id_length=0x20.
//   - now              = 1_700_000_000.
//
// Required modules (run `go mod init reality-vector && go get ...`):
//
//	golang.org/x/crypto/curve25519
//	golang.org/x/crypto/hkdf
package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"os"

	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/hkdf"
)

func main() {
	priv := [32]byte{
		0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11,
		0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11,
		0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11,
		0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11,
	}

	// Derive server_pub from the [0x22; 32] StaticSecret-style seed.
	// curve25519.X25519 applies the same clamping convention as the
	// x25519-dalek StaticSecret::from used by the Rust capture, so the
	// resulting public key matches byte-for-byte.
	serverSeed := [32]byte{
		0x22, 0x22, 0x22, 0x22, 0x22, 0x22, 0x22, 0x22,
		0x22, 0x22, 0x22, 0x22, 0x22, 0x22, 0x22, 0x22,
		0x22, 0x22, 0x22, 0x22, 0x22, 0x22, 0x22, 0x22,
		0x22, 0x22, 0x22, 0x22, 0x22, 0x22, 0x22, 0x22,
	}
	serverPub, err := curve25519.X25519(serverSeed[:], curve25519.Basepoint)
	if err != nil {
		fmt.Fprintf(os.Stderr, "X25519 basepoint mult failed: %v\n", err)
		os.Exit(1)
	}

	var clientRandom [32]byte
	for i := range clientRandom {
		clientRandom[i] = byte(i) + 1
	}
	shortID := []byte{0xAB, 0xCD}
	const nowSecs uint32 = 1_700_000_000

	// raw_hello stub: type(1) | length(3) | legacy_version(2) |
	// random(32) | session_id_length(1) | session_id_slot(32) | tail(8).
	raw := make([]byte, 80)
	raw[0] = 0x01
	raw[1], raw[2], raw[3] = 0x00, 0x00, 0x4c
	raw[4], raw[5] = 0x03, 0x03
	copy(raw[6:38], clientRandom[:])
	raw[38] = 0x20

	// ECDH(priv, serverPub) → shared.
	shared, err := curve25519.X25519(priv[:], serverPub)
	if err != nil {
		fmt.Fprintf(os.Stderr, "X25519 ECDH failed: %v\n", err)
		os.Exit(1)
	}

	// HKDF-SHA256(salt = clientRandom[:20], IKM = shared, info = "REALITY", L = 32).
	r := hkdf.New(sha256.New, shared, clientRandom[:20], []byte("REALITY"))
	var authKey [32]byte
	if _, err := r.Read(authKey[:]); err != nil {
		fmt.Fprintf(os.Stderr, "HKDF read: %v\n", err)
		os.Exit(1)
	}

	// Plaintext: version(0x00 0x00 0x01) | reserved(0) | be32(now) | short_id(8 padded).
	pt := make([]byte, 16)
	pt[0], pt[1], pt[2] = 0x00, 0x00, 0x01
	pt[3] = 0x00
	binary.BigEndian.PutUint32(pt[4:8], nowSecs)
	copy(pt[8:], shortID)

	// AAD = raw with session_id slot zeroed.
	aad := make([]byte, len(raw))
	copy(aad, raw)
	for i := 39; i < 71; i++ {
		aad[i] = 0
	}

	block, err := aes.NewCipher(authKey[:])
	if err != nil {
		fmt.Fprintf(os.Stderr, "AES cipher: %v\n", err)
		os.Exit(1)
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		fmt.Fprintf(os.Stderr, "GCM: %v\n", err)
		os.Exit(1)
	}
	sealed := aead.Seal(nil, clientRandom[20:32], pt, aad)

	if len(sealed) != 32 {
		fmt.Fprintf(os.Stderr, "expected 32-byte seal, got %d\n", len(sealed))
		os.Exit(1)
	}
	fmt.Println(hex.EncodeToString(sealed))
}
