package main

import (
	"sync"

	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
)

// configuredTUN makes upstream reload S4 after IpcSet before it consumes a packet.
// NewDevice starts Read with the old offset before the fixture can apply its config.
type configuredTUN struct {
	tun.Device
	ready      chan struct{}
	release    sync.Once
	firstRead  sync.Once
	acceptedIP func([]int)
}

func newConfiguredTUN(inner tun.Device) *configuredTUN {
	return &configuredTUN{Device: inner, ready: make(chan struct{})}
}

func (t *configuredTUN) configured() { t.release.Do(func() { close(t.ready) }) }

func (t *configuredTUN) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	first := false
	t.firstRead.Do(func() { first = true; <-t.ready })
	if first {
		return 0, nil
	}
	return t.Device.Read(bufs, sizes, offset)
}

func (t *configuredTUN) Close() error {
	// IpcSet can fail before configured; Close must still release the reader.
	t.configured()
	return t.Device.Close()
}

func (t *configuredTUN) Write(bufs [][]byte, offset int) (int, error) {
	lengths := make([]int, len(bufs))
	for i, packet := range bufs {
		lengths[i] = len(packet) - offset
	}
	count, err := t.Device.Write(bufs, offset)
	if t.acceptedIP != nil && count > 0 {
		t.acceptedIP(lengths[:count])
	}
	return count, err
}
