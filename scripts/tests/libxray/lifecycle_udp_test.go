package xray

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"testing"
	"time"
)

type managedUDPTestConn struct {
	*net.UDPConn
	relay *net.UDPAddr
	nonce [16]byte
	next  uint64
}

func (c *managedUDPTestConn) Write(payload []byte) (int, error) {
	c.next++
	frame := managedSessionFrame(c.nonce, c.next, payload)
	_, err := c.WriteToUDP(frame, c.relay)
	return len(payload), err
}

func (c *managedUDPTestConn) Read(payload []byte) (int, error) {
	frame := make([]byte, 65507)
	n, source, err := c.ReadFromUDP(frame)
	if err != nil {
		return 0, err
	}
	if n < 32 || source.String() != c.relay.String() || string(frame[:4]) != "RUF1" || frame[4] != 2 || frame[5] != 0 ||
		int(binary.BigEndian.Uint16(frame[6:8])) != n-32 || !bytes.Equal(frame[8:24], c.nonce[:]) || binary.BigEndian.Uint64(frame[24:32]) != 0 {
		return 0, fmt.Errorf("invalid session reply: %x", frame[:n])
	}
	return copy(payload, frame[32:n]), nil
}

func managedSessionFrame(nonce [16]byte, id uint64, payload []byte) []byte {
	frame := make([]byte, 32, 32+len(payload))
	copy(frame, "RUF1")
	frame[4] = 1
	binary.BigEndian.PutUint16(frame[6:8], uint16(len(payload)))
	copy(frame[8:24], nonce[:])
	binary.BigEndian.PutUint64(frame[24:32], id)
	return append(frame, payload...)
}

func managedUDPClient(t *testing.T, port int) (net.Conn, net.Conn) {
	t.Helper()
	control := managedUDPControl(t, port)
	udp, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { udp.Close() })
	source := udp.LocalAddr().(*net.UDPAddr)
	control.Write([]byte{5, 0x80, 0, 1, 127, 0, 0, 1, byte(source.Port >> 8), byte(source.Port)})
	bound := make([]byte, 10)
	if _, err := io.ReadFull(control, bound); err != nil || !bytes.Equal(bound[:4], []byte{5, 0, 0, 1}) {
		t.Fatalf("SOCKS private association: %x %v", bound, err)
	}
	preface := make([]byte, 20)
	if _, err := io.ReadFull(control, preface); err != nil || string(preface[:4]) != "RUF1" {
		t.Fatalf("private session preface: %x %v", preface, err)
	}
	client := &managedUDPTestConn{UDPConn: udp, relay: &net.UDPAddr{IP: net.IP(bound[4:8]), Port: int(binary.BigEndian.Uint16(bound[8:]))}}
	copy(client.nonce[:], preface[4:])
	return control, client
}

func managedFrame(host string, ip net.IP, port int, payload []byte) []byte {
	frame := append([]byte{0, 0, 0, 5, byte(len(host))}, host...)
	frame = append(frame, 1)
	frame = append(frame, ip.To4()...)
	frame = binary.BigEndian.AppendUint16(frame, uint16(port))
	return append(frame, payload...)
}

// Independent RFC 1928 peer: route identity comes from the selected listener;
// the numeric destination in Xray's wire frame determines the real echo socket.
func managedSOCKSOracle(t *testing.T, marker string) int {
	t.Helper()
	control, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	relay, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		control.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() { control.Close(); relay.Close() })
	go func() {
		for {
			conn, err := control.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				conn.SetDeadline(time.Now().Add(5 * time.Second))
				greeting := make([]byte, 2)
				if _, err := io.ReadFull(conn, greeting); err != nil {
					return
				}
				if _, err := io.CopyN(io.Discard, conn, int64(greeting[1])); err != nil {
					return
				}
				conn.Write([]byte{5, 0})
				request := make([]byte, 10)
				if _, err := io.ReadFull(conn, request); err != nil || !bytes.Equal(request[:4], []byte{5, 3, 0, 1}) {
					return
				}
				port := relay.LocalAddr().(*net.UDPAddr).Port
				conn.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, byte(port >> 8), byte(port)})
				io.Copy(io.Discard, conn)
			}()
		}
	}()
	go func() {
		frame := make([]byte, 2048)
		for {
			n, peer, err := relay.ReadFrom(frame)
			if err != nil {
				return
			}
			if n < 10 || !bytes.Equal(frame[:4], []byte{0, 0, 0, 1}) {
				continue
			}
			destination := &net.UDPAddr{IP: net.IP(frame[4:8]), Port: int(binary.BigEndian.Uint16(frame[8:10]))}
			upstream, err := net.DialUDP("udp4", nil, destination)
			if err != nil {
				continue
			}
			upstream.SetDeadline(time.Now().Add(time.Second))
			upstream.Write(frame[10:n])
			payload := make([]byte, 2048)
			count, err := upstream.Read(payload)
			upstream.Close()
			if err != nil {
				continue
			}
			response := append([]byte(nil), frame[:10]...)
			if bytes.HasPrefix(payload[:count], []byte("probe-wrong")) {
				wrong := append([]byte(nil), response...)
				wrong[7] = 9
				relay.WriteTo(append(wrong, "wrong-ip"...), peer)
				wrong = append([]byte(nil), response...)
				wrong[9] ^= 1
				relay.WriteTo(append(wrong, "wrong-port"...), peer)
			}
			if count != 0 {
				response = append(response, marker...)
			}
			response = append(response, payload[:count]...)
			relay.WriteTo(response, peer)
		}
	}()
	return control.Addr().(*net.TCPAddr).Port
}

