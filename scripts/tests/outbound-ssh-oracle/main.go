// Independent SSH peer using the pinned Go SSH implementation, not russh.
package main

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/crypto/ssh"
)

type directTarget struct {
	Host       string
	Port       uint32
	Origin     string
	OriginPort uint32
}

func run() error {
	_, hostKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	host, err := ssh.NewSignerFromKey(hostKey)
	if err != nil {
		return err
	}
	_, userKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	user, err := ssh.NewSignerFromKey(userKey)
	if err != nil {
		return err
	}
	private, err := ssh.MarshalPrivateKeyWithPassphrase(userKey, "outbound-oracle", []byte("loopback-key-passphrase"))
	if err != nil {
		return err
	}
	if err := os.WriteFile(os.Args[1], pem.EncodeToMemory(private), 0600); err != nil {
		return err
	}
	var attempts atomic.Int64
	config := &ssh.ServerConfig{
		AuthLogCallback: func(_ ssh.ConnMetadata, _ string, _ error) { attempts.Add(1) },
		PasswordCallback: func(metadata ssh.ConnMetadata, password []byte) (*ssh.Permissions, error) {
			if metadata.User() == "outbound-interop" && string(password) == "loopback-test-password" {
				return nil, nil
			}
			return nil, errors.New("test authentication rejected")
		},
		PublicKeyCallback: func(metadata ssh.ConnMetadata, key ssh.PublicKey) (*ssh.Permissions, error) {
			if metadata.User() == "outbound-interop" && bytes.Equal(key.Marshal(), user.PublicKey().Marshal()) {
				return nil, nil
			}
			return nil, errors.New("test key rejected")
		},
	}
	config.AddHostKey(host)
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
	echoAddress := echo.Addr().(*net.TCPAddr)
	var echoWorkers sync.WaitGroup
	var echoConnections sync.Map
	echoStopped := make(chan struct{})
	go func() {
		defer close(echoStopped)
		for {
			socket, err := echo.Accept()
			if err != nil {
				return
			}
			echoConnections.Store(socket, struct{}{})
			echoWorkers.Add(1)
			go func() {
				defer echoWorkers.Done()
				defer echoConnections.Delete(socket)
				defer socket.Close()
				socket.SetDeadline(time.Now().Add(20 * time.Second))
				io.Copy(socket, socket)
			}()
		}
	}()
	var workers sync.WaitGroup
	var connections sync.Map
	stopped := make(chan struct{})
	go func() {
		defer close(stopped)
		for {
			raw, err := listener.Accept()
			if err != nil {
				return
			}
			connections.Store(raw, struct{}{})
			workers.Add(1)
			go func() {
				defer workers.Done()
				defer connections.Delete(raw)
				defer raw.Close()
				raw.SetDeadline(time.Now().Add(20 * time.Second))
				conn, channels, requests, err := ssh.NewServerConn(raw, config)
				if err != nil {
					return
				}
				defer conn.Close()
				var children sync.WaitGroup
				children.Add(1)
				go func() { defer children.Done(); ssh.DiscardRequests(requests) }()
				for channel := range channels {
					var target directTarget
					if channel.ChannelType() != "direct-tcpip" || ssh.Unmarshal(channel.ExtraData(), &target) != nil || target.Host != echoAddress.IP.String() || target.Port != uint32(echoAddress.Port) {
						channel.Reject(ssh.Prohibited, "only the owned echo target is allowed")
						continue
					}
					targetSocket, err := net.DialTimeout("tcp4", echo.Addr().String(), 2*time.Second)
					if err != nil {
						channel.Reject(ssh.ConnectionFailed, "owned target unavailable")
						continue
					}
					targetSocket.SetDeadline(time.Now().Add(20 * time.Second))
					stream, requests, err := channel.Accept()
					if err != nil {
						targetSocket.Close()
						continue
					}
					children.Add(1)
					go func() {
						defer children.Done()
						defer stream.Close()
						requestDone := make(chan struct{})
						go func() { defer close(requestDone); ssh.DiscardRequests(requests) }()
						copied := make(chan struct{})
						go func() {
							defer close(copied)
							io.Copy(targetSocket, stream)
							targetSocket.(*net.TCPConn).CloseWrite()
						}()
						io.Copy(stream, targetSocket)
						stream.Close()
						targetSocket.Close()
						<-copied
						<-requestDone
					}()
				}
				conn.Close()
				children.Wait()
			}()
		}
	}()
	json.NewEncoder(os.Stdout).Encode(map[string]string{"endpoint": listener.Addr().String(), "tcp": echo.Addr().String(), "fingerprint": ssh.FingerprintSHA256(host.PublicKey()), "private_key": os.Args[1]})
	var stop [1]byte
	_, _ = os.Stdin.Read(stop[:])
	listener.Close()
	<-stopped
	connections.Range(func(key, _ any) bool { key.(net.Conn).Close(); return true })
	workers.Wait()
	echo.Close()
	<-echoStopped
	echoConnections.Range(func(key, _ any) bool { key.(net.Conn).Close(); return true })
	echoWorkers.Wait()
	// A key-policy rejection test must never reach an authentication callback.
	if os.Getenv("RIPDPI_OUTBOUND_EXPECT_NO_AUTH") == "1" && attempts.Load() != 0 {
		return errors.New("host-key rejection transmitted authentication")
	}
	return nil
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
