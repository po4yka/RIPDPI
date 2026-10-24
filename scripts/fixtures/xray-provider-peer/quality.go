package main

import (
	"crypto/sha256"
	"crypto/tls"
	"encoding/base64"
	"fmt"
	"net"
	"net/http"
	"time"
)

const qualityPayloadBytes = 262144

func qualityPayload() []byte {
	body := make([]byte, qualityPayloadBytes)
	for index := range body {
		body[index] = byte(index % 251)
	}
	return body
}

// The origin is loopback-only and reached by exact Xray routes; HEAD and GET share one object.
func (p *peer) startQualityOrigin(certificate tls.Certificate) (net.Listener, error) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	body := qualityPayload()
	p.manifest.QualityPort = listener.Addr().(*net.TCPAddr).Port
	p.manifest.QualityBytes = len(body)
	p.manifest.QualitySHA256 = fmt.Sprintf("%x", sha256.Sum256(body))
	p.manifest.QualityCertificate = base64.StdEncoding.EncodeToString(certificate.Certificate[0])
	server := &http.Server{
		TLSConfig:         &tls.Config{MinVersion: tls.VersionTLS13, Certificates: []tls.Certificate{certificate}},
		ReadHeaderTimeout: 3 * time.Second, WriteTimeout: 5 * time.Second, IdleTimeout: time.Second,
		MaxHeaderBytes: 16384,
		Handler: http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
			if request.URL.Path != "/quality-payload" || (request.Method != http.MethodHead && request.Method != http.MethodGet) {
				w.WriteHeader(http.StatusNotFound)
				return
			}
			p.count.Add(1)
			p.requests.record("Quality", request.URL.Path)
			w.Header().Set("Connection", "close")
			w.Header().Set("Content-Type", "application/octet-stream")
			w.Header().Set("Content-Length", fmt.Sprint(len(body)))
			w.WriteHeader(http.StatusOK)
			if request.Method == http.MethodHead {
				return
			}
			if written, err := w.Write(body[:1]); err != nil || written != 1 {
				return
			}
			if err := http.NewResponseController(w).Flush(); err != nil {
				return
			}
			timer := time.NewTimer(400 * time.Millisecond)
			defer timer.Stop()
			select {
			case <-request.Context().Done():
				return
			case <-timer.C:
			}
			if written, err := w.Write(body[1:]); err == nil && written == len(body)-1 {
				p.requests.record("QualityComplete", request.URL.Path)
			}
		}),
	}
	p.closers = append(p.closers, server)
	go func() { _ = server.ServeTLS(listener, "", "") }()
	return listener, nil
}
