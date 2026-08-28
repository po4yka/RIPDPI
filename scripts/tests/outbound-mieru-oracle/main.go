// Rootless interoperability peer: wire codec, authentication and SOCKS parser
// come from the pinned upstream module; only the destination echo is local.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/enfein/mieru/v3/apis/model"
	"github.com/enfein/mieru/v3/apis/server"
	"github.com/enfein/mieru/v3/pkg/appctl/appctlpb"
	"github.com/enfein/mieru/v3/pkg/log"
	"google.golang.org/protobuf/proto"
)

type loopbackListener struct{ listener net.Listener }

func (f *loopbackListener) Listen(context.Context, string, string) (net.Listener, error) {
	return f.listener, nil
}

type observedListener struct {
	net.Listener
	active   atomic.Int64
	accepted atomic.Int64
}
type observedConn struct {
	net.Conn
	owner *observedListener
	once  sync.Once
}

func (l *observedListener) Accept() (net.Conn, error) {
	c, err := l.Listener.Accept()
	if err != nil {
		return nil, err
	}
	l.active.Add(1)
	l.accepted.Add(1)
	return &observedConn{Conn: c, owner: l}, nil
}
func (c *observedConn) Close() error {
	err := c.Conn.Close()
	c.once.Do(func() { c.owner.active.Add(-1) })
	return err
}

func run() error {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return err
	}
	defer listener.Close()
	observed := &observedListener{Listener: listener}
	stats, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return err
	}
	statsDone := make(chan struct{})
	go func() {
		defer close(statsDone)
		for {
			c, err := stats.Accept()
			if err != nil {
				return
			}
			c.SetWriteDeadline(time.Now().Add(time.Second))
			fmt.Fprintf(c, "%d %d\n", observed.active.Load(), observed.accepted.Load())
			c.Close()
		}
	}()
	defer func() { stats.Close(); <-statsDone }()
	peer := server.NewServer()
	if os.Getenv("RIPDPI_OUTBOUND_TRACE") == "1" {
		log.SetFormatter(&log.CliFormatter{})
		log.SetLevel("TRACE")
		log.SetOutput(os.Stderr)
	}
	config := &server.ServerConfig{
		Config: &appctlpb.ServerConfig{
			PortBindings: []*appctlpb.PortBinding{{Port: proto.Int32(int32(listener.Addr().(*net.TCPAddr).Port)), Protocol: appctlpb.TransportProtocol_TCP.Enum()}},
			Users:        []*appctlpb.User{{Name: proto.String("outbound-interop"), Password: proto.String("loopback-test-password")}},
		},
		StreamListenerFactory: &loopbackListener{observed},
	}
	if err := peer.Store(config); err != nil {
		return err
	}
	if err := peer.Start(); err != nil {
		return err
	}
	defer peer.Stop()
	var workers sync.WaitGroup
	var connections sync.Map
	stopped := make(chan struct{})
	go func() {
		defer close(stopped)
		for {
			conn, request, err := peer.Accept()
			if err != nil {
				if !peer.IsRunning() {
					return
				}
				fmt.Fprintln(os.Stderr, "upstream accept:", err)
				continue
			}
			connections.Store(conn, struct{}{})
			workers.Add(1)
			go func() {
				defer workers.Done()
				defer connections.Delete(conn)
				defer conn.Close()
				conn.SetDeadline(time.Now().Add(20 * time.Second))
				if request.Command != 1 || request.DstAddr.String() != "interop.invalid:443" {
					fmt.Fprintln(os.Stderr, "unexpected upstream request", request)
					return
				}
				if err := model.WriteSocks5Response(conn, 0, model.AddrSpec{IP: net.IPv4(127, 0, 0, 1), Port: 443}); err != nil {
					return
				}
				io.Copy(conn, conn)
			}()
		}
	}()
	json.NewEncoder(os.Stdout).Encode(map[string]string{"endpoint": listener.Addr().String(), "stats": stats.Addr().String()})
	var stop [1]byte
	_, _ = os.Stdin.Read(stop[:])
	if os.Getenv("RIPDPI_OUTBOUND_EXPECT_IDLE") == "1" {
		deadline := time.Now().Add(time.Second)
		for observed.active.Load() != 0 && time.Now().Before(deadline) {
			time.Sleep(time.Millisecond)
		}
		if observed.active.Load() != 0 || observed.accepted.Load() != 2 {
			return fmt.Errorf("Off shutdown must release two distinct carriers before server shutdown: active=%d accepted=%d", observed.active.Load(), observed.accepted.Load())
		}
		fmt.Fprintln(os.Stderr, "upstream verified: two distinct Off carriers, both closed before server shutdown")
	}
	peer.Stop()
	connections.Range(func(key, _ any) bool { key.(net.Conn).Close(); return true })
	<-stopped
	workers.Wait()
	return nil
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
