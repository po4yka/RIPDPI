package main

import (
	"net/netip"
	"testing"
)

func TestPeerBindDefaultsToLoopback(t *testing.T) {
	t.Setenv("RIPDPI_AWG_BIND_IP", "")
	t.Setenv("RIPDPI_AWG_PEER_IP", "")
	bind, err := newPeerBind()
	if err != nil || bind.bindIP.String() != "127.0.0.1" {
		t.Fatalf("default bind: %v", err)
	}
	if !bind.allows(netip.MustParseAddr("127.0.0.1")) || bind.allows(netip.MustParseAddr("10.0.0.2")) {
		t.Fatal("default peer admission must remain loopback-only")
	}
}

func TestPeerBindAdmitsOnlyExplicitPrivatePeer(t *testing.T) {
	t.Setenv("RIPDPI_AWG_BIND_IP", "10.0.0.1")
	t.Setenv("RIPDPI_AWG_PEER_IP", "10.0.0.2")
	bind, err := newPeerBind()
	if err != nil {
		t.Fatal(err)
	}
	if !bind.allows(netip.MustParseAddr("10.0.0.2")) || bind.allows(netip.MustParseAddr("10.0.0.3")) || bind.allows(netip.MustParseAddr("127.0.0.1")) {
		t.Fatal("LAN admission must match the selected peer exactly")
	}
	for _, pair := range [][2]string{{"10.0.0.1", ""}, {"", "10.0.0.2"}, {"0.0.0.0", "10.0.0.2"}, {"10.0.0.1", "8.8.8.8"}, {"10.0.0.1", "10.0.0.1"}, {"fd00::1", "fd00::2"}} {
		t.Setenv("RIPDPI_AWG_BIND_IP", pair[0])
		t.Setenv("RIPDPI_AWG_PEER_IP", pair[1])
		if _, err := newPeerBind(); err == nil {
			t.Fatalf("invalid opt-in accepted: %v", pair)
		}
	}
}
