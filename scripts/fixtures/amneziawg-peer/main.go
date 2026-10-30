// Independent, rootless AmneziaWG peer. Loopback unless a private peer is explicitly selected.
// Fixed keys are public test fixtures, never production credentials.
package main

import (
	"bufio"
	"bytes"
	"crypto/ecdh"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
	"golang.org/x/net/dns/dnsmessage"
)

type loopbackBind struct {
	bindIP      netip.Addr
	allowedPeer netip.Addr
	mu          sync.Mutex
	socket      *net.UDPConn
	observation wireObservation
	cookies     *cookieExercise
	transport   *transportExercise
}

func newPeerBind() (*loopbackBind, error) {
	localText, remoteText := os.Getenv("RIPDPI_AWG_BIND_IP"), os.Getenv("RIPDPI_AWG_PEER_IP")
	if localText == "" && remoteText == "" {
		return &loopbackBind{bindIP: netip.MustParseAddr("127.0.0.1")}, nil
	}
	local, localErr := netip.ParseAddr(localText)
	remote, remoteErr := netip.ParseAddr(remoteText)
	if localErr != nil || remoteErr != nil || !local.Is4() || !remote.Is4() || !local.IsPrivate() || !remote.IsPrivate() || local == remote {
		return nil, errors.New("LAN fixture requires distinct private IPv4 bind and peer addresses")
	}
	return &loopbackBind{bindIP: local, allowedPeer: remote}, nil
}
func (b *loopbackBind) allows(address netip.Addr) bool {
	if b.allowedPeer.IsValid() {
		return address == b.allowedPeer
	}
	return address.Is4() && address.IsLoopback()
}

