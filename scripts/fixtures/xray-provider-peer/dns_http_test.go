package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

func TestOwnedDNSHTTPManifestAndRoundTrips(t *testing.T) {
	p, endpoint := startDNSHTTPTestPeer(t)
	assertDNSHTTPReceiptsUnchanged(t, p)
	client := &http.Client{Transport: &http.Transport{Proxy: nil}, Timeout: 2 * time.Second}
	t.Cleanup(client.CloseIdleConnections)
	for _, method := range []string{http.MethodPost, http.MethodGet} {
		for _, question := range []struct {
			name string
			kind dnsmessage.Type
		}{
			{"owned.test.", dnsmessage.TypeA},
			{"background.test.", dnsmessage.TypeA},
			{"background.test.", dnsmessage.TypeAAAA},
		} {
			t.Run(method+"/"+question.name+question.kind.String(), func(t *testing.T) {
				query := dnsHTTPQuery(t, question.name, question.kind)
				first := readDNSHTTPAnswer(t, client, dnsHTTPRequest(t, method, endpoint, query), query)
				second := readDNSHTTPAnswer(t, client, dnsHTTPRequest(t, method, endpoint, query), query)
				if !bytes.Equal(first, second) {
					t.Fatal("owned and background answers must be deterministic")
				}
			})
		}
	}
}

func TestOwnedDNSHTTPRejectsInvalidRequestsWithoutReceiptPollution(t *testing.T) {
	p, endpoint := startDNSHTTPTestPeer(t)
	// Preserve a nonzero UDP receipt and its query across every HTTP rejection.
	answer, err := directDNSQuery(&net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: p.manifest.DNSPort}, mustDNSQuery(t, "owned.test."))
	if err != nil {
		t.Fatal(err)
	}
	if ip, err := parseDNSAnswer(answer, "owned.test."); err != nil || ip.String() != "192.0.2.77" {
		t.Fatalf("UDP baseline failed: %s %v", ip, err)
	}
	assertDNSHTTPReceiptsUnchanged(t, p)
	client := &http.Client{Transport: &http.Transport{Proxy: nil}, Timeout: 2 * time.Second}
	t.Cleanup(client.CloseIdleConnections)
	valid := dnsHTTPQuery(t, "owned.test.", dnsmessage.TypeA)
	var message dnsmessage.Message
	if err := message.Unpack(valid); err != nil {
		t.Fatal(err)
	}
	pack := func(message dnsmessage.Message) []byte {
		t.Helper()
		payload, err := message.Pack()
		if err != nil {
			t.Fatal(err)
		}
		return payload
	}
	response := message
	response.Header.Response = true
	update := message
	update.Header.OpCode = 5
	noQuestion := message
	noQuestion.Questions = nil
	multiple := message
	multiple.Questions = append([]dnsmessage.Question{message.Questions[0]}, message.Questions[0])
	wrongClass := message
	wrongClass.Questions = []dnsmessage.Question{message.Questions[0]}
	wrongClass.Questions[0].Class = dnsmessage.ClassCHAOS
	for _, test := range []struct {
		name        string
		method      string
		payload     []byte
		contentType string
		status      int
	}{
		{"short-message", http.MethodPost, []byte{0xde, 0xad, 0xbe, 0xef}, "application/dns-message", http.StatusBadRequest},
		{"empty-message", http.MethodPost, nil, "application/dns-message", http.StatusBadRequest},
		{"truncated-question", http.MethodPost, valid[:len(valid)-1], "application/dns-message", http.StatusBadRequest},
		{"trailing-data", http.MethodPost, append(append([]byte{}, valid...), 0), "application/dns-message", http.StatusBadRequest},
		{"response-not-query", http.MethodPost, pack(response), "application/dns-message", http.StatusBadRequest},
		{"update-not-query", http.MethodPost, pack(update), "application/dns-message", http.StatusBadRequest},
		{"missing-question", http.MethodPost, pack(noQuestion), "application/dns-message", http.StatusBadRequest},
		{"multiple-questions", http.MethodPost, pack(multiple), "application/dns-message", http.StatusBadRequest},
		{"wrong-class", http.MethodPost, pack(wrongClass), "application/dns-message", http.StatusBadRequest},
		{"malformed-get", http.MethodGet, []byte{0xde, 0xad}, "", http.StatusBadRequest},
		{"wrong-method", http.MethodPut, valid, "application/dns-message", http.StatusMethodNotAllowed},
		{"wrong-content-type", http.MethodPost, valid, "application/json", http.StatusUnsupportedMediaType},
		{"missing-content-type", http.MethodPost, valid, "", http.StatusUnsupportedMediaType},
		// A DNS wire message cannot exceed its 16-bit length bound.
		{"oversized-body", http.MethodPost, bytes.Repeat([]byte{0}, 65536), "application/dns-message", http.StatusRequestEntityTooLarge},
	} {
		t.Run(test.name, func(t *testing.T) {
			assertDNSHTTPReceiptsUnchanged(t, p)
			request := dnsHTTPRequest(t, test.method, endpoint, test.payload)
			request.Header.Set("Content-Type", test.contentType)
			response, err := client.Do(request)
			if err != nil {
				t.Fatal(err)
			}
			defer response.Body.Close()
			if response.StatusCode != test.status {
				t.Fatalf("HTTP status = %d, want %d", response.StatusCode, test.status)
			}
		})
	}
	for _, suffix := range []string{"", "?dns=%%%", "?dns="} {
		t.Run("invalid-get-"+suffix, func(t *testing.T) {
			assertDNSHTTPReceiptsUnchanged(t, p)
			request, err := http.NewRequestWithContext(t.Context(), http.MethodGet, endpoint+suffix, nil)
			if err != nil {
				t.Fatal(err)
			}
			response, err := client.Do(request)
			if err != nil {
				t.Fatal(err)
			}
			defer response.Body.Close()
			if response.StatusCode != http.StatusBadRequest {
				t.Fatalf("invalid GET status = %d, want 400", response.StatusCode)
			}
		})
	}
}

