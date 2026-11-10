package xray

import (
	"bytes"
	"fmt"
	"io"
	"net"
	"testing"
	"time"
)

func managedUDPControl(t *testing.T, port int) net.Conn {
	t.Helper()
	control, err := net.Dial("tcp4", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { control.Close() })
	control.SetDeadline(time.Now().Add(2 * time.Second))
	control.Write([]byte{5, 1, 2})
	reply := make([]byte, 2)
	if _, err := io.ReadFull(control, reply); err != nil || !bytes.Equal(reply, []byte{5, 2}) {
		t.Fatalf("SOCKS greeting: %x %v", reply, err)
	}
	control.Write([]byte{1, 1, 'u', 1, 'p'})
	if _, err := io.ReadFull(control, reply); err != nil || !bytes.Equal(reply, []byte{1, 0}) {
		t.Fatalf("SOCKS auth: %x %v", reply, err)
	}
	return control
}

func startManagedUDPServer(t *testing.T) int {
	t.Helper()
	return startUDPServer(t, "ripdpi-managed-socks-in-v1")
}

func startUDPServer(t *testing.T, tag string) int {
	t.Helper()
	reservation, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := reservation.Addr().(*net.TCPAddr).Port
	reservation.Close()
	config := fmt.Sprintf(`{"inbounds":[{"tag":%q,"listen":"127.0.0.1","port":%d,"protocol":"socks","settings":{"auth":"password","accounts":[{"user":"u","pass":"p"}],"udp":true}}],"outbounds":[{"protocol":"freedom"}]}`, tag, port)
	if err := RunXrayFromJSON(t.TempDir(), "", config); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := StopXray(); err != nil {
			t.Error(err)
		}
	})
	return port
}

func TestManagedUDPRejectsStandardAssociation(t *testing.T) {
	control := managedUDPControl(t, startManagedUDPServer(t))
	control.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0})
	reply := make([]byte, 10)
	if _, err := io.ReadFull(control, reply); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(reply[:4], []byte{5, 7, 0, 1}) {
		t.Fatalf("managed standard UDP bypass accepted: %x", reply)
	}
}

func TestManagedUDPSessionAuthorityAndLifetime(t *testing.T) {
	port := startManagedUDPServer(t)
	echo, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { echo.Close() })
	received := make(chan string, 32)
	go func() {
		buffer := make([]byte, 256)
		for {
			n, source, err := echo.ReadFromUDP(buffer)
			if err != nil {
				return
			}
			received <- string(buffer[:n])
			echo.WriteToUDP(buffer[:n], source)
		}
	}()
	body := func(value string) []byte {
		target := echo.LocalAddr().(*net.UDPAddr)
		return append([]byte{0, 0, 0, 1, 127, 0, 0, 1, byte(target.Port >> 8), byte(target.Port)}, value...)
	}
	control, connection := managedUDPClient(t, port)
	client := connection.(*managedUDPTestConn)
	exchange := func(value string) {
		t.Helper()
		client.SetDeadline(time.Now().Add(2 * time.Second))
		if _, err := client.Write(body(value)); err != nil {
			t.Fatal(err)
		}
		response := make([]byte, 512)
		n, err := client.Read(response)
		if err != nil || !bytes.Equal(response[:n], body(value)) {
			t.Fatalf("session payload: %x %v", response[:n], err)
		}
		select {
		case got := <-received:
			if got != value {
				t.Fatalf("wrong peer receipt %q", got)
			}
		case <-time.After(time.Second):
			t.Fatal("independent echo did not observe payload")
		}
	}
	expectNoPayload := func() {
		t.Helper()
		select {
		case value := <-received:
			t.Fatalf("unauthorized frame reached independent peer: %q", value)
		case <-time.After(50 * time.Millisecond):
		}
	}
	exchange("initial")
	badNonce := client.nonce
	badNonce[0] ^= 1
	for name, frame := range map[string][]byte{
		"unwrapped":   body("raw"),
		"wrong-nonce": managedSessionFrame(badNonce, 2, body("nonce")),
		"replay":      managedSessionFrame(client.nonce, 1, body("replayed")),
		"zero-id":     managedSessionFrame(client.nonce, 0, body("zero")),
		"truncated":   []byte("RUF1"),
		"empty":       {},
	} {
		t.Run(name, func(t *testing.T) {
			client.WriteToUDP(frame, client.relay)
			expectNoPayload()
		})
	}
	for _, field := range []int{4, 5, 6} {
		frame := managedSessionFrame(client.nonce, 2, body("malformed"))
		frame[field] ^= 1
		client.WriteToUDP(frame, client.relay)
		expectNoPayload()
	}
	other, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { other.Close() })
	other.WriteToUDP(managedSessionFrame(client.nonce, 2, body("other-source")), client.relay)
	expectNoPayload()
	// Even after authenticated setup, the fixed shared UDP port is not an alternate entry.
	other.WriteToUDP(body("shared-raw"), &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port})
	expectNoPayload()
	exchange("after-invalid")
	oldNonce := client.nonce
	control.Close()
	deadline := time.Now().Add(2 * time.Second)
	for {
		rebound, err := net.ListenPacket("udp4", client.relay.String())
		if err == nil {
			rebound.Close()
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("control close retained its private UDP socket: %v", err)
		}
		time.Sleep(time.Millisecond)
	}
	client.WriteToUDP(managedSessionFrame(oldNonce, 3, body("closed-control")), client.relay)
	expectNoPayload()
	_, replacement := managedUDPClient(t, port)
	client = replacement.(*managedUDPTestConn)
	if client.nonce == oldNonce {
		t.Fatal("replacement association reused session nonce")
	}
	client.WriteToUDP(managedSessionFrame(oldNonce, 1, body("old-session")), client.relay)
	expectNoPayload()
	exchange("replacement")
}

