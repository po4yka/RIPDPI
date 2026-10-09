// Independent loopback-only peer for Android Xray provider acceptance.
// Private REALITY keys exist only in memory. No public DNS or upstream servers are used.
package main

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/signal"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/xtls/reality"
	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
	"golang.org/x/net/dns/dnsmessage"
)

const peerID = "550e8400-e29b-41d4-a716-446655440000"
const serverName = "fixture.test"
const destination = "192.0.2.77:80"
const ownedDNSName = "owned.test."

type peerManifest struct {
	Version     string `json:"version"`
	RunID       string `json:"runId"`
	TCPPort     int    `json:"tcpPort"`
	XHTTPPort   int    `json:"xhttpPort"`
	DirectPort  int    `json:"directPort"`
	DNSPort     int    `json:"dnsPort,omitempty"`
	DNSHTTPPort int    `json:"dnsHttpPort"`
	PublicKey   string `json:"publicKey"`
	UUID        string `json:"uuid"`
}

type peer struct {
	manifest     peerManifest
	count        atomic.Int64
	directCount  atomic.Int64
	dnsCount     atomic.Int64
	dnsHTTPCount atomic.Int64
	dnsLastQuery atomic.Value
	requests     requestReceipts
	closers      []io.Closer
	options      peerOptions
	instanceMu   sync.Mutex
	instance     *core.Instance
	config       any
	ingress      []*tcpIngress
	backends     []string
	epoch        int
}

type peerOptions struct {
	BindHost      string
	AdvertiseHost string
	RunID         string
	Debug         bool
}

func validateLocalHost(host string) error {
	address, err := netip.ParseAddr(host)
	if err != nil || !address.Is4() || (!address.IsPrivate() && !address.IsLoopback()) {
		return fmt.Errorf("expected a numeric loopback or private IPv4 address")
	}
	return nil
}

func startPeer(ctx context.Context) (*peer, error) {
	return startPeerWithOptions(ctx, peerOptions{BindHost: "127.0.0.1", AdvertiseHost: "10.0.2.2"})
}