func (b *loopbackBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.socket != nil {
		return nil, 0, conn.ErrBindAlreadyOpen
	}
	socket, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IP(b.bindIP.AsSlice()), Port: int(port)})
	if err != nil {
		return nil, 0, err
	}
	b.socket = socket
	receive := func(packets [][]byte, sizes []int, endpoints []conn.Endpoint) (int, error) {
		size, remote, err := socket.ReadFromUDPAddrPort(packets[0])
		if err != nil {
			return 0, err
		}
		if !b.allows(remote.Addr()) {
			return 0, errors.New("non-loopback peer")
		}
		if b.cookies != nil && b.cookies.intercept(packets[0][:size], remote) {
			sizes[0] = 0
			return 0, nil
		}
		b.observation.observe(packets[0][:size], true)
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
func (b *loopbackBind) ParseEndpoint(value string) (conn.Endpoint, error) {
	addr, err := netip.ParseAddrPort(value)
	if err != nil {
		return nil, err
	}
	if !b.allows(addr.Addr()) {
		return nil, errors.New("endpoint must be IPv4 loopback")
	}
	return &conn.StdNetEndpoint{AddrPort: addr}, nil
}
func (b *loopbackBind) Send(packets [][]byte, endpoint conn.Endpoint) error {
	target, ok := endpoint.(*conn.StdNetEndpoint)
	if !ok || !b.allows(target.AddrPort.Addr()) {
		return errors.New("non-loopback send")
	}
	b.mu.Lock()
	socket := b.socket
	b.mu.Unlock()
	if socket == nil {
		return net.ErrClosed
	}
	for _, packet := range packets {
		if b.transport != nil {
			handled, err := b.transport.send(packet, socket, target.AddrPort)
			if err != nil {
				return err
			}
			if handled {
				continue
			}
		}
		if b.observation.observe(packet, false) {
			continue
		}
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
	if err = client.SetDeadline(time.Now().Add(60 * time.Second)); err != nil {
		return err
	}
	var header [4]byte
	if _, err = io.ReadFull(client, header[:]); err != nil {
		return err
	}
	size := binary.BigEndian.Uint32(header[:])
	if size == 0 || size > 16<<20 {
		return errors.New("invalid payload size")
	}
	payload := make([]byte, int(size))
	if _, err = io.ReadFull(client, payload); err != nil {
		return err
	}
	_, err = io.Copy(client, bytes.NewReader(payload))
	return err
}

// sessionWait bounds the wait for one session's first datagram: bulk TCP runs before the UDP and DNS exchanges.
const sessionWait = 90 * time.Second

func echoUDPPair(first, second net.PacketConn) error {
	deadline := time.Now().Add(sessionWait)
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

// echoUDPSeries echoes count datagrams; the client sends each one after the previous echo, so any loss stalls it.
func echoUDPSeries(socket net.PacketConn, count int) error {
	buffer := make([]byte, 2048)
	for i := 0; i < count; i++ {
		wait := 20 * time.Second
		if i == 0 {
			wait = sessionWait
		}
		if err := socket.SetDeadline(time.Now().Add(wait)); err != nil {
			return err
		}
		size, source, err := socket.ReadFrom(buffer)
		if err != nil {
			return err
		}
		if _, err = socket.WriteTo(buffer[:size], source); err != nil {
			return err
		}
	}
	return nil
}

func answerDNS(socket net.PacketConn) error {
	if err := socket.SetDeadline(time.Now().Add(sessionWait)); err != nil {
		return err
	}
	buffer := make([]byte, 1024)
	size, source, err := socket.ReadFrom(buffer)
	if err != nil {
		return err
	}
	var parser dnsmessage.Parser
	header, err := parser.Start(buffer[:size])
	if err != nil {
		return err
	}
	question, err := parser.Question()
	if err != nil {
		return err
	}
	if question.Name.String() != "acceptance.test." {
		return errors.New("unexpected DNS question")
	}
	builder := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: header.ID, Response: true, Authoritative: true})
	if err := builder.StartQuestions(); err != nil {
		return err
	}
	if err := builder.Question(question); err != nil {
		return err
	}
	if err := builder.StartAnswers(); err != nil {
		return err
	}
	record := dnsmessage.ResourceHeader{Name: question.Name, Class: dnsmessage.ClassINET, TTL: 1}
	switch question.Type {
	case dnsmessage.TypeA:
		err = builder.AResource(record, dnsmessage.AResource{A: [4]byte{192, 0, 2, 53}})
	case dnsmessage.TypeAAAA:
		err = builder.AAAAResource(record, dnsmessage.AAAAResource{AAAA: netip.MustParseAddr("fd77::53").As16()})
	default:
		return errors.New("unsupported DNS question type")
	}
	if err != nil {
		return err
	}
	response, err := builder.Finish()
	if err != nil {
		return err
	}
	_, err = socket.WriteTo(response, source)
	return err
}
func run() error {
	mtu, err := strconv.Atoi(os.Getenv("RIPDPI_AWG_INTEROP_MTU"))
	if err != nil || mtu < 1280 || mtu > 1420 {
		return errors.New("RIPDPI_AWG_INTEROP_MTU must be an integer from 1280 through 1420")
	}
	cycles := 1
	if value := os.Getenv("RIPDPI_AWG_INTEROP_CYCLES"); value != "" {
		cycles, err = strconv.Atoi(value)
		if err != nil || cycles < 1 || cycles > 4 {
			return errors.New("RIPDPI_AWG_INTEROP_CYCLES must be an integer from 1 through 4")
		}
	}
	series := 0
	if value := os.Getenv("RIPDPI_AWG_UDP_SERIES"); value != "" {
		series, err = strconv.Atoi(value)
		if err != nil || series < 1 || series > 4096 {
			return errors.New("RIPDPI_AWG_UDP_SERIES must be an integer from 1 through 4096")
		}
	}
	scenario := os.Getenv("RIPDPI_AWG_SCENARIO")
	if scenario != "" && scenario != "payload" && scenario != "small-datagrams" && scenario != "timer-persistent" && scenario != "timer-idle" && scenario != "timer-retry-loss" && scenario != "timer-rekey" && scenario != "timer-retry-exhaust" && scenario != "timer-reject" && scenario != "cookies-enabled" && scenario != "cookies-disabled" && scenario != "negative-transport" {
		return errors.New("unknown fixture scenario")
	}
	transport := scenario == "negative-transport"
	cookie := strings.HasPrefix(scenario, "cookies-")
	timed := strings.HasPrefix(scenario, "timer-")
	small := scenario == "small-datagrams" || timed || cookie || transport
	if (timed || cookie || transport) && (cycles != 1 || os.Getenv("RIPDPI_AWG_PROFILE") != "v3-cpa-trailers") {
		return errors.New("timer scenarios require one session and v3-cpa-trailers")
	}
	profile, err := selectWireProfile(os.Getenv("RIPDPI_AWG_PROFILE"))
	if err != nil {
		return err
	}
	switch os.Getenv("RIPDPI_AWG_KERNEL_MODE") {
	case "config":
		config, err := kernelConfig(profile)
		if err != nil {
			return err
		}
		_, err = fmt.Print(config)
		return err
	case "echo":
		if timed || transport {
			return kernelControlledPeer(profile, scenario)
		}
		return kernelEcho(cycles)
	case "observe":
		if transport {
			return observeKernelNegativeCapture(profile)
		}
		return observeKernelCapture(profile, cycles)
	case "":
	default:
		return errors.New("unknown kernel fixture mode")
	}
	server, err := ecdh.X25519().NewPrivateKey(bytes.Repeat([]byte{9}, 32))
	if err != nil {
		return err
	}
	client, err := ecdh.X25519().NewPrivateKey(bytes.Repeat([]byte{7}, 32))
	if err != nil {
		return err
	}
	addresses := []netip.Addr{netip.MustParseAddr("10.77.0.1"), netip.MustParseAddr("fd77::1")}
	httpAcceptance := os.Getenv("RIPDPI_AWG_HTTP_ACCEPTANCE") == "1"
	if httpAcceptance {
		addresses = append(addresses, netip.MustParseAddr("192.0.2.77"))
	}
	tun, network, err := netstack.CreateNetTUN(addresses, nil, mtu)
	if err != nil {
		return err
	}
	bind, err := newPeerBind()
	if err != nil {
		return err
	}
	bind.observation.profile = profile
	if timed || cookie || transport {
		bind.observation.scenario = scenario
	}
	if cookie {
		bind.cookies = newCookieExercise(bind, profile, client.PublicKey().Bytes())
		bind.observation.cookies = bind.cookies
		bind.observation.evidence.start = bind.cookies.started
	}
	if transport {
		bind.transport = newTransportExercise(bind, profile)
		bind.observation.transport = bind.transport
		bind.observation.evidence.start = bind.transport.start
	}
	configured := newConfiguredTUN(tun)
	if transport {
		configured.mutateIP = bind.transport.mutateIP
	}
	configured.acceptedIP = bind.observation.acceptedIP
	peer := device.NewDevice(configured, bind, &device.Logger{Verbosef: bind.observation.verbose, Errorf: device.DiscardLogf})
	defer peer.Close()
	if cookie {
		bind.cookies.device = peer
	}
	config := fmt.Sprintf("private_key=%s\nlisten_port=0\n%spublic_key=%s\npreshared_key=%s\n%sallowed_ip=10.77.0.2/32\nallowed_ip=fd77::2/128\n", hex.EncodeToString(server.Bytes()), profile.iface, hex.EncodeToString(client.PublicKey().Bytes()), hex.EncodeToString(bytes.Repeat([]byte{5}, 32)), profile.peer)
	if err = peer.IpcSet(config); err != nil {
		return err
	}
	configured.configured()
	if err = peer.Up(); err != nil {
		return err
	}
	if httpAcceptance {
		endpoint, err := bind.address()
		if err != nil {
			return err
		}
		return runHTTPAcceptance(network, peer, bind, endpoint, base64.StdEncoding.EncodeToString(server.PublicKey().Bytes()))
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
	expected := 4
	if small {
		expected = 1
	}
	if scenario == "timer-persistent" || scenario == "timer-retry-exhaust" {
		expected = 0
	}
	results := make(chan error, 8)
	serve := func(exchange func() error) {
		for cycle := 0; cycle < cycles; cycle++ {
			if err := exchange(); err != nil {
				results <- err
				return
			}
		}
		results <- nil
	}
	if transport {
		go serve(func() error { return bind.transport.run(first) })
	} else if cookie {
		go serve(func() error { return bind.cookies.run(first, scenario == "cookies-disabled") })
	} else if scenario != "timer-persistent" && scenario != "timer-retry-exhaust" {
		go serve(func() error {
			if err := echoUDPPair(first, second); err != nil {
				return err
			}
			if scenario == "timer-rekey" || scenario == "timer-reject" {
				if err := echoUDPPair(first, second); err != nil {
					return err
				}
				return echoUDPPair(first, second)
			}
			return nil
		})
	}
	if !small {
		go serve(func() error { return echoTCP(tcp) })
		go serve(func() error { return echoTCP(tcp6) })
		go serve(func() error { return echoUDPPair(first6, second6) })
	}
	if series > 0 {
		for _, address := range []string{"10.77.0.1:41007", "[fd77::1]:41008"} {
			socket, err := network.ListenUDPAddrPort(netip.MustParseAddrPort(address))
			if err != nil {
				return err
			}
			defer socket.Close()
			go serve(func() error { return echoUDPSeries(socket, series) })
			expected++
		}
	}
	if os.Getenv("RIPDPI_AWG_INTEROP_DNS") == "1" {
		for _, address := range []string{"10.77.0.1:53", "[fd77::1]:53"} {
			socket, err := network.ListenUDPAddrPort(netip.MustParseAddrPort(address))
			if err != nil {
				return err
			}
			defer socket.Close()
			go serve(func() error { return answerDNS(socket) })
			expected++
		}
	}
	endpoint, err := bind.address()
	if err != nil {
		return err
	}
	if err = json.NewEncoder(os.Stdout).Encode(map[string]string{"endpoint": endpoint, "public_key": base64.StdEncoding.EncodeToString(server.PublicKey().Bytes())}); err != nil {
		return err
	}
	stop := make(chan struct{})
	go func() { bufio.NewScanner(os.Stdin).Scan(); close(stop) }()
	// Preserve the per-session deadline while one peer serves all reconnects.
	timer := time.NewTimer(time.Duration(cycles) * sessionWait)
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
			for completed < expected {
				select {
				case err := <-results:
					if err != nil {
						return err
					}
					completed++
				case <-timer.C:
					return fmt.Errorf("incomplete exchanges: %d/%d", completed, expected)
				}
			}
			if err := bind.observation.finishCounters(peer.IpcGet); err != nil {
				return err
			}
			return bind.observation.finish(cycles)
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
