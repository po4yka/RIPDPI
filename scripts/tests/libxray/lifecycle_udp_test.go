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

func managedUDPClient(t *testing.T, port int) (net.Conn, net.Conn) {
	t.Helper()
	control, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port))
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
	control.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0})
	bound := make([]byte, 10)
	if _, err := io.ReadFull(control, bound); err != nil || !bytes.Equal(bound[:4], []byte{5, 0, 0, 1}) {
		t.Fatalf("SOCKS association: %x %v", bound, err)
	}
	udp, err := net.Dial("udp4", fmt.Sprintf("%s:%d", net.IP(bound[4:8]), binary.BigEndian.Uint16(bound[8:])))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { udp.Close() })
	return control, udp
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
	if err := RunXrayFromJSON(t.TempDir(), "", config); err != nil {
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