func startPeerWithOptions(ctx context.Context, options peerOptions) (*peer, error) {
	if err := validateLocalHost(options.BindHost); err != nil {
		return nil, err
	}
	if err := validateLocalHost(options.AdvertiseHost); err != nil {
		return nil, err
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	p := &peer{requests: requestReceipts{startedAt: time.Now()}, options: options, epoch: 1}
	ready := false
	defer func() {
		if !ready {
			p.close()
		}
	}()
	private, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	certificate, err := decoyCertificate()
	if err != nil {
		return nil, err
	}
	decoyListener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	decoy := &http.Server{
		Handler:           http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNotFound) }),
		TLSConfig:         &tls.Config{MinVersion: tls.VersionTLS13, Certificates: []tls.Certificate{certificate}},
		ReadHeaderTimeout: 3 * time.Second,
	}
	p.closers = append(p.closers, decoy)
	go func() { _ = decoy.ServeTLS(decoyListener, "", "") }()
	echoListener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	echo := &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
			p.count.Add(1)
			p.requests.record("Provider", request.URL.Path)
			w.Header().Set("Connection", "close")
			_, _ = io.WriteString(w, "xray-owned-echo\n")
		}),
		ReadHeaderTimeout: 3 * time.Second,
	}
	p.closers = append(p.closers, echo)
	go func() { _ = echo.Serve(echoListener) }()
	directListener, err := net.Listen("tcp4", net.JoinHostPort(options.BindHost, "0"))
	if err != nil {
		return nil, err
	}
	direct := &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
			p.directCount.Add(1)
			p.requests.record("Direct", request.URL.Path)
			w.Header().Set("Connection", "close")
			_, _ = io.WriteString(w, "xray-direct-sentinel\n")
		}),
		ReadHeaderTimeout: 3 * time.Second,
	}
	p.closers = append(p.closers, direct)
	go func() { _ = direct.Serve(directListener) }()
	dnsListener, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	p.closers = append(p.closers, dnsListener)
	go p.serveOwnedDNS(dnsListener)
	dnsHTTPListener, err := p.startOwnedDNSHTTP()
	if err != nil {
		return nil, err
	}
	// Hold both reservations simultaneously; never derive an adjacent port.
	tcp, err := net.Listen("tcp4", net.JoinHostPort(options.BindHost, "0"))
	if err != nil {
		return nil, err
	}
	defer func() {
		if !ready {
			_ = tcp.Close()
		}
	}()
	xhttp, err := net.Listen("tcp4", net.JoinHostPort(options.BindHost, "0"))
	if err != nil {
		return nil, err
	}
	defer func() {
		if !ready {
			_ = xhttp.Close()
		}
	}()
	// Private core listeners are distinct from the published ingress ports.
	privateTCP, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	defer privateTCP.Close()
	privateXHTTP, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	defer privateXHTTP.Close()
	p.backends = []string{privateTCP.Addr().String(), privateXHTTP.Addr().String()}
	p.manifest = peerManifest{
		Version:     core.Version(),
		RunID:       options.RunID,
		TCPPort:     tcp.Addr().(*net.TCPAddr).Port,
		XHTTPPort:   xhttp.Addr().(*net.TCPAddr).Port,
		DirectPort:  directListener.Addr().(*net.TCPAddr).Port,
		DNSPort:     dnsListener.LocalAddr().(*net.UDPAddr).Port,
		DNSHTTPPort: dnsHTTPListener.Addr().(*net.TCPAddr).Port,
		PublicKey:   base64.RawURLEncoding.EncodeToString(private.PublicKey().Bytes()),
		UUID:        peerID,
	}
	inbounds := []any{}
	for _, network := range []string{"tcp", "xhttp"} {
		port, flow := privateTCP.Addr().(*net.TCPAddr).Port, "xtls-rprx-vision"
		if network == "xhttp" {
			port, flow = privateXHTTP.Addr().(*net.TCPAddr).Port, ""
		}
		inbounds = append(inbounds, map[string]any{
			"tag": network, "listen": "127.0.0.1", "port": port, "protocol": "vless",
			"settings": map[string]any{"decryption": "none", "clients": []any{map[string]any{"id": peerID, "flow": flow}}},
			"streamSettings": map[string]any{
				"network": network, "security": "reality",
				"realitySettings": map[string]any{
					"target": decoyListener.Addr().String(), "serverNames": []string{serverName},
					"privateKey": base64.RawURLEncoding.EncodeToString(private.Bytes()), "shortIds": []string{"ab12"},
				},
				"xhttpSettings": map[string]any{"path": "/owned-xhttp", "mode": "auto"},
			},
		})
	}
	logLevel := "none"
	if options.Debug {
		logLevel = "debug"
	}
	config := map[string]any{
		"log": map[string]any{"loglevel": logLevel}, "inbounds": inbounds,
		"outbounds": []any{
			map[string]any{"tag": "deny", "protocol": "blackhole"},
			map[string]any{"tag": "owned-echo", "protocol": "freedom", "settings": map[string]any{"redirect": echoListener.Addr().String()}},
			map[string]any{"tag": "owned-dns", "protocol": "freedom", "settings": map[string]any{"redirect": dnsListener.LocalAddr().String()}},
			map[string]any{"tag": "owned-doh", "protocol": "freedom", "settings": map[string]any{"redirect": dnsHTTPListener.Addr().String()}},
		},
		"routing": map[string]any{"domainStrategy": "AsIs", "rules": []any{
			map[string]any{"type": "field", "inboundTag": []string{"tcp", "xhttp"}, "network": "tcp", "ip": []string{"192.0.2.77/32"}, "port": "80", "outboundTag": "owned-echo"},
			map[string]any{"type": "field", "inboundTag": []string{"tcp", "xhttp"}, "network": "udp", "ip": []string{"192.0.2.53/32"}, "port": "53", "outboundTag": "owned-dns"},
			// Host tests and the emulator alias reach only this owned HTTP port.
			map[string]any{"type": "field", "inboundTag": []string{"tcp", "xhttp"}, "network": "tcp", "ip": []string{"127.0.0.1/32", options.AdvertiseHost + "/32"}, "port": fmt.Sprint(p.manifest.DNSHTTPPort), "outboundTag": "owned-doh"},
		}},
	}
	_ = privateTCP.Close()
	_ = privateXHTTP.Close()
	instance, err := startInstance(config)
	if err != nil {
		return nil, err
	}
	p.instance = instance
	p.config = config
	p.ingress = []*tcpIngress{startTCPIngress(p.traceIngress(tcp, "tcp", p.epoch), p.backends[0]), startTCPIngress(p.traceIngress(xhttp, "xhttp", p.epoch), p.backends[1])}
	if err := awaitRealityMetadata(ctx, decoyListener.Addr().String()); err != nil {
		return nil, err
	}
	p.traceLifecycle("ready")
	ready = true
	return p, nil
}

func (p *peer) serveOwnedDNS(conn net.PacketConn) {
	buffer := make([]byte, 512)
	for {
		n, addr, err := conn.ReadFrom(buffer)
		if err != nil {
			return
		}
		response, query, ok := buildOwnedDNSResponse(buffer[:n])
		if !ok {
			continue
		}
		p.dnsCount.Add(1)
		p.dnsLastQuery.Store(query)
		_, _ = conn.WriteTo(response, addr)
	}
}

