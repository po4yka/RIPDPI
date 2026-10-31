// Generates Salamander conformance vectors with apernet/hysteria's own
// extras/obfs package. Every vector is written by the upstream obfuscator
// and read back through the upstream deobfuscator before it is saved.
package main

import (
	"bytes"
	"fmt"
	"math/rand"
	"net"
	"os"
	"path/filepath"
	"time"

	"github.com/apernet/hysteria/extras/v2/obfs"
)

type capture struct {
	written [][]byte
	inbound [][]byte
}

func (c *capture) ReadFrom(p []byte) (int, net.Addr, error) {
	if len(c.inbound) == 0 {
		return 0, nil, fmt.Errorf("no more inbound datagrams")
	}
	n := copy(p, c.inbound[0])
	c.inbound = c.inbound[1:]
	return n, nil, nil
}
func (c *capture) WriteTo(p []byte, _ net.Addr) (int, error) {
	c.written = append(c.written, append([]byte(nil), p...))
	return len(p), nil
}
func (c *capture) Close() error                     { return nil }
func (c *capture) LocalAddr() net.Addr              { return nil }
func (c *capture) SetDeadline(time.Time) error      { return nil }
func (c *capture) SetReadDeadline(time.Time) error  { return nil }
func (c *capture) SetWriteDeadline(time.Time) error { return nil }

func main() {
	out := os.Args[1]
	if err := os.MkdirAll(out, 0o755); err != nil {
		panic(err)
	}
	src := rand.New(rand.NewSource(20261031))
	bytesOf := func(n int) []byte { b := make([]byte, n); src.Read(b); return b }

	vectors := []struct {
		name  string
		psk   []byte
		plain []byte
	}{
		{"upstream-test-key-1200-bytes", []byte("average_password"), bytesOf(1200)},
		{"min-psk-1-byte", []byte("abcd"), bytesOf(1)},
		{"keystream-31-bytes", []byte("top-secret"), bytesOf(31)},
		{"keystream-32-bytes", []byte("top-secret"), bytesOf(32)},
		{"keystream-wrap-33-bytes", []byte("top-secret"), bytesOf(33)},
		{"keystream-wrap-65-bytes", []byte("top-secret"), bytesOf(65)},
		{"psk-one-block-120-bytes", bytesOf(120), bytesOf(64)},
		{"psk-two-blocks-121-bytes", bytesOf(121), bytesOf(64)},
		{"utf8-psk-1350-bytes", []byte("пароль-obfs"), bytesOf(1350)},
	}
	for _, v := range vectors {
		conn := &capture{}
		wrapped, err := obfs.WrapPacketConnSalamander(conn, v.psk)
		if err != nil {
			panic(err)
		}
		if _, err := wrapped.WriteTo(v.plain, nil); err != nil {
			panic(err)
		}
		wire := conn.written[0]
		if len(wire) != len(v.plain)+8 {
			panic("unexpected wire length")
		}
		// Read back through the upstream deobfuscator; a datagram of only
		// a salt is dropped by upstream, so it is queued first and must be
		// skipped.
		conn.inbound = [][]byte{wire[:8], wire}
		buf := make([]byte, 4096)
		n, _, err := wrapped.ReadFrom(buf)
		if err != nil || !bytes.Equal(buf[:n], v.plain) {
			panic(fmt.Sprintf("upstream round trip failed for %s: %v", v.name, err))
		}
		for ext, data := range map[string][]byte{".bin": wire, ".plain": v.plain, ".psk": v.psk} {
			if err := os.WriteFile(filepath.Join(out, v.name+ext), data, 0o644); err != nil {
				panic(err)
			}
		}
		fmt.Printf("%s psk=%dB plain=%dB salt=%x\n", v.name, len(v.psk), len(v.plain), wire[:8])
	}
	if _, err := obfs.WrapPacketConnSalamander(&capture{}, []byte("abc")); err != obfs.ErrPSKTooShort {
		panic("upstream accepted a 3-byte PSK")
	}
}
