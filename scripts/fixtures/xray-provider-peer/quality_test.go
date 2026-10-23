package main

import (
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"fmt"
	"io"
	"net/http"
	"testing"
	"time"
)

func TestAuthenticatedQualityBodyAndOverlappingHeadThroughReality(t *testing.T) {
	p, err := startPeer(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer p.close()
	der, err := base64.StdEncoding.DecodeString(p.manifest.QualityCertificate)
	if err != nil {
		t.Fatal(err)
	}
	certificate, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	roots := x509.NewCertPool()
	roots.AddCert(certificate)
	for _, network := range []string{"tcp", "xhttp"} {
		t.Run(network, func(t *testing.T) {
			client := realityHTTPClient(t, p, network, nil)
			client.Transport.(*http.Transport).TLSClientConfig = &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS13}
			before := p.count.Load()
			endpoint := "https://quality-a.example.test/quality-payload"
			baseline, err := client.Head(endpoint)
			if err != nil {
				t.Fatal(err)
			}
			baseline.Body.Close()
			if baseline.StatusCode != 200 || baseline.ContentLength != int64(qualityPayloadBytes) {
				t.Fatal("invalid baseline")
			}
			transfer, err := client.Get(endpoint)
			if err != nil {
				t.Fatal(err)
			}
			defer transfer.Body.Close()
			first := make([]byte, 1)
			if _, err := io.ReadFull(transfer.Body, first); err != nil {
				t.Fatal(err)
			}
			started := time.Now()
			loaded, err := client.Head(endpoint)
			if err != nil {
				t.Fatal(err)
			}
			loadedAt := time.Now()
			loaded.Body.Close()
			rest, err := io.ReadAll(transfer.Body)
			if err != nil {
				t.Fatal(err)
			}
			finished := time.Now()
			body := append(first, rest...)
			if len(body) != p.manifest.QualityBytes || fmt.Sprintf("%x", sha256.Sum256(body)) != p.manifest.QualitySHA256 {
				t.Fatal("body integrity failed")
			}
			if loaded.StatusCode != 200 || loaded.ContentLength != int64(qualityPayloadBytes) || !loadedAt.Before(finished) || loadedAt.Before(started) {
				t.Fatal("HEAD did not overlap authenticated transfer")
			}
			if p.count.Load() != before+3 || p.directCount.Load() != 0 {
				t.Fatal("quality escaped owned REALITY route")
			}
		})
	}
}
