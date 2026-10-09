package main

import (
	"io"
	"net"
	"sync"
	"testing"
	"time"
)

func listenLoopback(t *testing.T) net.Listener {
	t.Helper()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	return listener
}

func assertConnectionClosed(t *testing.T, conn net.Conn) {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(time.Second))
	_, err := conn.Read(make([]byte, 1))
	if err == nil {
		t.Fatal("connection remains readable")
	}
	if timeout, ok := err.(net.Error); ok && timeout.Timeout() {
		t.Fatal("connection was not closed before stop returned")
	}
}

func TestIngressStopClosesBothEndsAndJoinsWorkers(t *testing.T) {
	backend := listenLoopback(t)
	listener := listenLoopback(t)
	gate := startTCPIngress(listener, backend.Addr().String())
	t.Cleanup(func() { _ = gate.Close() })
	client, err := net.Dial("tcp4", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = backend.(*net.TCPListener).SetDeadline(time.Now().Add(time.Second))
	upstream, err := backend.Accept()
	if err != nil {
		t.Fatal(err)
	}
	defer upstream.Close()
	_ = client.SetDeadline(time.Now().Add(time.Second))
	_ = upstream.SetDeadline(time.Now().Add(time.Second))
	if _, err := client.Write([]byte("real bytes")); err != nil {
		t.Fatal(err)
	}
	payload := make([]byte, len("real bytes"))
	if _, err := io.ReadFull(upstream, payload); err != nil || string(payload) != "real bytes" {
		t.Fatalf("ingress payload %q: %v", payload, err)
	}
	if _, err := upstream.Write(payload); err != nil {
		t.Fatal(err)
	}
	if _, err := io.ReadFull(client, payload); err != nil {
		t.Fatal(err)
	}
	var stops sync.WaitGroup
	for range 4 {
		stops.Go(func() {
			if err := gate.Close(); err != nil {
				t.Error(err)
			}
		})
	}
	stops.Wait()
	assertConnectionClosed(t, client)
	assertConnectionClosed(t, upstream)
	assertIngressStopped(t, gate)
}

func assertIngressStopped(t *testing.T, gate *tcpIngress) {
	t.Helper()
	select {
	case <-gate.accepted:
	default:
		t.Fatal("accept loop still running")
	}
	gate.mu.Lock()
	defer gate.mu.Unlock()
	if !gate.closed || len(gate.connections) != 0 {
		t.Fatalf("unjoined ingress: closed=%v, connections=%d", gate.closed, len(gate.connections))
	}
}

func TestIngressStopRacesAcceptedConnections(t *testing.T) {
	backend := listenLoopback(t)
	listener := listenLoopback(t)
	gate := startTCPIngress(listener, backend.Addr().String())
	t.Cleanup(func() { _ = gate.Close() })
	start := make(chan struct{})
	var clients sync.WaitGroup
	for range 32 {
		clients.Go(func() {
			<-start
			conn, err := net.DialTimeout("tcp4", listener.Addr().String(), time.Second)
			if err == nil {
				defer conn.Close()
				assertConnectionClosed(t, conn)
			}
		})
	}
	close(start)
	if err := gate.Close(); err != nil {
		t.Fatal(err)
	}
	clients.Wait()
	assertIngressStopped(t, gate)
}

func TestIngressUnavailableBackendClosesClient(t *testing.T) {
	backend := listenLoopback(t)
	_ = backend.Close()
	listener := listenLoopback(t)
	gate := startTCPIngress(listener, backend.Addr().String())
	t.Cleanup(func() { _ = gate.Close() })
	client, err := net.Dial("tcp4", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	assertConnectionClosed(t, client)
	if err := gate.Close(); err != nil {
		t.Fatal(err)
	}
	assertIngressStopped(t, gate)
}
