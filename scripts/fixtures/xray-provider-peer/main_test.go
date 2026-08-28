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

	"golang.org/x/net/dns/dnsmessage"
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
	var dnsPort int
	if err := json.Unmarshal(manifest["dnsPort"], &dnsPort); err != nil || dnsPort < 1 || dnsPort > 65535 {
		t.Fatal("manifest must expose a reachable owned DNS port")
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

func TestIndependentPeerRoutesOwnedDNSOverApprovedTransports(t *testing.T) {
	p, err := startPeer(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer p.close()
	control := httptest.NewServer(p.controlHandler())
	defer control.Close()
	client := &http.Client{Timeout: 2 * time.Second}
	if receipts := readDNSReceipts(t, client, control.URL+"/dns-receipts"); receipts.Count != 0 {
		t.Fatalf("unexpected initial DNS receipts: %+v", receipts)
	}
	for _, network := range []string{"tcp", "xhttp"} {
		t.Run(network, func(t *testing.T) {
			answer, err := p.exchangeDNS(network, "owned.test.")
			if err != nil {
				t.Fatal(err)
			}
			if answer.String() != "192.0.2.77" {
				t.Fatalf("unexpected DNS answer: %s", answer)
			}
		})
	}
	receipts := readDNSReceipts(t, client, control.URL+"/dns-receipts")
	if receipts.Count != 2 || receipts.LastQuery != "owned.test." {
		t.Fatalf("DNS receipts must record both owned queries, got %+v", receipts)
	}
	if count := readReceiptCount(t, client, control.URL+"/receipts"); count != 0 {
		t.Fatalf("DNS traffic must not change provider echo receipts, got %d", count)
	}
}

func TestOwnedDNSResponderRejectsUnknownAndMalformedQueries(t *testing.T) {
	p, err := startPeer(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer p.close()
	address := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: p.manifest.DNSPort}
	answer, err := directDNSQuery(address, mustDNSQuery(t, "owned.test."))
	if err != nil {
		t.Fatal(err)
	}
	if parsed, err := parseDNSAnswer(answer, "owned.test."); err != nil || parsed.String() != "192.0.2.77" {
		t.Fatalf("unexpected direct DNS answer: %s %v", parsed, err)
	}
	if p.dnsCount.Load() != 1 {
		t.Fatalf("owned query must record one DNS receipt, got %d", p.dnsCount.Load())
	}
	updateQuery := mustDNSQuery(t, "owned.test.")
	updateQuery[2] |= 5 << 3 // DNS UPDATE opcode is not an ordinary QUERY.
	for name, payload := range map[string][]byte{
		"unknown":   mustDNSQuery(t, "unknown.test."),
		"malformed": {0xde, 0xad, 0xbe, 0xef},
		"non-query": updateQuery,
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := directDNSQuery(address, payload); err == nil {
				t.Fatal("refused DNS payload unexpectedly received a response")
			}
		})
	}
	if p.dnsCount.Load() != 1 {
		t.Fatalf("refused DNS payloads must not record receipts, got %d", p.dnsCount.Load())
	}
}

func TestOwnedDNSResponderCloseReleasesPort(t *testing.T) {
	p, err := startPeer(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	port := p.manifest.DNSPort
	p.close()
	listener, err := net.ListenPacket("udp4", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		t.Fatalf("DNS listener port was not released after peer close: %v", err)
	}
	_ = listener.Close()
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

type dnsReceipts struct {
	Count     int64  `json:"count"`
	LastQuery string `json:"lastQuery"`
}

func readDNSReceipts(t *testing.T, client *http.Client, endpoint string) dnsReceipts {
	t.Helper()
	response, err := client.Get(endpoint)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("DNS receipt endpoint status: %d", response.StatusCode)
	}
	var receipts dnsReceipts
	if err := json.NewDecoder(response.Body).Decode(&receipts); err != nil {
		t.Fatal(err)
	}
	return receipts
}

func (p *peer) exchangeDNS(network string, name string) (net.IP, error) {
	return p.exchangeDNSQuery(network, name, false)
}

func (p *peer) exchangeDNSQuery(network string, name string, wrongIdentity bool) (net.IP, error) {
	reservation, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
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
		"log": map[string]any{"loglevel": "none"},
		"inbounds": []any{map[string]any{
			"listen": "127.0.0.1", "port": socksPort, "protocol": "socks",
			"settings": map[string]any{"auth": "noauth", "udp": true},
		}},
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
		return nil, err
	}
	defer instance.Close()
	conn, err := net.DialTimeout("tcp4", fmt.Sprintf("127.0.0.1:%d", socksPort), 2*time.Second)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(4 * time.Second))
	if _, err = conn.Write([]byte{5, 1, 0}); err != nil {
		return nil, err
	}
	greeting := make([]byte, 2)
	if _, err = io.ReadFull(conn, greeting); err != nil {
		return nil, err
	}
	if !bytes.Equal(greeting, []byte{5, 0}) {
		return nil, fmt.Errorf("SOCKS greeting rejected")
	}
	if _, err = conn.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		return nil, err
	}
	bind, err := readSocksAddress(conn)
	if err != nil {
		return nil, err
	}
	packet, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	defer packet.Close()
	if err := packet.SetDeadline(time.Now().Add(4 * time.Second)); err != nil {
		return nil, err
	}
	query, err := buildDNSQuery(name)
	if err != nil {
		return nil, err
	}
	payload := append([]byte{0, 0, 0, 1, 192, 0, 2, 53, 0, 53}, query...)
	if _, err := packet.WriteTo(payload, bind); err != nil {
		return nil, err
	}
	buffer := make([]byte, 512)
	n, _, err := packet.ReadFrom(buffer)
	if err != nil {
		return nil, err
	}
	dnsPayload, err := stripSocksUDPHeader(buffer[:n])
	if err != nil {
		return nil, err
	}
	return parseDNSAnswer(dnsPayload, name)
}

