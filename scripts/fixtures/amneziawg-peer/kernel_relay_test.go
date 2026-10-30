package main

import (
	"bytes"
	"net"
	"testing"
	"time"
)

func TestKernelRelayDropsFirstResponseAndPreservesEndpoint(t *testing.T) {
	open := func() *net.UDPConn {
		socket, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { socket.Close() })
		socket.SetDeadline(time.Now().Add(2 * time.Second))
		return socket
	}
	client, kernel, relay := open(), open(), open()
	observer := wireObservation{profile: legacyWireProfile, scenario: "timer-retry-loss"}
	report := kernelRelayReport{}
	done := make(chan error, 1)
	go func() { done <- relayKernel(relay, kernel.LocalAddr().(*net.UDPAddr).AddrPort(), &observer, &report) }()
	defer func() {
		relay.Close()
		if err := <-done; err != nil {
			t.Error(err)
		}
	}()
	request := wirePacket(156, 8, 100)
	client.WriteToUDP(request, relay.LocalAddr().(*net.UDPAddr))
	buffer := make([]byte, 2048)
	n, source, err := kernel.ReadFromUDP(buffer)
	if err != nil || !bytes.Equal(buffer[:n], request) || source.String() != relay.LocalAddr().String() {
		t.Fatal("request or relay source differs", err)
	}
	first, second := wirePacket(104, 12, 200), wirePacket(104, 12, 201)
	kernel.WriteToUDP(first, source)
	kernel.WriteToUDP(second, source)
	n, source, err = client.ReadFromUDP(buffer)
	if err != nil || !bytes.Equal(buffer[:n], second) || source.String() != relay.LocalAddr().String() {
		t.Fatal("first response was not dropped or source differs", err)
	}
	client.SetReadDeadline(time.Now().Add(30 * time.Millisecond))
	if _, _, err = client.ReadFromUDP(buffer); err == nil {
		t.Fatal("extra response reached client")
	}
}

func TestRelayCaptureRequiresBothLegsAndActualLoss(t *testing.T) {
	ip := [4]byte{127, 0, 0, 1}
	packet := func(source, destination uint16, payload string) capturedUDP {
		return capturedUDP{source: source, destination: destination, sourceIP: ip, destinationIP: ip, payload: []byte(payload)}
	}
	report := kernelRelayReport{Port: 50000, EndNS: 1, Actions: []relayAction{
		{"in", wireDigest([]byte("request")), false},
		{"out", wireDigest([]byte("lost")), true},
		{"out", wireDigest([]byte("response")), false},
	}}
	packets := []capturedUDP{packet(40000, 50000, "request"), packet(50000, 51820, "request"), packet(51820, 50000, "lost"), packet(51820, 50000, "response"), packet(50000, 40000, "response")}
	verify := func(values []capturedUDP, actions kernelRelayReport) error {
		check, err := newRelayCaptureCheck(actions)
		if err != nil {
			return err
		}
		for _, p := range values {
			if _, err = check.accept(p, p.source == 51820 || p.destination == 51820); err != nil {
				return err
			}
		}
		return check.finish()
	}
	if err := verify(packets, report); err != nil {
		t.Fatal(err)
	}
	changedEndpoint := append([]capturedUDP(nil), packets...)
	changedEndpoint[4].destination++
	if verify(changedEndpoint, report) == nil {
		t.Fatal("changed client endpoint accepted")
	}
	for removed := range packets {
		changed := append([]capturedUDP(nil), packets[:removed]...)
		changed = append(changed, packets[removed+1:]...)
		if verify(changed, report) == nil {
			t.Fatal("missing capture packet accepted")
		}
	}
	for _, extra := range []capturedUDP{packet(50000, 40000, "lost"), packets[4], packet(50000, 40000, "wrong")} {
		if verify(append(append([]capturedUDP(nil), packets...), extra), report) == nil {
			t.Fatal("extra or corrupt packet accepted")
		}
	}
	report.Actions[1].Dropped = false
	if verify(packets, report) == nil {
		t.Fatal("missing drop action accepted")
	}
}
