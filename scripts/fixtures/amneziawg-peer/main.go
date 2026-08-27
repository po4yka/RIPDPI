// Independent, rootless AmneziaWG peer. Every OS socket is IPv4 loopback.
// Fixed keys are public test fixtures, never production credentials.
package main

import (
	"bufio"
	"bytes"
	"crypto/ecdh"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"sync"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/conn"
	"github.com/amnezia-vpn/amneziawg-go/device"
	"github.com/amnezia-vpn/amneziawg-go/tun/netstack"
)

type loopbackBind struct {
	mu     sync.Mutex
	socket *net.UDPConn
}

func (b *loopbackBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.socket != nil {
		return nil, 0, conn.ErrBindAlreadyOpen
	}
	socket, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: int(port)})
	if err != nil {
		return nil, 0, err
	}
	b.socket = socket
	receive := func(packets [][]byte, sizes []int, endpoints []conn.Endpoint) (int, error) {
		size, remote, err := socket.ReadFromUDPAddrPort(packets[0])
		if err != nil {
			return 0, err
		}
		if !remote.Addr().IsLoopback() {
			return 0, errors.New("non-loopback peer")
		}
		sizes[0] = size
		endpoints[0] = &conn.StdNetEndpoint{AddrPort: remote}
		return 1, nil
	}
	return []conn.ReceiveFunc{receive}, uint16(socket.LocalAddr().(*net.UDPAddr).Port), nil
}
func (b *loopbackBind) Close() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.socket == nil {
		return nil
	}
	err := b.socket.Close()
	b.socket = nil
	return err
}
func (*loopbackBind) SetMark(mark uint32) error {
	if mark != 0 {
		return errors.New("marks unsupported")
	}
	return nil
}
func (*loopbackBind) BatchSize() int { return 1 }
func (*loopbackBind) ParseEndpoint(value string) (conn.Endpoint, error) {
	addr, err := netip.ParseAddrPort(value)
	if err != nil {
		return nil, err
	}
	if !addr.Addr().Is4() || !addr.Addr().IsLoopback() {
		return nil, errors.New("endpoint must be IPv4 loopback")
	}
	return &conn.StdNetEndpoint{AddrPort: addr}, nil
}
func (b *loopbackBind) Send(packets [][]byte, endpoint conn.Endpoint) error {
	target, ok := endpoint.(*conn.StdNetEndpoint)
	if !ok || !target.AddrPort.Addr().IsLoopback() {
		return errors.New("non-loopback send")
	}
	b.mu.Lock()
	socket := b.socket
	b.mu.Unlock()
	if socket == nil {
		return net.ErrClosed
	}
	for _, packet := range packets {
		n, err := socket.WriteToUDPAddrPort(packet, target.AddrPort)
		if err != nil {
			return err
		}
		if n != len(packet) {
			return io.ErrShortWrite
		}
	}
	return nil
}
func (b *loopbackBind) address() (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.socket == nil {
		return "", net.ErrClosed
	}
	return b.socket.LocalAddr().String(), nil
}