func TestManagedUDPAuthorityRoutingPreservesPin(t *testing.T) {
	echo, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { echo.Close() })
	go func() {
		buffer := make([]byte, 256)
		for {
			n, peer, err := echo.ReadFrom(buffer)
			if err != nil {
				return
			}
			echo.WriteTo(buffer[:n], peer)
		}
	}()
	portReservation, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	inboundPort := portReservation.Addr().(*net.TCPAddr).Port
	portReservation.Close()
	alpha := managedSOCKSOracle(t, "alpha:")
	beta := managedSOCKSOracle(t, "beta:")
	config := fmt.Sprintf(`{
		"dns":{"hosts":{"alpha.invalid":"127.0.0.9","beta.invalid":"127.0.0.9"}},
		"inbounds":[{"tag":"ripdpi-managed-socks-in-v1","listen":"127.0.0.1","port":%d,"protocol":"socks","settings":{"auth":"password","accounts":[{"user":"u","pass":"p"}],"udp":true}}],
		"outbounds":[{"tag":"deny","protocol":"blackhole"},{"tag":"alpha","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":%d}]}},{"tag":"beta","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":%d}]}}],
		"routing":{"domainStrategy":"AsIs","rules":[{"type":"field","domain":["full:alpha.invalid"],"outboundTag":"alpha"},{"type":"field","domain":["full:beta.invalid"],"outboundTag":"beta"}]}
	}`, inboundPort, alpha, beta)
	if err := RunXray(config); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := StopXray(); err != nil {
			t.Error(err)
		}
	})
	_, udp := managedUDPClient(t, inboundPort)
	targetPort := echo.LocalAddr().(*net.UDPAddr).Port
	for index, host := range []string{"alpha.invalid", "beta.invalid", "alpha.invalid"} {
		payload := []byte(fmt.Sprintf("packet-%d", index))
		frame := managedFrame(host, net.ParseIP("127.0.0.1"), targetPort, payload)
		udp.SetDeadline(time.Now().Add(2 * time.Second))
		if _, err := udp.Write(frame); err != nil {
			t.Fatal(err)
		}
		response := make([]byte, 512)
		n, err := udp.Read(response)
		if err != nil {
			t.Fatalf("private UDP %s did not reach its pinned endpoint: %v", host, err)
		}
		from := "alpha:"
		if host == "beta.invalid" {
			from = "beta:"
		}
		expected := managedFrame(host, net.ParseIP("127.0.0.1"), targetPort, append([]byte(from), payload...))
		if !bytes.Equal(response[:n], expected) {
			t.Fatalf("authority/routing/pin mismatch: got %x want %x", response[:n], expected)
		}
	}
	for _, name := range []string{"wrong-peer-then-valid", "empty-request-and-reply"} {
		t.Run(name, func(t *testing.T) {
			_, client := managedUDPClient(t, inboundPort)
			var payload, expectedPayload []byte
			if name == "wrong-peer-then-valid" {
				payload = []byte("probe-wrong-authority")
				expectedPayload = append([]byte("alpha:"), payload...)
			}
			client.SetDeadline(time.Now().Add(2 * time.Second))
			client.Write(managedFrame("alpha.invalid", net.ParseIP("127.0.0.1"), targetPort, payload))
			response := make([]byte, 512)
			n, err := client.Read(response)
			if err != nil {
				t.Fatal(err)
			}
			expected := managedFrame("alpha.invalid", net.ParseIP("127.0.0.1"), targetPort, expectedPayload)
			if !bytes.Equal(response[:n], expected) {
				t.Fatalf("wrong authority or datagram boundary: got %x want %x", response[:n], expected)
			}
		})
	}
}
