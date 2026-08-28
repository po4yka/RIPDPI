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
	"flag"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/signal"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/xtls/reality"
	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
)

const peerID = "550e8400-e29b-41d4-a716-446655440000"
const serverName = "fixture.test"
const destination = "192.0.2.77:80"

type peerManifest struct {
	TCPPort    int    `json:"tcpPort"`
	XHTTPPort  int    `json:"xhttpPort"`
	DirectPort int    `json:"directPort"`
	PublicKey  string `json:"publicKey"`
	UUID       string `json:"uuid"`
}

type peer struct {
	manifest    peerManifest
	count       atomic.Int64
	directCount atomic.Int64
	closers     []io.Closer
}

func startPeer(ctx context.Context) (*peer, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	p := &peer{}
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
		Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			p.count.Add(1)
			w.Header().Set("Connection", "close")
			_, _ = io.WriteString(w, "xray-owned-echo\n")
		}),
		ReadHeaderTimeout: 3 * time.Second,
	}
	p.closers = append(p.closers, echo)
	go func() { _ = echo.Serve(echoListener) }()
	directListener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	direct := &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			p.directCount.Add(1)
			w.Header().Set("Connection", "close")
			_, _ = io.WriteString(w, "xray-direct-sentinel\n")
		}),
		ReadHeaderTimeout: 3 * time.Second,
	}
	p.closers = append(p.closers, direct)
	go func() { _ = direct.Serve(directListener) }()
	// Hold both reservations simultaneously; never derive an adjacent port.
	tcp, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	defer tcp.Close()
	xhttp, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	defer xhttp.Close()
	p.manifest = peerManifest{
		TCPPort:    tcp.Addr().(*net.TCPAddr).Port,
		XHTTPPort:  xhttp.Addr().(*net.TCPAddr).Port,
		DirectPort: directListener.Addr().(*net.TCPAddr).Port,
		PublicKey:  base64.RawURLEncoding.EncodeToString(private.PublicKey().Bytes()),
		UUID:       peerID,
	}
	inbounds := []any{}
	for _, network := range []string{"tcp", "xhttp"} {
		port, flow := p.manifest.TCPPort, "xtls-rprx-vision"
		if network == "xhttp" {
			port, flow = p.manifest.XHTTPPort, ""
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
	config := map[string]any{
		"log": map[string]any{"loglevel": "none"}, "inbounds": inbounds,
		"outbounds": []any{
			map[string]any{"tag": "deny", "protocol": "blackhole"},
			map[string]any{"tag": "owned-echo", "protocol": "freedom", "settings": map[string]any{"redirect": echoListener.Addr().String()}},
		},
		"routing": map[string]any{"domainStrategy": "AsIs", "rules": []any{
			map[string]any{"type": "field", "inboundTag": []string{"tcp", "xhttp"}, "network": "tcp", "ip": []string{"192.0.2.77/32"}, "port": "80", "outboundTag": "owned-echo"},
		}},
	}
	_ = tcp.Close()
	_ = xhttp.Close()
	instance, err := startInstance(config)
	if err != nil {
		return nil, err
	}
	p.closers = append(p.closers, instance)
	if err := awaitRealityMetadata(ctx, decoyListener.Addr().String()); err != nil {
		return nil, err
	}
	ready = true
	return p, nil
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

func (p *peer) close() {
	for i := len(p.closers) - 1; i >= 0; i-- {
		_ = p.closers[i].Close()
	}
}

func (p *peer) controlHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /manifest", func(w http.ResponseWriter, _ *http.Request) { _ = json.NewEncoder(w).Encode(p.manifest) })
	mux.HandleFunc("GET /receipts", func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]int64{"count": p.count.Load()})
	})
	mux.HandleFunc("GET /direct-receipts", func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]int64{"count": p.directCount.Load()})
	})
	return mux
}

func main() {
	readyFile := flag.String("ready-file", "", "Exclusive path for the public readiness manifest")
	flag.Parse()
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
	p, err := startPeer(ctx)
	if err != nil {
		fmt.Fprintln(os.Stderr, "owned Xray peer startup failed")
		os.Exit(1)
	}
	defer p.close()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
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
