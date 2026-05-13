package main

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"log"
	"math/big"
	"net/http"
	"time"

	"github.com/quic-go/quic-go/http3"
)

func main() {
	mux := http.NewServeMux()
	mux.HandleFunc("/h3/ok", jsonHandler(map[string]any{"ok": true, "service": "quic-go", "endpoint": "ok"}))
	mux.HandleFunc("/h3/delay", func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(3 * time.Second)
		jsonHandler(map[string]any{"ok": true, "delayed": true})(w, r)
	})
	mux.HandleFunc("/h3/large", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/octet-stream")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(make([]byte, 1024*1024))
	})
	mux.HandleFunc("/h3/close", func(w http.ResponseWriter, r *http.Request) {
		hj, ok := w.(http.Hijacker)
		if !ok {
			http.Error(w, "close unsupported", http.StatusInternalServerError)
			return
		}
		conn, _, err := hj.Hijack()
		if err == nil {
			_ = conn.Close()
		}
	})

	server := &http3.Server{
		Addr:      ":9443",
		Handler:   mux,
		TLSConfig: selfSignedTLSConfig(),
	}
	log.Println("RIPDPI QUIC lab server listening on :9443")
	log.Fatal(server.ListenAndServe())
}

func jsonHandler(payload map[string]any) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(payload)
	}
}

func selfSignedTLSConfig() *tls.Config {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		log.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "quic.test"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
		DNSNames:     []string{"quic.test", "localhost"},
		KeyUsage:     x509.KeyUsageKeyEncipherment | x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	certDER, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		log.Fatal(err)
	}
	return &tls.Config{
		Certificates: []tls.Certificate{{Certificate: [][]byte{certDER}, PrivateKey: key}},
		NextProtos:   []string{http3.NextProtoH3},
		MinVersion:   tls.VersionTLS12,
	}
}
