package main

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"time"
)

// tcpIngress owns the wire connections of this functional test fixture. The
// pinned Xray xHTTP listener closes its listening socket but leaves HTTP/2
// sessions alive. Closing these raw TCP connections makes peer/stop a real
// transport outage without replacing Xray protocol handling or authentication.
type tcpIngress struct {
	listener    net.Listener
	backend     string
	ctx         context.Context
	cancel      context.CancelFunc
	mu          sync.Mutex
	closed      bool
	connections map[*ingressConnection]struct{}
	accepted    chan struct{}
	workers     sync.WaitGroup
	stop        sync.Once
	closeErr    error
}

type ingressConnection struct {
	client   net.Conn
	upstream net.Conn // guarded by tcpIngress.mu
}

func startTCPIngress(listener net.Listener, backend string) *tcpIngress {
	ctx, cancel := context.WithCancel(context.Background())
	g := &tcpIngress{listener: listener, backend: backend, ctx: ctx, cancel: cancel,
		connections: make(map[*ingressConnection]struct{}), accepted: make(chan struct{})}
	go g.accept()
	return g
}

func (g *tcpIngress) accept() {
	defer close(g.accepted)
	for {
		client, err := g.listener.Accept()
		if err != nil {
			return
		}
		connection := &ingressConnection{client: client}
		g.mu.Lock()
		if g.closed {
			g.mu.Unlock()
			_ = client.Close()
			return
		}
		g.connections[connection] = struct{}{}
		g.workers.Add(1)
		g.mu.Unlock()
		go g.forward(connection)
	}
}

func (g *tcpIngress) forward(connection *ingressConnection) {
	defer g.workers.Done()
	defer func() {
		g.mu.Lock()
		delete(g.connections, connection)
		_ = connection.client.Close()
		if connection.upstream != nil {
			_ = connection.upstream.Close()
		}
		g.mu.Unlock()
	}()
	upstream, err := (&net.Dialer{Timeout: 3 * time.Second}).DialContext(g.ctx, "tcp4", g.backend)
	if err != nil {
		return
	}
	g.mu.Lock()
	if g.closed {
		g.mu.Unlock()
		_ = upstream.Close()
		return
	}
	connection.upstream = upstream
	g.mu.Unlock()
	copied := make(chan struct{})
	go func() {
		_, _ = io.Copy(upstream, connection.client)
		closeWrite(upstream)
		close(copied)
	}()
	_, _ = io.Copy(connection.client, upstream)
	closeWrite(connection.client)
	<-copied
}

func closeWrite(conn net.Conn) {
	if tcp, ok := conn.(*net.TCPConn); ok {
		_ = tcp.CloseWrite()
	}
}

func (g *tcpIngress) Close() error {
	g.stop.Do(func() {
		g.mu.Lock()
		g.closed = true
		g.cancel()
		g.closeErr = g.listener.Close()
		if errors.Is(g.closeErr, net.ErrClosed) {
			g.closeErr = nil
		}
		for connection := range g.connections {
			_ = connection.client.Close()
			if connection.upstream != nil {
				_ = connection.upstream.Close()
			}
		}
		g.mu.Unlock()
		// Registration and worker Add use the same lock as closed. Neither can
		// occur after this point, including an Accept that raced with Close.
		<-g.accepted
		g.workers.Wait()
	})
	return g.closeErr
}