func buildOwnedDNSResponse(packet []byte) ([]byte, string, bool) {
	var query dnsmessage.Message
	if err := query.Unpack(packet); err != nil {
		return nil, "", false
	}
	if query.Header.Response || query.Header.OpCode != 0 || len(query.Questions) != 1 || len(query.Answers) != 0 {
		return nil, "", false
	}
	question := query.Questions[0]
	if question.Name.String() != ownedDNSName || question.Type != dnsmessage.TypeA || question.Class != dnsmessage.ClassINET {
		return nil, "", false
	}
	response := dnsmessage.Message{
		Header: dnsmessage.Header{
			ID:                 query.Header.ID,
			Response:           true,
			Authoritative:      true,
			RecursionDesired:   query.Header.RecursionDesired,
			RecursionAvailable: false,
			RCode:              dnsmessage.RCodeSuccess,
		},
		Questions: query.Questions,
		Answers: []dnsmessage.Resource{{
			Header: dnsmessage.ResourceHeader{Name: question.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 30},
			Body:   &dnsmessage.AResource{A: [4]byte{192, 0, 2, 77}},
		}},
	}
	packed, err := response.Pack()
	if err != nil {
		return nil, "", false
	}
	return packed, question.Name.String(), true
}

func awaitRealityMetadata(ctx context.Context, target string) error {
	// REALITY starts these probes asynchronously. Serving authenticated clients before
	// they finish can enter its five-second polling sleep and exceed the decoy deadline.
	ticker := time.NewTicker(5 * time.Millisecond)
	defer ticker.Stop()
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	for {
		if err := ctx.Err(); err != nil {
			return fmt.Errorf("owned REALITY metadata did not become ready: %w", err)
		}
		ready := true
		for alpn := range 3 {
			value, _ := reality.GlobalPostHandshakeRecordsLens.Load(fmt.Sprintf("%s %s %d", target, serverName, alpn))
			if _, ok := value.([]int); !ok {
				ready = false
			}
		}
		if ready {
			return nil
		}
		select {
		case <-ticker.C:
		case <-ctx.Done():
			return fmt.Errorf("owned REALITY metadata did not become ready: %w", ctx.Err())
		}
	}
}

func startInstance(config any) (*core.Instance, error) {
	data, err := json.Marshal(config)
	if err != nil {
		return nil, err
	}
	parsed, err := core.LoadConfig("json", bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	instance, err := core.New(parsed)
	if err != nil {
		return nil, err
	}
	if err := instance.Start(); err != nil {
		_ = instance.Close()
		return nil, err
	}
	return instance, nil
}

func decoyCertificate() (tls.Certificate, error) {
	private, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, err
	}
	template := &x509.Certificate{
		SerialNumber: big.NewInt(1), DNSNames: []string{serverName},
		NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &private.PublicKey, private)
	if err != nil {
		return tls.Certificate{}, err
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: private}, nil
}

func (p *peer) setRunning(running bool) error {
	p.instanceMu.Lock()
	defer p.instanceMu.Unlock()
	if !running {
		p.traceLifecycle("stop-begin")
		var failures []error
		for _, ingress := range p.ingress {
			failures = append(failures, ingress.Close())
		}
		p.ingress = nil
		if p.instance != nil {
			failures = append(failures, p.instance.Close())
			p.instance = nil
		}
		p.traceLifecycle("stop-complete")
		return errors.Join(failures...)
	}
	if p.instance != nil {
		return nil
	}
	p.traceLifecycle("restart-begin")
	instance, err := startInstance(p.config)
	if err != nil {
		return err
	}
	var ingress []*tcpIngress
	for i, port := range []int{p.manifest.TCPPort, p.manifest.XHTTPPort} {
		listener, err := net.Listen("tcp4", net.JoinHostPort(p.options.BindHost, fmt.Sprint(port)))
		if err != nil {
			for _, opened := range ingress {
				_ = opened.Close()
			}
			_ = instance.Close()
			return err
		}
		ingress = append(ingress, startTCPIngress(p.traceIngress(listener, []string{"tcp", "xhttp"}[i], p.epoch+1), p.backends[i]))
	}
	p.instance = instance
	p.ingress = ingress
	p.epoch++
	p.traceLifecycle("restart-ready")
	return nil
}

// Only the listener is decorated. Accept returns the original *net.TCPConn,
// preserving ingress half-close and all real transport behavior.
type tracedIngressListener struct {
	net.Listener
	transport string
	epoch     int
	accepted  atomic.Int64
}

func (listener *tracedIngressListener) Accept() (net.Conn, error) {
	conn, err := listener.Listener.Accept()
	if err == nil {
		log.Printf("peer-trace ingress-accept transport=%s epoch=%d ordinal=%d", listener.transport, listener.epoch, listener.accepted.Add(1))
	}
	return conn, err
}

func (p *peer) traceIngress(listener net.Listener, transport string, epoch int) net.Listener {
	if !p.options.Debug {
		return listener
	}
	return &tracedIngressListener{Listener: listener, transport: transport, epoch: epoch}
}