func echoTCP(listener net.Listener) error {
	client, err := listener.Accept()
	if err != nil {
		return err
	}
	defer client.Close()
	if err = client.SetDeadline(time.Now().Add(20 * time.Second)); err != nil {
		return err
	}
	var header [4]byte
	if _, err = io.ReadFull(client, header[:]); err != nil {
		return err
	}
	size := binary.BigEndian.Uint32(header[:])
	if size == 0 || size > 65536 {
		return errors.New("invalid payload size")
	}
	payload := make([]byte, int(size))
	if _, err = io.ReadFull(client, payload); err != nil {
		return err
	}
	_, err = io.Copy(client, bytes.NewReader(payload))
	return err
}
func echoUDPPair(first, second net.PacketConn) error {
	deadline := time.Now().Add(20 * time.Second)
	if err := first.SetDeadline(deadline); err != nil {
		return err
	}
	if err := second.SetDeadline(deadline); err != nil {
		return err
	}
	a, b := make([]byte, 2048), make([]byte, 2048)
	na, pa, err := first.ReadFrom(a)
	if err != nil {
		return err
	}
	nb, pb, err := second.ReadFrom(b)
	if err != nil {
		return err
	}
	// Both requests must arrive before either response: expose source-address loss.
	if _, err = second.WriteTo(b[:nb], pb); err != nil {
		return err
	}
	_, err = first.WriteTo(a[:na], pa)
	return err
}
func run() error {
	server, err := ecdh.X25519().NewPrivateKey(bytes.Repeat([]byte{9}, 32))
	if err != nil {
		return err
	}
	client, err := ecdh.X25519().NewPrivateKey(bytes.Repeat([]byte{7}, 32))
	if err != nil {
		return err
	}
	tun, network, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.77.0.1"), netip.MustParseAddr("fd77::1")}, nil, 1420)
	if err != nil {
		return err
	}
	bind := &loopbackBind{}
	peer := device.NewDevice(tun, bind, device.NewLogger(device.LogLevelSilent, ""))
	defer peer.Close()
	config := fmt.Sprintf("private_key=%s\nlisten_port=0\njc=4\njmin=64\njmax=96\ns1=8\ns2=12\ns3=0\ns4=0\nh1=268435457\nh2=268435458\nh3=268435459\nh4=268435460\npublic_key=%s\npreshared_key=%s\nallowed_ip=10.77.0.2/32\nallowed_ip=fd77::2/128\n", hex.EncodeToString(server.Bytes()), hex.EncodeToString(client.PublicKey().Bytes()), hex.EncodeToString(bytes.Repeat([]byte{5}, 32)))
	if err = peer.IpcSet(config); err != nil {
		return err
	}
	if err = peer.Up(); err != nil {
		return err
	}
	tcp, err := network.ListenTCPAddrPort(netip.MustParseAddrPort("10.77.0.1:41001"))
	if err != nil {
		return err
	}
	defer tcp.Close()
	first, err := network.ListenUDPAddrPort(netip.MustParseAddrPort("10.77.0.1:41002"))
	if err != nil {
		return err
	}
	defer first.Close()
	second, err := network.ListenUDPAddrPort(netip.MustParseAddrPort("10.77.0.1:41003"))
	if err != nil {
		return err
	}
	defer second.Close()
	tcp6, err := network.ListenTCPAddrPort(netip.MustParseAddrPort("[fd77::1]:41004"))
	if err != nil {
		return err
	}
	defer tcp6.Close()
	first6, err := network.ListenUDPAddrPort(netip.MustParseAddrPort("[fd77::1]:41005"))
	if err != nil {
		return err
	}
	defer first6.Close()
	second6, err := network.ListenUDPAddrPort(netip.MustParseAddrPort("[fd77::1]:41006"))
	if err != nil {
		return err
	}
	defer second6.Close()
	results := make(chan error, 4)
	go func() { results <- echoTCP(tcp) }()
	go func() { results <- echoUDPPair(first, second) }()
	go func() { results <- echoTCP(tcp6) }()
	go func() { results <- echoUDPPair(first6, second6) }()
	endpoint, err := bind.address()
	if err != nil {
		return err
	}
	if err = json.NewEncoder(os.Stdout).Encode(map[string]string{"endpoint": endpoint}); err != nil {
		return err
	}
	stop := make(chan struct{})
	go func() { bufio.NewScanner(os.Stdin).Scan(); close(stop) }()
	timer := time.NewTimer(30 * time.Second)
	defer timer.Stop()
	completed := 0
	for {
		select {
		case err := <-results:
			if err != nil {
				return err
			}
			completed++
		case <-stop:
			// The final write can reach the client before its worker enqueues completion.
			for completed < 4 {
				select {
				case err := <-results:
					if err != nil {
						return err
					}
					completed++
				case <-timer.C:
					return fmt.Errorf("incomplete exchanges: %d/4", completed)
				}
			}
			return nil
		case <-timer.C:
			return errors.New("peer deadline exceeded")
		}
	}
}
func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
