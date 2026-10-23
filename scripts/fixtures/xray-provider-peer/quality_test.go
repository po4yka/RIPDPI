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
		for _, late := range []bool{false, true} {
			t.Run(fmt.Sprintf("%s/late-head=%t", network, late), func(t *testing.T) {
				client := realityHTTPClient(t, p, network, nil)
				client.Transport.(*http.Transport).TLSClientConfig = &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS13}
				before := p.count.Load()
				verifyQualityRound(t, p, client, late)
				if p.count.Load() != before+3 || p.directCount.Load() != 0 {
					t.Fatal("quality escaped owned REALITY route")
				}
			})
		}
	}
}

func verifyQualityRound(t *testing.T, p *peer, client *http.Client, late bool) {
	t.Helper()
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
	type bodyResult struct {
		bytes []byte
		ended time.Time
		err   error
	}
	finished := make(chan bodyResult, 1)
	go func() {
		rest, readErr := io.ReadAll(transfer.Body)
		ended := time.Now()
		finished <- bodyResult{append(first, rest...), ended, readErr}
	}()
	// Deliberately issue the negative control only after independently observed EOF.
	var body bodyResult
	if late {
		body = <-finished
	}
	headStarted := time.Now()
	loaded, err := client.Head(endpoint)
	loadedAt := time.Now()
	if err != nil {
		t.Fatal(err)
	}
	loaded.Body.Close()
	if !late {
		body = <-finished
	}
	if body.err != nil {
		t.Fatal(body.err)
	}
	if len(body.bytes) != p.manifest.QualityBytes || fmt.Sprintf("%x", sha256.Sum256(body.bytes)) != p.manifest.QualitySHA256 {
		t.Fatal("body integrity failed")
	}
	if loaded.StatusCode != 200 || loaded.ContentLength != int64(qualityPayloadBytes) {
		t.Fatal("invalid loaded HEAD")
	}
	overlap := !headStarted.Before(started) && !loadedAt.After(body.ended)
	if overlap == late {
		t.Fatalf("observed overlap=%t, late control=%t", overlap, late)
	}
}
