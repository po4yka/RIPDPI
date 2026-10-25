package main

import (
	"crypto/tls"
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"net"
	"os"
	"path/filepath"
)

// The optional plaintext inlet is loopback-only and exists only behind the owned nginx frontend.
func (p *peer) prepareTLSFrontend(certificate tls.Certificate) (any, net.Listener, net.Listener, error) {
	if err := os.Mkdir(p.options.TLSDirectory, 0700); err != nil {
		return nil, nil, nil, err
	}
	private, err := x509.MarshalPKCS8PrivateKey(certificate.PrivateKey)
	if err != nil {
		return nil, nil, nil, err
	}
	for name, block := range map[string]*pem.Block{
		"certificate.pem": {Type: "CERTIFICATE", Bytes: certificate.Certificate[0]},
		"private-key.pem": {Type: "PRIVATE KEY", Bytes: private},
	} {
		if err := os.WriteFile(filepath.Join(p.options.TLSDirectory, name), pem.EncodeToMemory(block), 0600); err != nil {
			return nil, nil, nil, err
		}
	}
	public, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, nil, nil, err
	}
	privateListener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		public.Close()
		return nil, nil, nil, err
	}
	p.closers = append(p.closers, public)
	p.backends = append(p.backends, privateListener.Addr().String())
	p.manifest.TLSBackendPort = public.Addr().(*net.TCPAddr).Port
	inbound := map[string]any{
		"tag": "xhttp-tls", "listen": "127.0.0.1", "port": privateListener.Addr().(*net.TCPAddr).Port,
		"protocol": "vless", "settings": map[string]any{
			"decryption": "none", "clients": []any{map[string]any{"id": p.options.ClientID}},
		},
		"streamSettings": map[string]any{
			"network": "xhttp", "security": "none",
			"xhttpSettings": map[string]any{"path": "/owned-xhttp", "mode": "auto"},
		},
	}
	return inbound, public, privateListener, nil
}

func (p *peer) frontendAddresses() []string {
	addresses := []string{
		net.JoinHostPort(p.options.BindHost, fmt.Sprint(p.manifest.TCPPort)),
		net.JoinHostPort(p.options.BindHost, fmt.Sprint(p.manifest.XHTTPPort)),
	}
	if p.manifest.TLSBackendPort != 0 {
		addresses = append(addresses, net.JoinHostPort("127.0.0.1", fmt.Sprint(p.manifest.TLSBackendPort)))
	}
	return addresses
}

func frontendName(index int) string {
	return []string{"tcp", "xhttp", "xhttp-tls"}[index]
}
