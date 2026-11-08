package socks

import (
	"bytes"
	"context"
	"fmt"
	stdnet "net"
	"strings"
	"testing"
	"time"

	appdispatcher "github.com/xtls/xray-core/app/dispatcher"
	"github.com/xtls/xray-core/app/proxyman"
	_ "github.com/xtls/xray-core/app/proxyman/outbound"
	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/routing"
	"github.com/xtls/xray-core/proxy/freedom"
)

func TestManagedUDPParserRejectsMalformed(t *testing.T) {
	valid := []byte{0, 0, 0, 5, 1, 'a', 1, 127, 0, 0, 1, 0, 53}
	for length := 0; length < len(valid); length++ {
		packet := buf.FromBytes(valid[:length])
		_, _, err := decodeManagedUDP(packet)
		packet.Release()
		if err == nil {
			t.Fatalf("accepted truncated frame of %d bytes", length)
		}
	}
	for _, index := range []int{0, 1, 2, 3, 4, 5, 6} {
		frame := append([]byte(nil), valid...)
		frame[index] = 255
		packet := buf.FromBytes(frame)
		_, _, err := decodeManagedUDP(packet)
		packet.Release()
		if err == nil {
			t.Fatalf("accepted invalid header field %d", index)
		}
	}
	packet := buf.FromBytes(valid)
	defer packet.Release()
	if _, err := DecodeUDPPacket(packet); err == nil {
		t.Fatal("public SOCKS parser accepted private ATYP5")
	}
}

func TestManagedUDPAdmissionRetainsLiveAndReclaimsClosed(t *testing.T) {
	instance, err := core.New(&core.Config{
		App:      []*serial.TypedMessage{serial.ToTypedMessage(&appdispatcher.Config{}), serial.ToTypedMessage(&proxyman.OutboundConfig{})},
		Outbound: []*core.OutboundHandlerConfig{{ProxySettings: serial.ToTypedMessage(&freedom.Config{})}},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { instance.Close() })
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	echo, err := stdnet.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { echo.Close() })
	lateSeen := make(chan struct{}, 1)
	releaseLate := make(chan struct{})
	t.Cleanup(func() { close(releaseLate) })
	go func() {
		buffer := make([]byte, 512)
		for {
			n, peer, err := echo.ReadFrom(buffer)
			if err != nil {
				return
			}
			if n == 2 {
				lateSeen <- struct{}{}
				<-releaseLate
			}
			echo.WriteTo(buffer[:n], peer)
		}
	}()
	collector, err := stdnet.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { collector.Close() })
	output, err := stdnet.Dial("udp4", collector.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { output.Close() })
	d := &managedUDPDispatch{entries: make(map[managedUDPAuthority]*managedUDPEntry), dispatcher: instance.GetFeature(routing.DispatcherType()).(routing.Dispatcher), conn: output}
	t.Cleanup(d.close)
	port := echo.LocalAddr().(*stdnet.UDPAddr).Port
	frame := func(index int) []byte {
		host := fmt.Sprintf("host-%d.invalid", index)
		packet := append([]byte{0, 0, 0, 5, byte(len(host))}, host...)
		return append(packet, 1, 127, 0, 0, 1, byte(port>>8), byte(port), byte(index))
	}
	exchange := func(index int) {
		t.Helper()
		packet := frame(index)
		d.dispatch(context.Background(), buf.FromBytes(packet))
		collector.SetReadDeadline(time.Now().Add(2 * time.Second))
		response := make([]byte, 512)
		n, _, err := collector.ReadFrom(response)
		if err != nil || !bytes.Equal(response[:n], packet) {
			t.Fatalf("authority %d: response %x, error %v", index, response[:n], err)
		}
	}
	for i := 0; i < 256; i++ {
		exchange(i)
	}
	d.dispatch(context.Background(), buf.FromBytes(frame(256)))
	collector.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
	if _, _, err := collector.ReadFrom(make([]byte, 512)); err == nil {
		t.Fatal("new authority exceeded the live admission bound")
	}
	exchange(0)
	for authority, entry := range d.entries {
		if authority.host == "host-1.invalid" {
			entry.dispatcher.RemoveRay()
		}
	}
	exchange(256)
	if len(d.entries) != 256 {
		t.Fatal("closed authority was not replaced within the admission bound")
	}
	empty := frame(0)
	empty = empty[:len(empty)-1]
	d.dispatch(context.Background(), buf.FromBytes(empty))
	collector.SetReadDeadline(time.Now().Add(time.Second))
	response := make([]byte, 512)
	if n, _, err := collector.ReadFrom(response); err != nil || !bytes.Equal(response[:n], empty) {
		t.Fatalf("direct freedom lost an empty UDP request or reply: %x %v", response[:n], err)
	}
	d.dispatch(context.Background(), buf.FromBytes(append(frame(0), 255)))
	select {
	case <-lateSeen:
	case <-time.After(time.Second):
		t.Fatal("late-response fixture did not receive its real datagram")
	}
	d.close()
	releaseLate <- struct{}{}
	collector.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
	if _, _, err := collector.ReadFrom(make([]byte, 512)); err == nil {
		t.Fatal("closed authority delivered a late response")
	}
	for _, entry := range d.entries {
		if entry.dispatcher.IsActive() {
			t.Fatal("shutdown retained a live ray")
		}
	}
}

func TestManagedUDPParserPreservesIPv6AndEmptyDatagram(t *testing.T) {
	host := strings.Repeat("a", 253) + "."
	frame := append([]byte{0, 0, 0, 5, byte(len(host))}, host...)
	frame = append(frame, 4)
	frame = append(frame, stdnet.ParseIP("2001:db8::1234").To16()...)
	frame = append(frame, 0x12, 0x34)
	packet := buf.FromBytes(frame)
	defer packet.Release()
	authority, header, err := decodeManagedUDP(packet)
	if err != nil {
		t.Fatal(err)
	}
	if authority.host != strings.Repeat("a", 253) || !authority.destination.Address.IP().Equal(stdnet.ParseIP("2001:db8::1234")) || authority.destination.Port != 0x1234 || !packet.IsEmpty() || !bytes.Equal(header, frame) {
		t.Fatalf("private authority or empty datagram changed: %+v", authority)
	}
}

func TestManagedUDPRequiresExplicitAuthenticatedLoopbackInbound(t *testing.T) {
	server := &Server{config: &ServerConfig{AuthType: AuthType_PASSWORD}}
	for _, input := range []struct {
		tag, gateway string
		auth         AuthType
		allowed      bool
	}{
		{"ripdpi-managed-socks-in-v1", "127.0.0.1", AuthType_PASSWORD, true},
		{"ripdpi-managed-socks-in-v1", "::1", AuthType_PASSWORD, true},
		{"socks-in", "127.0.0.1", AuthType_PASSWORD, false},
		{"ripdpi-managed-socks-in-v1", "0.0.0.0", AuthType_PASSWORD, false},
		{"ripdpi-managed-socks-in-v1", "192.0.2.1", AuthType_PASSWORD, false},
		{"ripdpi-managed-socks-in-v1", "127.0.0.1", AuthType_NO_AUTH, false},
	} {
		server.config.AuthType = input.auth
		ctx := session.ContextWithInbound(context.Background(), &session.Inbound{Tag: input.tag, Gateway: net.UDPDestination(net.ParseAddress(input.gateway), 1234)})
		if server.acceptsManagedUDP(ctx) != input.allowed {
			t.Fatalf("wrong private capability for %+v", input)
		}
	}
	if server.acceptsManagedUDP(context.Background()) {
		t.Fatal("missing inbound admitted private frames")
	}
}