func TestOwnedDNSHTTPRoutesBothTransportsAndRetainsDefaultBlackhole(t *testing.T) {
	p, endpoint := startDNSHTTPTestPeer(t)
	assertDNSHTTPReceiptsUnchanged(t, p)
	var unownedHits atomic.Int64
	unowned := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		unownedHits.Add(1)
		w.WriteHeader(http.StatusNoContent)
	}))
	t.Cleanup(unowned.Close)
	for _, network := range []string{"tcp", "xhttp"} {
		t.Run(network, func(t *testing.T) {
			assertDNSHTTPReceiptsUnchanged(t, p)
			client := dnsHTTPRealityClient(t, p, network)
			for _, method := range []string{http.MethodPost, http.MethodGet} {
				query := dnsHTTPQuery(t, "owned.test.", dnsmessage.TypeA)
				readDNSHTTPAnswer(t, client, dnsHTTPRequest(t, method, endpoint, query), query)
			}
			// Deny both a different port and the owned port on a different IP.
			for _, target := range []string{
				unowned.URL,
				fmt.Sprintf("http://127.0.0.1:%d/direct", p.manifest.DirectPort),
				"http://127.0.0.2:" + strconv.Itoa(dnsHTTPManifestPort(t, p)) + "/dns-query",
			} {
				response, err := client.Get(target)
				if response != nil {
					_ = response.Body.Close()
				}
				if err == nil {
					t.Fatalf("unowned target escaped the default blackhole: %s", target)
				}
			}
			if unownedHits.Load() != 0 {
				t.Fatal("Xray contacted the unowned loopback server")
			}
		})
	}
}

