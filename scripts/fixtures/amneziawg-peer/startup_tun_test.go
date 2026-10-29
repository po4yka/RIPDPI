package main

import (
	"net/netip"
	"testing"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

type offsetTUN struct {
	tun.Device
	offsets chan int
}

func (t *offsetTUN) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	t.offsets <- offset
	return t.Device.Read(bufs, sizes, offset)
}

func readOffset(t *testing.T, offsets <-chan int) int {
	t.Helper()
	select {
	case offset := <-offsets:
		return offset
	case <-time.After(3 * time.Second):
		t.Fatal("TUN read did not start")
		return 0
	}
}

func TestUpstreamFirstReadReloadsConfiguredPadding(t *testing.T) {
	for _, barrier := range []bool{false, true} {
		t.Run(map[bool]string{false: "upstream-control", true: "configured-barrier"}[barrier], func(t *testing.T) {
			inner, _, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.77.0.1")}, nil, 1280)
			if err != nil {
				t.Fatal(err)
			}
			actual := &offsetTUN{Device: inner, offsets: make(chan int, 4)}
			gate := newConfiguredTUN(actual)
			var reader tun.Device = actual
			if barrier {
				reader = gate
			}
			outer := &offsetTUN{Device: reader, offsets: make(chan int, 4)}
			bind, err := newPeerBind()
			if err != nil {
				t.Fatal(err)
			}
			peer := device.NewDevice(outer, bind, device.NewLogger(device.LogLevelSilent, ""))
			defer peer.Close()
			if first := readOffset(t, outer.offsets); first != 16 {
				t.Fatalf("initial offset=%d, want 16", first)
			}
			if err := peer.IpcSet("s4=12\n"); err != nil {
				t.Fatal(err)
			}
			gate.configured()
			want := 16
			if barrier {
				want = 28
			}
			if got := readOffset(t, actual.offsets); got != want {
				t.Fatalf("first actual read offset=%d, want %d", got, want)
			}
		})
	}
}

func TestConfiguredTUNCloseBeforeConfigurationReleasesReader(t *testing.T) {
	inner, _, err := netstack.CreateNetTUN(nil, nil, 1280)
	if err != nil {
		t.Fatal(err)
	}
	gate := newConfiguredTUN(inner)
	done := make(chan struct{})
	go func() { defer close(done); gate.Read(make([][]byte, 1), make([]int, 1), 16) }()
	if err := gate.Close(); err != nil {
		t.Fatal(err)
	}
	gate.configured()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("Close left first Read blocked")
	}
}

func TestEveryMatrixProfileIsAcceptedByPinnedPeer(t *testing.T) {
	for _, name := range []string{"awg-3.1-ranges-v1", "amneziavpn-5x", "v3-cpa", "v3-trailers", "v3-cpa-trailers", "v3-aligned", "v3-fixed-headers"} {
		t.Run(name, func(t *testing.T) {
			profile, err := selectWireProfile(name)
			if err != nil {
				t.Fatal(err)
			}
			inner, _, err := netstack.CreateNetTUN(nil, nil, 1280)
			if err != nil {
				t.Fatal(err)
			}
			gate := newConfiguredTUN(inner)
			bind, err := newPeerBind()
			if err != nil {
				t.Fatal(err)
			}
			peer := device.NewDevice(gate, bind, device.NewLogger(device.LogLevelSilent, ""))
			defer peer.Close()
			if err := peer.IpcSet(profile.iface); err != nil {
				t.Fatal(err)
			}
			gate.configured()
		})
	}
}
