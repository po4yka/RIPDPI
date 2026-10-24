package main

import (
	"bytes"
	"fmt"
	"io"
	"net"
	"testing"
	"time"
)

func socksDNSPacket(t *testing.T, port int, address net.IP, destinationPort int) ([]byte, error) {
	t.Helper()
	control, err := net.DialTimeout("tcp4", fmt.Sprintf("127.0.0.1:%d", port), time.Second)
	if err != nil {
		return nil, err
	}
	defer control.Close()
	_ = control.SetDeadline(time.Now().Add(time.Second))
	if _, err = control.Write([]byte{5, 1, 0}); err != nil {
		return nil, err
	}
	greeting := make([]byte, 2)
	if _, err = io.ReadFull(control, greeting); err != nil {
		return nil, err
	}
	if !bytes.Equal(greeting, []byte{5, 0}) {
		return nil, fmt.Errorf("SOCKS greeting refused")
	}
	if _, err = control.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		return nil, err
	}
	target, err := readSocksAddress(control)
	if err != nil {
		return nil, err
	}
	packet, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	defer packet.Close()
	_ = packet.SetDeadline(time.Now().Add(250 * time.Millisecond))
	payload := append([]byte{0, 0, 0, 1}, address.To4()...)
	payload = append(payload, byte(destinationPort>>8), byte(destinationPort))
	payload = append(payload, mustDNSQuery(t, ownedDNSName)...)
	if _, err = packet.WriteTo(payload, target); err != nil {
		return nil, err
	}
	buffer := make([]byte, 512)
	n, _, err := packet.ReadFrom(buffer)
	if err != nil {
		return nil, err
	}
	return stripSocksUDPHeader(buffer[:n])
}

func rejectHysteriaTCP(t *testing.T, port int) {
	t.Helper()
	control, err := net.DialTimeout("tcp4", fmt.Sprintf("127.0.0.1:%d", port), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer control.Close()
	_ = control.SetDeadline(time.Now().Add(250 * time.Millisecond))
	if _, err := control.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(control, greeting); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(greeting, []byte{5, 0}) {
		t.Fatal("SOCKS greeting refused")
	}
	// This HTTP target is allowed on the VLESS ingress, but never on this one.
	if _, err := control.Write([]byte{5, 1, 0, 1, 192, 0, 2, 77, 0, 80}); err != nil {
		t.Fatal(err)
	}
	if _, err := readSocksAddress(control); err != nil {
		return
	}
	_, _ = io.WriteString(control, "GET /forbidden HTTP/1.0\r\n\r\n")
	buffer := make([]byte, 128)
	if n, _ := control.Read(buffer); n != 0 {
		t.Fatal("SOCKS ingress forwarded denied TCP")
	}
}

func TestHysteriaDNSIngressRoutesOnlyOwnedDestinationAndRestarts(t *testing.T) {
	p, err := startPeer(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer p.close()
	port := p.manifest.HysteriaSOCKSPort
	for cycle := 0; cycle < 2; cycle++ {
		answer, err := socksDNSPacket(t, port, net.IPv4(192, 0, 2, 53), p.manifest.DNSPort)
		if err != nil {
			t.Fatal(err)
		}
		parsed, err := parseDNSAnswer(answer, ownedDNSName)
		if err != nil || parsed.String() != "192.0.2.77" {
			t.Fatalf("owned DNS reply: %v %v", parsed, err)
		}
		for _, target := range []struct {
			address net.IP
			port    int
		}{
			{net.IPv4(192, 0, 2, 54), p.manifest.DNSPort},
			{net.IPv4(127, 0, 0, 1), p.manifest.DNSPort},
			{net.IPv4(192, 0, 2, 53), 53},
		} {
			if _, err := socksDNSPacket(t, port, target.address, target.port); err == nil {
				t.Fatal("non-owned destination received a response")
			}
		}
		rejectHysteriaTCP(t, port)
		if p.count.Load() != 0 {
			t.Fatal("SOCKS TCP reached owned HTTP origin")
		}
		if p.dnsCount.Load() != int64(cycle+1) {
			t.Fatal("rejected destination reached owned DNS")
		}
		if err := p.setRunning(false); err != nil {
			t.Fatal(err)
		}
		tcp, err := net.Listen("tcp4", fmt.Sprintf("127.0.0.1:%d", port))
		if err != nil {
			t.Fatal("stopped peer retained SOCKS TCP port", err)
		}
		udp, err := net.ListenPacket("udp4", tcp.Addr().String())
		if err != nil {
			tcp.Close()
			t.Fatal("stopped peer retained SOCKS UDP port", err)
		}
		tcp.Close()
		udp.Close()
		if cycle == 0 {
			if err := p.setRunning(true); err != nil {
				t.Fatal(err)
			}
		}
	}
}
