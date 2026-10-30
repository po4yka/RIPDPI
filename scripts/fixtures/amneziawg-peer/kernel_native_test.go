package main

import (
	"encoding/binary"
	"golang.org/x/crypto/chacha20"
	"net"
	"net/netip"
	"testing"
	"time"
)

func TestNativeRecoveryIgnoresOldKeyKeepalive(t *testing.T) {
	profile, _ := selectWireProfile("v3-cpa-trailers")
	socket, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer socket.Close()
	client, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	n := &nativeKernelExercise{profile: profile, socket: socket, remote: client.LocalAddr().(*net.UDPAddr).AddrPort(), start: time.Now(), packets: make([][]byte, 3), recoveryReceiver: 9, confirmed: make(chan struct{})}
	packet := func(receiver uint32) []byte {
		wire := make([]byte, 72)
		body := make([]byte, 48)
		binary.LittleEndian.PutUint32(body, 450)
		binary.LittleEndian.PutUint32(body[4:], receiver)
		cipher, _ := chacha20.NewUnauthenticatedCipher(profile.headerKey, wire[:12])
		cipher.XORKeyStream(wire[24:], body)
		return wire
	}
	if err = n.handle(packet(1), false, netip.MustParseAddrPort("127.0.0.1:51820")); err != nil {
		t.Fatal(err)
	}
	select {
	case <-n.confirmed:
		t.Fatal("old key completed recovery")
	default:
	}
	if err = n.handle(packet(9), false, netip.MustParseAddrPort("127.0.0.1:51820")); err != nil {
		t.Fatal(err)
	}
	select {
	case <-n.confirmed:
	default:
		t.Fatal("new key did not complete recovery")
	}
}
