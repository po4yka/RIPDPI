//go:build darwin

package libXray

import (
	"context"
	"net"
	"testing"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/internet"
)

// Darwin can allocate a dual-stack wildcard UDP port already held by an IPv4
// loopback client. A native loopback path must stay in that client's IP family.
func TestManagedUDPv4LoopbackUsesIPv4Binding(t *testing.T) {
	listener := udpEcho(t)
	destination, err := xnet.ParseDestination("udp:" + listener.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	connection, err := internet.DialSystem(context.Background(), destination, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { connection.Close() })
	if local, ok := connection.LocalAddr().(*net.UDPAddr); !ok || local.IP.To4() == nil {
		t.Fatalf("IPv4 loopback allocated a dual-stack source: %v", connection.LocalAddr())
	}
	exchange(t, connection)
}

// An explicit IPv6 wildcard remains dual-stack, even for an IPv4 target.
func TestManagedUDPExplicitWildcardFamilyIsPreserved(t *testing.T) {
	for _, input := range []struct {
		name     string
		source   xnet.Address
		wantIPv4 bool
	}{
		{"default", nil, true},
		{"IPv4-any", xnet.AnyIP, true},
		{"IPv6-any", xnet.AnyIPv6, false},
	} {
		t.Run(input.name, func(t *testing.T) {
			listener := udpEcho(t)
			destination, err := xnet.ParseDestination("udp:" + listener.LocalAddr().String())
			if err != nil {
				t.Fatal(err)
			}
			connection, err := (&internet.DefaultSystemDialer{}).Dial(context.Background(), input.source, destination, nil)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { connection.Close() })
			local, ok := connection.LocalAddr().(*net.UDPAddr)
			if !ok || (local.IP.To4() != nil) != input.wantIPv4 {
				t.Fatalf("explicit source family changed: %v", connection.LocalAddr())
			}
			exchange(t, connection)
		})
	}
}
