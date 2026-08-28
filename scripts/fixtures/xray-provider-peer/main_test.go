package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"testing"
	"time"
)

func TestDirectSentinelIsReachableWithoutPeer(t *testing.T) {
	p, err := startPeer(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer p.close()
	data, err := json.Marshal(p.manifest)
	if err != nil {
		t.Fatal(err)
	}
	var manifest map[string]json.RawMessage
	if err := json.Unmarshal(data, &manifest); err != nil {
		t.Fatal(err)
	}
	var port int
	if err := json.Unmarshal(manifest["directPort"], &port); err != nil || port < 1 || port > 65535 {
		t.Fatal("manifest must expose a reachable direct-sentinel port")
	}
	client := &http.Client{Timeout: 2 * time.Second}
	response, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/direct", port))
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK || string(body) != "xray-direct-sentinel\n" {
		t.Fatal("direct baseline must return the sentinel response")
	}
	if p.count.Load() != 0 {
		t.Fatal("direct traffic must not increment provider receipts")
	}
}

func TestDirectSentinelReceiptsAreIndependent(t *testing.T) {
	p, err := startPeer(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer p.close()
	control := httptest.NewServer(p.controlHandler())
	defer control.Close()
	client := &http.Client{Timeout: 2 * time.Second}
	if count := readReceiptCount(t, client, control.URL+"/direct-receipts"); count != 0 {
		t.Fatalf("unexpected initial direct receipts: %d", count)
	}
	response, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/direct", p.manifest.DirectPort))
	if err != nil {
		t.Fatal(err)
	}
	_ = response.Body.Close()
	if count := readReceiptCount(t, client, control.URL+"/direct-receipts"); count != 1 {
		t.Fatalf("direct baseline must record one receipt, got %d", count)
	}
	if count := readReceiptCount(t, client, control.URL+"/receipts"); count != 0 {
		t.Fatal("direct baseline changed provider receipts")
	}
	if _, err := p.exchange("tcp", false, destination); err != nil {
		t.Fatal(err)
	}
	if count := readReceiptCount(t, client, control.URL+"/receipts"); count != 1 {
		t.Fatalf("provider baseline must record one receipt, got %d", count)
	}
	if count := readReceiptCount(t, client, control.URL+"/direct-receipts"); count != 1 {
		t.Fatal("provider traffic changed direct receipts")
	}
}

func readReceiptCount(t *testing.T, client *http.Client, endpoint string) int64 {
	t.Helper()
	response, err := client.Get(endpoint)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("receipt endpoint status: %d", response.StatusCode)
	}
	var receipts struct {
		Count int64 `json:"count"`
	}
	if err := json.NewDecoder(response.Body).Decode(&receipts); err != nil {
		t.Fatal(err)
	}
	return receipts.Count
}

func TestIndependentPeerCannotReachDirectSentinel(t *testing.T) {
	p, err := startPeer(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer p.close()
	target := fmt.Sprintf("127.0.0.1:%d", p.manifest.DirectPort)
	client := &http.Client{Timeout: 2 * time.Second}
	response, err := client.Get("http://" + target + "/direct")
	if err != nil {
		t.Fatal(err)
	}
	_ = response.Body.Close()
	if response.StatusCode != http.StatusOK || p.directCount.Load() != 1 {
		t.Fatal("direct sentinel must be reachable before checking peer denial")
	}
	for _, network := range []string{"tcp", "xhttp"} {
		t.Run(network, func(t *testing.T) {
			reply, err := p.exchange(network, false, destination)
			if err != nil || !strings.Contains(reply, "xray-owned-echo") {
				t.Fatal("authorized peer must reach its owned target before checking denial")
			}
			before := p.count.Load()
			reply, err = p.exchange(network, false, target)
			if err == nil || reply != "" {
				t.Fatal("authorized peer must blackhole direct sentinel traffic")
			}
			if p.directCount.Load() != 1 || p.count.Load() != before {
				t.Fatal("peer denial must not change direct or provider receipts")
			}
		})
	}
}

func TestIndependentPeerRoutesBothApprovedTransports(t *testing.T) {
	p, err := startPeer(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer p.close()
	for _, network := range []string{"tcp", "xhttp"} {
		t.Run(network, func(t *testing.T) {
			reply, err := p.exchange(network, false, destination)
			if err != nil {
				t.Fatal(err)
			}
			if !strings.Contains(reply, "xray-owned-echo") {
				t.Fatalf("missing peer echo")
			}
		})
	}
}

func TestIndependentPeerRejectsWrongIdentity(t *testing.T) {
	p, err := startPeer(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer p.close()
	for _, network := range []string{"tcp", "xhttp"} {
		t.Run(network, func(t *testing.T) {
			before := p.count.Load()
			reply, err := p.exchange(network, true, destination)
			if err == nil || strings.Contains(reply, "xray-owned-echo") {
				t.Fatal("unauthorized client reached echo")
			}
			if p.count.Load() != before {
				t.Fatal("unauthorized client changed server receipts")
			}
		})
	}
}

func TestRealityMetadataWaitHonorsCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if err := awaitRealityMetadata(ctx, "127.0.0.1:0"); !errors.Is(err, context.Canceled) {
		t.Fatalf("metadata wait must honor cancellation, got %v", err)
	}
}

func (p *peer) exchange(network string, wrongIdentity bool, target string) (string, error) {
	address, err := netip.ParseAddrPort(target)
	if err != nil || !address.Addr().Is4() {
		return "", fmt.Errorf("test destination must be an IPv4 address and port")
	}
	reservation, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	socksPort := reservation.Addr().(*net.TCPAddr).Port
	port, flow := p.manifest.TCPPort, "xtls-rprx-vision"
	if network == "xhttp" {
		port, flow = p.manifest.XHTTPPort, ""
	}
	id := peerID
	if wrongIdentity {
		id = "00000000-0000-4000-8000-000000000001"
	}
	config := map[string]any{
		"log":      map[string]any{"loglevel": "none"},
		"inbounds": []any{map[string]any{"listen": "127.0.0.1", "port": socksPort, "protocol": "socks", "settings": map[string]any{"auth": "noauth"}}},
		"outbounds": []any{map[string]any{
			"protocol": "vless",
			"settings": map[string]any{"vnext": []any{map[string]any{"address": "127.0.0.1", "port": port, "users": []any{map[string]any{"id": id, "flow": flow, "encryption": "none"}}}}},
			"streamSettings": map[string]any{
				"network": network, "security": "reality",
				"realitySettings": map[string]any{"publicKey": p.manifest.PublicKey, "serverName": serverName, "shortId": "ab12", "fingerprint": "chrome"},
				"xhttpSettings":   map[string]any{"path": "/owned-xhttp", "mode": "auto"},
			},
		}},
	}
	_ = reservation.Close()
	instance, err := startInstance(config)
	if err != nil {
		return "", err
	}
	defer instance.Close()
	conn, err := net.DialTimeout("tcp4", fmt.Sprintf("127.0.0.1:%d", socksPort), 2*time.Second)
	if err != nil {
		return "", err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(4 * time.Second))
	if _, err = conn.Write([]byte{5, 1, 0}); err != nil {
		return "", err
	}
	greeting := make([]byte, 2)
	if _, err = io.ReadFull(conn, greeting); err != nil {
		return "", err
	}
	if !bytes.Equal(greeting, []byte{5, 0}) {
		return "", fmt.Errorf("SOCKS greeting rejected")
	}
	ip := address.Addr().As4()
	if _, err = conn.Write([]byte{5, 1, 0, 1, ip[0], ip[1], ip[2], ip[3], byte(address.Port() >> 8), byte(address.Port())}); err != nil {
		return "", err
	}
	reply := make([]byte, 10)
	if _, err = io.ReadFull(conn, reply); err != nil {
		return "", err
	}
	if reply[1] != 0 {
		return "", fmt.Errorf("SOCKS connection rejected")
	}
	if _, err = io.WriteString(conn, "GET /owned HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n"); err != nil {
		return "", err
	}
	response, err := http.ReadResponse(bufio.NewReader(conn), nil)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	return string(body), err
}
