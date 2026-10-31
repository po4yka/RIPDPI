// The wire protocol and authentication come from the pinned, unmodified Xray
// module. All destinations and the REALITY decoy are owned loopback listeners.
package main

import (
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/xtls/reality"
	xraylog "github.com/xtls/xray-core/common/log"
	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
)

const subscriber = "12345678-1234-1234-1234-123456789abc"
const shortID = "0123456789abcdef"

type bootstrapLog struct{}

func (bootstrapLog) Handle(message xraylog.Message) {
	fmt.Fprintln(os.Stderr, message.String())
}

func run() error {
	// JSON loader warnings must not share the machine-readable announcement channel.
	xraylog.RegisterHandler(bootstrapLog{})
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	certKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return err
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "outbound.invalid"}, DNSNames: []string{"outbound.invalid"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &certKey.PublicKey, certKey)
	if err != nil {
		return err
	}
	tlsConfig := &tls.Config{MinVersion: tls.VersionTLS13, MaxVersion: tls.VersionTLS13, Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: certKey}}, NextProtos: []string{"h2", "http/1.1"}}
	var listeners []net.Listener
	var packets []net.PacketConn
	var connections sync.Map
	var workers sync.WaitGroup
	var lifecycle sync.Mutex
	stopping := false
	defer func() {
		lifecycle.Lock()
		stopping = true
		for _, listener := range listeners {
			listener.Close()
		}
		for _, packet := range packets {
			packet.Close()
		}
		connections.Range(func(k, _ any) bool { k.(net.Conn).Close(); return true })
		lifecycle.Unlock()
		workers.Wait()
	}()
	listen := func() (net.Listener, error) {
		listener, err := net.Listen("tcp", "127.0.0.1:0")
		if err == nil {
			listeners = append(listeners, listener)
		}
		return listener, err
	}
	serve := func(listener net.Listener, handler func(net.Conn)) {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for {
				conn, err := listener.Accept()
				if err != nil {
					return
				}
				lifecycle.Lock()
				if stopping {
					lifecycle.Unlock()
					conn.Close()
					return
				}
				connections.Store(conn, true)
				workers.Add(1)
				lifecycle.Unlock()
				go func() {
					defer workers.Done()
					defer connections.Delete(conn)
					defer conn.Close()
					conn.SetDeadline(time.Now().Add(25 * time.Second))
					handler(conn)
				}()
			}
		}()
	}
	var delivered atomic.Int64
	echo := func(conn net.Conn) {
		buffer := make([]byte, 32768)
		for {
			n, err := conn.Read(buffer)
			if n > 0 {
				delivered.Add(int64(n))
				if _, writeErr := conn.Write(buffer[:n]); writeErr != nil {
					return
				}
			}
			if err != nil {
				return
			}
		}
	}
	endpoints := map[string]string{"uuid": subscriber, "public_key": base64.RawURLEncoding.EncodeToString(key.PublicKey().Bytes()), "short_id": shortID}
	for _, name := range []string{"tcp", "tls", "stats", "stall", "closed", "stall_relay"} {
		listener, err := listen()
		if err != nil {
			return err
		}
		endpoints[name] = listener.Addr().String()
		switch name {
		case "tcp":
			serve(listener, echo)
		case "tls":
			serve(listener, func(conn net.Conn) {
				stream := tls.Server(conn, tlsConfig.Clone())
				defer stream.Close()
				echo(stream)
			})
		case "stats":
			serve(listener, func(conn net.Conn) { fmt.Fprintln(conn, delivered.Load()) })
		case "closed":
			serve(listener, func(net.Conn) {})
		default:
			serve(listener, func(conn net.Conn) { io.Copy(io.Discard, conn) })
		}
	}
	decoy, err := listen()
	if err != nil {
		return err
	}
	decoyServer := &http.Server{
		Handler:           http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNotFound) }),
		TLSConfig:         tlsConfig.Clone(),
		ReadHeaderTimeout: 3 * time.Second,
	}
	defer decoyServer.Close()
	go func() { _ = decoyServer.ServeTLS(decoy, "", "") }()
	for _, name := range []string{"udp", "udp2"} {
		packet, err := net.ListenPacket("udp", "127.0.0.1:0")
		if err != nil {
			return err
		}
		packets = append(packets, packet)
		endpoints[name] = packet.LocalAddr().String()
		workers.Add(1)
		go func() {
			defer workers.Done()
			buffer := make([]byte, 8192)
			for {
				n, addr, err := packet.ReadFrom(buffer)
				if err != nil {
					return
				}
				delivered.Add(int64(n))
				packet.WriteTo(buffer[:n], addr)
			}
		}()
	}
	var inbounds []any
	var reservations []net.Listener
	defer func() {
		for _, listener := range reservations {
			listener.Close()
		}
	}()
	for _, name := range []string{"endpoint", "vision", "minimum", "maximum", "xhttp"} {
		reservation, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return err
		}
		port := reservation.Addr().(*net.TCPAddr).Port
		reservations = append(reservations, reservation)
		endpoints[name] = net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
		flow := ""
		if name == "vision" {
			flow = "xtls-rprx-vision"
		}
		minimum, maximum := "26.3.27", "26.10.29"
		if name == "minimum" {
			minimum, maximum = "26.10.30", ""
		}
		if name == "maximum" {
			minimum, maximum = "", "26.10.28"
		}
		stream := map[string]any{"network": "raw", "security": "reality", "realitySettings": map[string]any{"target": decoy.Addr().String(), "serverNames": []string{"outbound.invalid"}, "privateKey": base64.RawURLEncoding.EncodeToString(key.Bytes()), "shortIds": []string{shortID}, "minClientVer": minimum, "maxClientVer": maximum}}
		if name == "xhttp" {
			// Upstream xHTTP server in auto mode accepts both stream-up and stream-one.
			stream["network"] = "xhttp"
			stream["xhttpSettings"] = map[string]any{"path": "/xudp", "mode": "auto"}
		}
		inbounds = append(inbounds, map[string]any{"listen": "127.0.0.1", "port": port, "protocol": "vless", "settings": map[string]any{"clients": []any{map[string]any{"id": subscriber, "flow": flow}}, "decryption": "none"}, "streamSettings": stream})
	}
	var rules []any
	var finalRules []any
	for _, name := range []string{"tcp", "tls", "udp", "udp2", "stall", "closed"} {
		_, port, _ := net.SplitHostPort(endpoints[name])
		network := "tcp"
		if name == "udp" || name == "udp2" {
			network = "udp"
		}
		rules = append(rules, map[string]any{"type": "field", "ip": []string{"127.0.0.1/32"}, "port": port, "network": network, "outboundTag": "owned"})
		// Upstream blocks private VLESS destinations by default. Admit only
		// these exact owned fixture endpoints, never all private addresses.
		finalRules = append(finalRules, map[string]any{"action": "allow", "network": network, "port": port, "ip": []string{"127.0.0.1/32"}})
	}
	configuration, err := json.Marshal(map[string]any{"log": map[string]string{"loglevel": "none"}, "inbounds": inbounds, "outbounds": []any{map[string]string{"tag": "deny", "protocol": "blackhole"}, map[string]any{"tag": "owned", "protocol": "freedom", "settings": map[string]any{"finalRules": finalRules}}}, "routing": map[string]any{"domainStrategy": "AsIs", "rules": rules}})
	if err != nil {
		return err
	}
	for _, listener := range reservations {
		listener.Close()
	}
	peer, err := core.StartInstance("json", configuration)
	if err != nil {
		return err
	}
	defer peer.Close()
	// Xray starts decoy probes asynchronously; never announce readiness before
	// its actual metadata is available for all supported ALPN variants.
	deadline := time.Now().Add(3 * time.Second)
	for {
		ready := true
		for alpn := range 3 {
			value, _ := reality.GlobalPostHandshakeRecordsLens.Load(fmt.Sprintf("%s outbound.invalid %d", decoy.Addr(), alpn))
			if _, ok := value.([]int); !ok {
				ready = false
			}
		}
		if ready {
			break
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("owned REALITY decoy metadata did not become ready")
		}
		time.Sleep(5 * time.Millisecond)
	}
	if err := json.NewEncoder(os.Stdout).Encode(endpoints); err != nil {
		return err
	}
	var stop [1]byte
	_, err = os.Stdin.Read(stop[:])
	return err
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