func TestManagedUDPRejectsInvalidSource(t *testing.T) {
	port := startManagedUDPServer(t)
	for _, request := range [][]byte{
		{5, 0x80, 0, 1, 0, 0, 0, 0, 0, 53},
		{5, 0x80, 0, 1, 127, 0, 0, 2, 0, 53},
		{5, 0x80, 0, 1, 127, 0, 0, 1, 0, 0},
		{4, 0x80, 0, 1, 127, 0, 0, 1, 0, 53},
		{5, 0x80, 1, 1, 127, 0, 0, 1, 0, 53},
	} {
		control := managedUDPControl(t, port)
		control.Write(request)
		reply := make([]byte, 10)
		if _, err := io.ReadFull(control, reply); err != nil || !bytes.Equal(reply[:4], []byte{5, 7, 0, 1}) {
			t.Fatalf("invalid private source accepted: request=%x reply=%x err=%v", request, reply, err)
		}
		control.Close()
	}
}

func TestManagedUDPLeavesOrdinaryInboundStandard(t *testing.T) {
	port := startUDPServer(t, "ordinary-socks")
	control := managedUDPControl(t, port)
	control.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0})
	reply := make([]byte, 10)
	if _, err := io.ReadFull(control, reply); err != nil || !bytes.Equal(reply[:4], []byte{5, 0, 0, 1}) {
		t.Fatalf("ordinary standard association changed: %x %v", reply, err)
	}
	control.Close()
	control = managedUDPControl(t, port)
	control.Write([]byte{5, 0x80, 0, 1, 127, 0, 0, 1, 0, 53})
	if _, err := io.ReadFull(control, reply); err != nil || !bytes.Equal(reply[:4], []byte{5, 7, 0, 1}) {
		t.Fatalf("ordinary inbound exposed private association: %x %v", reply, err)
	}
}

func TestManagedUDPSessionPacketBoundary(t *testing.T) {
	port := startManagedUDPServer(t)
	echo, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { echo.Close() })
	received := make(chan int, 4)
	go func() {
		buffer := make([]byte, 65535)
		for {
			n, source, err := echo.ReadFromUDP(buffer)
			if err != nil {
				return
			}
			received <- n
			echo.WriteToUDP(buffer[:n], source)
		}
	}()
	_, client := managedUDPClient(t, port)
	body := func(size int) []byte {
		target := echo.LocalAddr().(*net.UDPAddr)
		header := []byte{0, 0, 0, 1, 127, 0, 0, 1, byte(target.Port >> 8), byte(target.Port)}
		return append(header, bytes.Repeat([]byte{0xa5}, size-len(header))...)
	}
	// The pinned core owns an 8192-byte SOCKS packet buffer. RUF1 must not reduce it.
	for _, size := range []int{8191, 8192} {
		packet := body(size)
		client.SetDeadline(time.Now().Add(2 * time.Second))
		if _, err := client.Write(packet); err != nil {
			t.Fatal(err)
		}
		response := make([]byte, 8192)
		n, err := client.Read(response)
		if err != nil || !bytes.Equal(response[:n], packet) {
			t.Fatalf("packet size %d changed: %d bytes %v", size, n, err)
		}
		if got := <-received; got != size-10 {
			t.Fatalf("peer received %d bytes for packet size %d", got, size)
		}
	}
	client.Write(body(8193))
	select {
	case size := <-received:
		t.Fatalf("oversized packet was truncated and dispatched as %d bytes", size)
	case <-time.After(50 * time.Millisecond):
	}
	client.SetDeadline(time.Now().Add(2 * time.Second))
	packet := body(11)
	client.Write(packet)
	response := make([]byte, 64)
	if n, err := client.Read(response); err != nil || !bytes.Equal(response[:n], packet) {
		t.Fatalf("oversized packet ended its association: %x %v", response[:n], err)
	}
}

func TestManagedUDPStopRevokesIdleControl(t *testing.T) {
	port := startManagedUDPServer(t)
	control, connection := managedUDPClient(t, port)
	client := connection.(*managedUDPTestConn)
	if err := StopXray(); err != nil {
		t.Fatal(err)
	}
	// The peer intentionally leaves control open: server stop owns revocation.
	rebound, err := net.ListenPacket("udp4", client.relay.String())
	if err != nil {
		t.Fatalf("runtime stop retained private UDP socket with idle control: %v", err)
	}
	rebound.Close()
	control.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := control.Read(make([]byte, 1)); err == nil {
		t.Fatal("runtime stop kept private control readable")
	} else if failure, ok := err.(net.Error); ok && failure.Timeout() {
		t.Fatal("runtime stop left idle private control open")
	}
	// A fresh managed runtime can own a new private session after stop.
	_, replacement := managedUDPClient(t, startManagedUDPServer(t))
	if replacement.(*managedUDPTestConn).nonce == client.nonce {
		t.Fatal("new runtime reused retired UDP session authority")
	}
}

func TestManagedUDPStopRevokesPendingHandshake(t *testing.T) {
	port := startManagedUDPServer(t)
	control, err := net.Dial("tcp4", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		t.Fatal(err)
	}
	defer control.Close()
	// Stop races registration before any SOCKS greeting or private listener exists.
	if err := StopXray(); err != nil {
		t.Fatal(err)
	}
	control.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := control.Read(make([]byte, 1)); err == nil {
		t.Fatal("stopped runtime retained a pending managed handshake")
	} else if failure, ok := err.(net.Error); ok && failure.Timeout() {
		t.Fatal("pending handshake escaped runtime shutdown")
	}
}