func readSocksAddress(reader io.Reader) (*net.UDPAddr, error) {
	header := make([]byte, 4)
	if _, err := io.ReadFull(reader, header); err != nil {
		return nil, err
	}
	if header[1] != 0 {
		return nil, fmt.Errorf("SOCKS UDP associate rejected")
	}
	var host net.IP
	switch header[3] {
	case 1:
		host = make(net.IP, 4)
		if _, err := io.ReadFull(reader, host); err != nil {
			return nil, err
		}
	case 4:
		host = make(net.IP, 16)
		if _, err := io.ReadFull(reader, host); err != nil {
			return nil, err
		}
	default:
		return nil, fmt.Errorf("unexpected SOCKS address type %d", header[3])
	}
	port := []byte{0, 0}
	if _, err := io.ReadFull(reader, port); err != nil {
		return nil, err
	}
	if host.IsUnspecified() {
		host = net.IPv4(127, 0, 0, 1)
	}
	return &net.UDPAddr{IP: host, Port: int(port[0])<<8 | int(port[1])}, nil
}

func mustDNSQuery(t *testing.T, name string) []byte {
	t.Helper()
	query, err := buildDNSQuery(name)
	if err != nil {
		t.Fatal(err)
	}
	return query
}

func directDNSQuery(address *net.UDPAddr, query []byte) ([]byte, error) {
	packet, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	defer packet.Close()
	if err := packet.SetDeadline(time.Now().Add(200 * time.Millisecond)); err != nil {
		return nil, err
	}
	if _, err := packet.WriteTo(query, address); err != nil {
		return nil, err
	}
	buffer := make([]byte, 512)
	n, _, err := packet.ReadFrom(buffer)
	if err != nil {
		return nil, err
	}
	return buffer[:n], nil
}

func buildDNSQuery(name string) ([]byte, error) {
	message := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: 0x1234, RecursionDesired: true},
		Questions: []dnsmessage.Question{{Name: dnsmessage.MustNewName(name), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}},
	}
	return message.Pack()
}

func stripSocksUDPHeader(packet []byte) ([]byte, error) {
	if len(packet) < 10 || packet[2] != 0 {
		return nil, fmt.Errorf("invalid SOCKS UDP packet")
	}
	length := 0
	switch packet[3] {
	case 1:
		length = 10
	case 4:
		length = 22
	default:
		return nil, fmt.Errorf("unexpected SOCKS UDP address type %d", packet[3])
	}
	if len(packet) < length {
		return nil, fmt.Errorf("short SOCKS UDP packet")
	}
	return packet[length:], nil
}

func parseDNSAnswer(payload []byte, name string) (net.IP, error) {
	var message dnsmessage.Message
	if err := message.Unpack(payload); err != nil {
		return nil, err
	}
	if message.Header.ID != 0x1234 || message.Header.RCode != dnsmessage.RCodeSuccess {
		return nil, fmt.Errorf("unexpected DNS response header: id=%d rcode=%v", message.Header.ID, message.Header.RCode)
	}
	expected := dnsmessage.MustNewName(name)
	for _, answer := range message.Answers {
		if answer.Header.Name == expected && answer.Header.Type == dnsmessage.TypeA {
			if body, ok := answer.Body.(*dnsmessage.AResource); ok {
				return net.IPv4(body.A[0], body.A[1], body.A[2], body.A[3]), nil
			}
		}
	}
	return nil, fmt.Errorf("missing A answer for %s", name)
}