func startDNSHTTPTestPeer(t *testing.T) (*peer, string) {
	t.Helper()
	p, err := startPeer(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(p.close)
	return p, fmt.Sprintf("http://127.0.0.1:%d/dns-query", dnsHTTPManifestPort(t, p))
}

func dnsHTTPManifestPort(t *testing.T, p *peer) int {
	t.Helper()
	data, err := json.Marshal(p.manifest)
	if err != nil {
		t.Fatal(err)
	}
	var manifest map[string]json.RawMessage
	if err := json.Unmarshal(data, &manifest); err != nil {
		t.Fatal(err)
	}
	var port int
	if err := json.Unmarshal(manifest["dnsHttpPort"], &port); err != nil || port < 1 || port > 65535 {
		t.Fatal("manifest must expose REQUIRED dnsHttpPort for the owned loopback DoH capability")
	}
	return port
}

func dnsHTTPQuery(t *testing.T, name string, kind dnsmessage.Type) []byte {
	t.Helper()
	message := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: 0x7a31, RecursionDesired: true},
		Questions: []dnsmessage.Question{{Name: dnsmessage.MustNewName(name), Type: kind, Class: dnsmessage.ClassINET}},
	}
	query, err := message.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return query
}

func dnsHTTPRequest(t *testing.T, method, endpoint string, query []byte) *http.Request {
	t.Helper()
	var body io.Reader = bytes.NewReader(query)
	if method == http.MethodGet {
		endpoint += "?dns=" + base64.RawURLEncoding.EncodeToString(query)
		body = nil
	}
	request, err := http.NewRequestWithContext(t.Context(), method, endpoint, body)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Accept", "application/dns-message")
	if method != http.MethodGet {
		request.Header.Set("Content-Type", "application/dns-message")
	}
	return request
}

func readDNSHTTPAnswer(t *testing.T, client *http.Client, request *http.Request, query []byte) []byte {
	t.Helper()
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK || response.Header.Get("Content-Type") != "application/dns-message" {
		t.Fatalf("DoH response status/type = %d/%q", response.StatusCode, response.Header.Get("Content-Type"))
	}
	payload, err := io.ReadAll(io.LimitReader(response.Body, 65537))
	if err != nil || len(payload) > 65535 {
		t.Fatalf("invalid bounded DoH response: length=%d error=%v", len(payload), err)
	}
	var sent, answer dnsmessage.Message
	if err := sent.Unpack(query); err != nil {
		t.Fatal(err)
	}
	if err := answer.Unpack(payload); err != nil {
		t.Fatalf("invalid DNS-message response: %v", err)
	}
	if !answer.Header.Response || answer.Header.ID != sent.Header.ID || answer.Header.OpCode != 0 ||
		answer.Header.Truncated || answer.Header.RCode != dnsmessage.RCodeSuccess ||
		answer.Header.RecursionDesired != sent.Header.RecursionDesired ||
		len(answer.Questions) != 1 || answer.Questions[0] != sent.Questions[0] {
		t.Fatalf("DNS response must preserve ID/question and successful query framing: %+v", answer)
	}
	question := sent.Questions[0]
	// Background AAAA may be deterministic NODATA; owned A must carry the exact fixture IP.
	if question.Name.String() == "owned.test." && question.Type == dnsmessage.TypeA && len(answer.Answers) != 1 {
		t.Fatal("owned A response must contain exactly one answer")
	}
	for _, resource := range answer.Answers {
		if resource.Header.Name != question.Name || resource.Header.Type != question.Type || resource.Header.Class != question.Class {
			t.Fatalf("answer does not match the DNS question: %+v", resource.Header)
		}
		switch question.Type {
		case dnsmessage.TypeA:
			body, ok := resource.Body.(*dnsmessage.AResource)
			if !ok || (question.Name.String() == "owned.test." && body.A != [4]byte{192, 0, 2, 77}) {
				t.Fatal("invalid A answer or incorrect owned address")
			}
		case dnsmessage.TypeAAAA:
			if _, ok := resource.Body.(*dnsmessage.AAAAResource); !ok {
				t.Fatal("invalid AAAA answer")
			}
		}
	}
	return payload
}

