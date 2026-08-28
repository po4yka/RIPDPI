// This entrypoint is compiled beside byte-identical pinned upstream server files.
// TLS authentication, sessions and TCP/UoT forwarding are upstream code.
package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"math/big"
	"net"
	"os"
	"sync"
	"time"
)

var passwordSha256 []byte

func certificate() (*tls.Certificate, []byte, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	now := time.Now()
	ca := &x509.Certificate{SerialNumber: big.NewInt(1), NotBefore: now.Add(-time.Hour), NotAfter: now.Add(time.Hour), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign}
	root, err := x509.CreateCertificate(rand.Reader, ca, ca, &key.PublicKey, key)
	if err != nil {
		return nil, nil, err
	}
	leaf := &x509.Certificate{SerialNumber: big.NewInt(2), NotBefore: now.Add(-time.Hour), NotAfter: now.Add(time.Hour), DNSNames: []string{"outbound.invalid"}, KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, leaf, ca, &key.PublicKey, key)
	if err != nil {
		return nil, nil, err
	}
	return &tls.Certificate{Certificate: [][]byte{der, root}, PrivateKey: key}, root, nil
}

func run() error {
	hash := sha256.Sum256([]byte("loopback-test-password"))
	passwordSha256 = hash[:]
	cert, root, err := certificate()
	if err != nil {
		return err
	}
	if err = os.WriteFile(os.Args[1], pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: root}), 0600); err != nil {
		return err
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return err
	}
	defer listener.Close()
	echo, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return err
	}
	defer echo.Close()
	udp, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		return err
	}
	defer udp.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var workers sync.WaitGroup
	var accepts sync.WaitGroup
	var connections sync.Map
	serve := func(ln net.Listener, handler func(net.Conn)) {
		accepts.Add(1)
		go func() {
			defer accepts.Done()
			for {
				conn, err := ln.Accept()
				if err != nil {
					return
				}
				connections.Store(conn, struct{}{})
				workers.Add(1)
				go func() {
					defer workers.Done()
					defer connections.Delete(conn)
					defer conn.Close()
					conn.SetDeadline(time.Now().Add(20 * time.Second))
					handler(conn)
				}()
			}
		}()
	}
	server := NewMyServer(&tls.Config{Certificates: []tls.Certificate{*cert}, MinVersion: tls.VersionTLS12})
	serve(listener, func(conn net.Conn) { handleTcpConnection(ctx, conn, server) })
	serve(echo, func(conn net.Conn) { io.Copy(conn, conn) })
	workers.Add(1)
	go func() {
		defer workers.Done()
		payload := make([]byte, 65535)
		for {
			n, addr, err := udp.ReadFrom(payload)
			if err != nil {
				return
			}
			if _, err = udp.WriteTo(payload[:n], addr); err != nil {
				return
			}
		}
	}()
	json.NewEncoder(os.Stdout).Encode(map[string]string{"endpoint": listener.Addr().String(), "tcp": echo.Addr().String(), "udp": udp.LocalAddr().String(), "certificate": os.Args[1]})
	var stop [1]byte
	_, _ = os.Stdin.Read(stop[:])
	cancel()
	listener.Close()
	echo.Close()
	udp.Close()
	accepts.Wait()
	connections.Range(func(key, _ any) bool { key.(net.Conn).Close(); return true })
	workers.Wait()
	return nil
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
