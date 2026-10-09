package main

import (
	"fmt"
	"io"
	"net"
	"strings"
	"testing"
)

func TestStoppedPeerRejectsExistingXHTTPClient(t *testing.T) {
	p, err := startPeer(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(p.close)
	client := realityHTTPClient(t, p, "xhttp", map[string]any{"maxConnections": 1})
	checkEcho := func() {
		t.Helper()
		response, err := client.Get("http://" + destination + "/owned-xhttp")
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		body, err := io.ReadAll(response.Body)
		if err != nil || !strings.Contains(string(body), "xray-owned-echo") {
			t.Fatalf("missing owned echo: %v", err)
		}
	}
	checkEcho()
	if err := p.setRunning(false); err != nil {
		t.Fatal(err)
	}
	before := p.count.Load()
	response, err := client.Get("http://" + destination + "/peer-stopped")
	if err == nil {
		defer response.Body.Close()
		t.Errorf("stopped peer accepted existing xHTTP client: HTTP %d", response.StatusCode)
	}
	if got := p.count.Load(); got != before {
		t.Fatalf("stopped peer receipt count changed: %d -> %d", before, got)
	}
	if err := p.setRunning(true); err != nil {
		t.Fatal(err)
	}
	checkEcho()
}

func TestPeerRestartFailureClosesPartialIngressAndCore(t *testing.T) {
	p, err := startPeer(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(p.close)
	if err := p.setRunning(false); err != nil {
		t.Fatal(err)
	}
	occupied, err := net.Listen("tcp4", net.JoinHostPort(p.options.BindHost, fmt.Sprint(p.manifest.XHTTPPort)))
	if err != nil {
		t.Fatal(err)
	}
	defer occupied.Close()
	if err := p.setRunning(true); err == nil {
		t.Fatal("restart ignored occupied xHTTP port")
	}
	if p.instance != nil || len(p.ingress) != 0 {
		t.Fatal("failed restart retained runtime ownership")
	}
	for _, address := range append([]string{net.JoinHostPort(p.options.BindHost, fmt.Sprint(p.manifest.TCPPort))}, p.backends...) {
		listener, err := net.Listen("tcp4", address)
		if err != nil {
			t.Fatalf("failed restart retained listener %s: %v", address, err)
		}
		_ = listener.Close()
	}
	_ = occupied.Close()
	if err := p.setRunning(true); err != nil {
		t.Fatal(err)
	}
	if err := p.setRunning(true); err != nil {
		t.Fatal(err)
	}
	if _, err := p.exchange("xhttp", false, destination); err != nil {
		t.Fatal(err)
	}
	if err := p.setRunning(false); err != nil {
		t.Fatal(err)
	}
	if err := p.setRunning(false); err != nil {
		t.Fatal(err)
	}
}