func assertDNSHTTPReceiptsUnchanged(t *testing.T, p *peer) {
	t.Helper()
	provider, direct, udp := p.count.Load(), p.directCount.Load(), p.dnsCount.Load()
	lastQuery, _ := p.dnsLastQuery.Load().(string)
	requests, err := json.Marshal(p.requests.snapshot())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		currentQuery, _ := p.dnsLastQuery.Load().(string)
		currentRequests, err := json.Marshal(p.requests.snapshot())
		if err != nil || p.count.Load() != provider || p.directCount.Load() != direct || p.dnsCount.Load() != udp ||
			currentQuery != lastQuery || !bytes.Equal(currentRequests, requests) {
			t.Error("DoH traffic must not pollute UDP, direct, provider or HTTP request receipts")
		}
	})
}

func dnsHTTPRealityClient(t *testing.T, p *peer, network string) *http.Client {
	t.Helper()
	// Match the existing exchange/exchangeDNS helpers, using the same real Xray
	// instance loader and SOCKS reply parser while allowing DNS-message HTTP bodies.
	reservation, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer reservation.Close()
	socksPort := reservation.Addr().(*net.TCPAddr).Port
	port, flow := p.manifest.TCPPort, "xtls-rprx-vision"
	if network == "xhttp" {
		port, flow = p.manifest.XHTTPPort, ""
	}
	config := map[string]any{
		"log":      map[string]any{"loglevel": "none"},
		"inbounds": []any{map[string]any{"listen": "127.0.0.1", "port": socksPort, "protocol": "socks", "settings": map[string]any{"auth": "noauth"}}},
		"outbounds": []any{map[string]any{
			"protocol": "vless",
			"settings": map[string]any{"vnext": []any{map[string]any{"address": "127.0.0.1", "port": port, "users": []any{map[string]any{"id": peerID, "flow": flow, "encryption": "none"}}}}},
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
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = instance.Close() })
	transport := &http.Transport{Proxy: nil, DisableKeepAlives: true}
	transport.DialContext = func(ctx context.Context, _, target string) (net.Conn, error) {
		host, portText, err := net.SplitHostPort(target)
		if err != nil {
			return nil, err
		}
		ip := net.ParseIP(host).To4()
		targetPort, err := strconv.Atoi(portText)
		if ip == nil || err != nil || targetPort < 1 || targetPort > 65535 {
			return nil, fmt.Errorf("DoH routing test requires a literal IPv4 destination")
		}
		conn, err := (&net.Dialer{Timeout: 2 * time.Second}).DialContext(ctx, "tcp4", fmt.Sprintf("127.0.0.1:%d", socksPort))
		if err != nil {
			return nil, err
		}
		ok := false
		defer func() {
			if !ok {
				_ = conn.Close()
			}
		}()
		if err := conn.SetDeadline(time.Now().Add(4 * time.Second)); err != nil {
			return nil, err
		}
		if _, err := conn.Write([]byte{5, 1, 0}); err != nil {
			return nil, err
		}
		greeting := make([]byte, 2)
		if _, err := io.ReadFull(conn, greeting); err != nil {
			return nil, err
		}
		if !bytes.Equal(greeting, []byte{5, 0}) {
			return nil, fmt.Errorf("SOCKS greeting rejected")
		}
		if _, err := conn.Write([]byte{5, 1, 0, 1, ip[0], ip[1], ip[2], ip[3], byte(targetPort >> 8), byte(targetPort)}); err != nil {
			return nil, err
		}
		if _, err := readSocksAddress(conn); err != nil {
			return nil, err
		}
		ok = true
		return conn, nil
	}
	client := &http.Client{Transport: transport, Timeout: 4 * time.Second}
	t.Cleanup(client.CloseIdleConnections)
	return client
}
