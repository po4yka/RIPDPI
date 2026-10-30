package main

import (
	"bytes"
	"crypto/ecdh"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"strings"
)

// kernelConfig uses the same public fixture keys and profiles as the Go peer.
func kernelConfig(profile wireProfile) (string, error) {
	client, err := ecdh.X25519().NewPrivateKey(bytes.Repeat([]byte{7}, 32))
	if err != nil {
		return "", err
	}
	b64 := base64.StdEncoding.EncodeToString
	var config strings.Builder
	fmt.Fprintf(&config, "[Interface]\nPrivateKey = %s\nListenPort = 51820\n", b64(bytes.Repeat([]byte{9}, 32)))
	for _, line := range strings.Split(strings.TrimSpace(profile.iface), "\n") {
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			return "", errors.New("invalid fixture UAPI line")
		}
		parts := strings.Split(key, "_")
		for i, part := range parts {
			parts[i] = strings.ToUpper(part[:1]) + part[1:]
		}
		if key == "header_protection_key" {
			value = b64(profile.headerKey)
		}
		if value == "true" {
			value = "on"
		}
		if value == "false" {
			value = "off"
		}
		fmt.Fprintf(&config, "%s = %s\n", strings.Join(parts, ""), value)
	}
	fmt.Fprintf(&config, "[Peer]\nPublicKey = %s\nPresharedKey = %s\nAllowedIPs = 10.77.0.2/32, fd77::2/128\n",
		b64(client.PublicKey().Bytes()), b64(bytes.Repeat([]byte{5}, 32)))
	for _, line := range strings.Split(profile.peer, "\n") {
		if value, ok := strings.CutPrefix(line, "persistent_keepalive_interval="); ok {
			fmt.Fprintf(&config, "PersistentKeepalive = %s\n", value)
		}
	}
	return config.String(), nil
}

// kernelEcho reuses the byte-for-byte payload oracle over ordinary OS sockets.
// It must run inside the disposable namespace that owns the kernel AWG interface.
func kernelEcho(cycles int) error {
	scenario := os.Getenv("RIPDPI_AWG_SCENARIO")
	if scenario != "" && scenario != "payload" && scenario != "small-datagrams" {
		return errors.New("unsupported kernel echo scenario")
	}
	small := scenario == "small-datagrams"
	var closers []func() error
	defer func() {
		for _, closeSocket := range closers {
			closeSocket()
		}
	}()
	var exchanges []func() error
	tcpAddresses := []string{"10.77.0.1:41001", "[fd77::1]:41004"}
	pairs := [][2]string{{"10.77.0.1:41002", "10.77.0.1:41003"}, {"[fd77::1]:41005", "[fd77::1]:41006"}}
	if small {
		tcpAddresses = nil
		pairs = pairs[:1]
	}
	for _, address := range tcpAddresses {
		listener, err := net.Listen("tcp", address)
		if err != nil {
			return err
		}
		closers = append(closers, listener.Close)
		exchanges = append(exchanges, func() error { return echoTCP(listener) })
	}
	for _, pair := range pairs {
		first, err := net.ListenPacket("udp", pair[0])
		if err != nil {
			return err
		}
		closers = append(closers, first.Close)
		second, err := net.ListenPacket("udp", pair[1])
		if err != nil {
			return err
		}
		closers = append(closers, second.Close)
		exchanges = append(exchanges, func() error { return echoUDPPair(first, second) })
	}
	results := make(chan error, len(exchanges))
	for _, exchange := range exchanges {
		go func() {
			for range cycles {
				if err := exchange(); err != nil {
					results <- err
					return
				}
			}
			results <- nil
		}()
	}
	if err := json.NewEncoder(os.Stdout).Encode(map[string]string{"endpoint": "127.0.0.1:51820"}); err != nil {
		return err
	}
	for range exchanges {
		if err := <-results; err != nil {
			return err
		}
	}
	return json.NewEncoder(os.Stdout).Encode(map[string]int{"completed_sessions": cycles})
}