func (p *peer) traceLifecycle(stage string) {
	if p.options.Debug {
		log.Printf("peer-trace lifecycle stage=%s epoch=%d", stage, p.epoch)
	}
}

func (p *peer) close() {
	_ = p.setRunning(false)
	for i := len(p.closers) - 1; i >= 0; i-- {
		_ = p.closers[i].Close()
	}
}

func (p *peer) controlHandler() http.Handler {
	mux := http.NewServeMux()
	for path, running := range map[string]bool{"/peer/start": true, "/peer/stop": false} {
		mux.HandleFunc("POST "+path, func(w http.ResponseWriter, _ *http.Request) {
			if err := p.setRunning(running); err != nil {
				http.Error(w, "peer transition failed", http.StatusInternalServerError)
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]bool{"running": running})
		})
	}
	mux.HandleFunc("GET /manifest", func(w http.ResponseWriter, _ *http.Request) { _ = json.NewEncoder(w).Encode(p.manifest) })
	mux.HandleFunc("GET /receipts", func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]int64{"count": p.count.Load()})
	})
	mux.HandleFunc("GET /direct-receipts", func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]int64{"count": p.directCount.Load()})
	})
	mux.HandleFunc("GET /dns-receipts", func(w http.ResponseWriter, _ *http.Request) {
		lastQuery, _ := p.dnsLastQuery.Load().(string)
		_ = json.NewEncoder(w).Encode(map[string]any{"count": p.dnsCount.Load(), "lastQuery": lastQuery})
	})
	mux.HandleFunc("GET /dns-http-receipts", func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"count": p.dnsHTTPCount.Load()})
	})
	mux.HandleFunc("GET /request-receipts", func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(p.requests.snapshot())
	})
	return mux
}

// Published data and management ports share the VM ingress address even when
// their listeners bind separate namespace addresses.
func (p *peer) listenControl(host string, port int) (net.Listener, error) {
	for attempt := 0; attempt < 16; attempt++ {
		listener, err := net.Listen("tcp4", net.JoinHostPort(host, fmt.Sprint(port)))
		if err != nil {
			return nil, err
		}
		selected := listener.Addr().(*net.TCPAddr).Port
		if selected != p.manifest.TCPPort && selected != p.manifest.XHTTPPort &&
			selected != p.manifest.DirectPort && selected != p.manifest.DNSHTTPPort {
			return listener, nil
		}
		_ = listener.Close()
		if port != 0 {
			return nil, fmt.Errorf("control port overlaps a published data port")
		}
	}
	return nil, fmt.Errorf("no distinct control port available")
}

func main() {
	readyFile := flag.String("ready-file", "", "Exclusive path for the public readiness manifest")
	bindHost := flag.String("bind-host", "127.0.0.1", "Private IPv4 address for data listeners")
	advertiseHost := flag.String("advertise-host", "10.0.2.2", "Private IPv4 address used by the Android client")
	controlHost := flag.String("control-host", "127.0.0.1", "Private IPv4 address on the separate management path")
	controlPort := flag.Int("control-port", 0, "Management port; zero allocates a port")
	debug := flag.Bool("debug", false, "Enable local native and ingress diagnostic logs")
	runID := flag.String("run-id", "", "Acceptance run identity")
	flag.Parse()
	if validateLocalHost(*controlHost) != nil || *controlPort < 0 || *controlPort > 65535 {
		fmt.Fprintln(os.Stderr, "invalid management address or port")
		os.Exit(2)
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	output := os.Stdout
	if *readyFile != "" {
		var err error
		output, err = os.OpenFile(*readyFile, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
		if err != nil {
			fmt.Fprintln(os.Stderr, "owned readiness manifest creation failed")
			os.Exit(1)
		}
		defer output.Close()
	}
	p, err := startPeerWithOptions(ctx, peerOptions{BindHost: *bindHost, AdvertiseHost: *advertiseHost, RunID: *runID, Debug: *debug})
	if err != nil {
		fmt.Fprintln(os.Stderr, "owned Xray peer startup failed")
		os.Exit(1)
	}
	defer p.close()
	listener, err := p.listenControl(*controlHost, *controlPort)
	if err != nil {
		fmt.Fprintln(os.Stderr, "owned control listener failed")
		os.Exit(1)
	}
	control := &http.Server{Handler: p.controlHandler(), ReadHeaderTimeout: 3 * time.Second}
	defer control.Close()
	go func() { _ = control.Serve(listener) }()
	if err := json.NewEncoder(output).Encode(map[string]any{"controlPort": listener.Addr().(*net.TCPAddr).Port, "version": core.Version()}); err != nil {
		fmt.Fprintln(os.Stderr, "owned readiness manifest write failed")
		return
	}
	<-ctx.Done()
}
