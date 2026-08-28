package libXray

import (
	"context"
	"io"
	"net"
	"testing"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/internet"
)

type testProtector struct {
	allowed bool
	calls   int
}

func (p *testProtector) ProtectFd(fd int) bool { p.calls++; return p.allowed }

func tcpEcho(t *testing.T) net.Listener {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		conn.SetDeadline(time.Now().Add(2 * time.Second))
		io.Copy(conn, conn)
	}()
	return listener
}

func udpEcho(t *testing.T) net.PacketConn {
	t.Helper()
	listener, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	go func() {
		buffer := make([]byte, 128)
		count, address, err := listener.ReadFrom(buffer)
		if err == nil {
			listener.WriteTo(buffer[:count], address)
		}
	}()
	return listener
}

func exchange(t *testing.T, conn net.Conn) {
	t.Helper()
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(2 * time.Second))
	payload := []byte("managed-protected-payload")
	if _, err := conn.Write(payload); err != nil {
		t.Fatal(err)
	}
	response := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, response); err != nil {
		t.Fatal(err)
	}
	if string(response) != string(payload) {
		t.Fatal("real protected socket changed payload")
	}
}

func TestManagedProtectionDenial(t *testing.T) {
	protector := &testProtector{}
	RegisterDialerController(protector)
	defer func() { protector.allowed = true }()
	for _, network := range []string{"tcp", "udp"} {
		t.Run(network, func(t *testing.T) {
			var address string
			if network == "tcp" {
				address = tcpEcho(t).Addr().String()
			} else {
				address = udpEcho(t).LocalAddr().String()
			}
			destination, err := xnet.ParseDestination(network + ":" + address)
			if err != nil {
				t.Fatal(err)
			}
			protector.allowed = false
			before := protector.calls
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			conn, err := internet.DialSystem(ctx, destination, nil)
			if conn != nil {
				conn.Close()
			}
			if err == nil {
				t.Fatal("denied protection still established a real connection")
			}
			if protector.calls == before {
				t.Fatal("native protector was not called")
			}
			protector.allowed = true
			conn, err = internet.DialSystem(ctx, destination, nil)
			if err != nil {
				t.Fatal(err)
			}
			exchange(t, conn)
		})
	}
}

func TestManagedListenerProtection(t *testing.T) {
	protector := &testProtector{}
	RegisterListenerController(protector)
	defer func() { protector.allowed = true }()
	for _, network := range []string{"tcp", "udp"} {
		t.Run(network, func(t *testing.T) {
			open := func() (io.Closer, error) {
				if network == "tcp" {
					return internet.ListenSystem(context.Background(), &net.TCPAddr{IP: net.ParseIP("127.0.0.1")}, nil)
				}
				return internet.ListenSystemPacket(context.Background(), &net.UDPAddr{IP: net.ParseIP("127.0.0.1")}, nil)
			}
			protector.allowed = false
			listener, err := open()
			if err == nil {
				listener.Close()
				t.Fatal("denied protection still bound a real listener")
			}
			protector.allowed = true
			listener, err = open()
			if err != nil {
				t.Fatal(err)
			}
			listener.Close()
		})
	}
}

func TestManagedDnsProtection(t *testing.T) {
	protector := &testProtector{}
	listener := udpEcho(t)
	InitDns(protector, listener.LocalAddr().String())
	defer ResetDns()
	conn, err := net.DefaultResolver.Dial(context.Background(), "udp", "ignored")
	if conn != nil {
		conn.Close()
	}
	if err == nil {
		t.Fatal("denied DNS protection still opened a real UDP socket")
	}
	if protector.calls == 0 {
		t.Fatal("DNS protection callback was not invoked")
	}
	protector.allowed = true
	conn, err = net.DefaultResolver.Dial(context.Background(), "udp", "ignored")
	if err != nil {
		t.Fatal(err)
	}
	exchange(t, conn)
}
