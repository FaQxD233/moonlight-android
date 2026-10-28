package tailnet

import (
	"context"
	"io"
	"log"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/tsnet"
)

const (
	dialTimeout = 15 * time.Second
	// A client socket's UDP relay is dropped after this long without traffic.
	udpIdleTimeout = 2 * time.Minute
	// Large enough for a whole video frame to arrive while the copy loop catches up.
	udpSocketBuffer = 4 << 20
)

// relayGroup relays ports on one loopback address to one tailnet host.
type relayGroup struct {
	srv    *tsnet.Server
	local  string
	remote string
	ctx    context.Context
	cancel context.CancelFunc

	mu           sync.Mutex
	tcpListeners map[int]net.Listener
	udpRelays    map[int]*udpRelay
}

func newRelayGroup(srv *tsnet.Server, local, remote string) *relayGroup {
	ctx, cancel := context.WithCancel(context.Background())
	return &relayGroup{
		srv:          srv,
		local:        local,
		remote:       remote,
		ctx:          ctx,
		cancel:       cancel,
		tcpListeners: map[int]net.Listener{},
		udpRelays:    map[int]*udpRelay{},
	}
}

func (g *relayGroup) target(port int) string {
	return net.JoinHostPort(g.remote, strconv.Itoa(port))
}

func (g *relayGroup) forwardTCP(port int) error {
	g.mu.Lock()
	defer g.mu.Unlock()
	if _, ok := g.tcpListeners[port]; ok {
		return nil
	}
	ln, err := net.Listen("tcp", net.JoinHostPort(g.local, strconv.Itoa(port)))
	if err != nil {
		return err
	}
	g.tcpListeners[port] = ln
	target := g.target(port)
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go g.relayTCP(conn, target)
		}
	}()
	return nil
}

func (g *relayGroup) relayTCP(local net.Conn, target string) {
	defer local.Close()
	ctx, cancel := context.WithTimeout(g.ctx, dialTimeout)
	remote, err := g.srv.Dial(ctx, "tcp", target)
	cancel()
	if err != nil {
		log.Printf("tailnet: dial tcp %s: %v", target, err)
		return
	}
	defer remote.Close()

	done := make(chan struct{}, 2)
	go func() { io.Copy(remote, local); done <- struct{}{} }()
	go func() { io.Copy(local, remote); done <- struct{}{} }()
	// The HTTP and RTSP clients never half-close, so either side finishing ends
	// the exchange; closing both unblocks the other copy.
	<-done
	local.Close()
	remote.Close()
	<-done
}

// udpRelay relays datagrams between local client sockets and one tailnet port,
// using a separate tailnet socket per client so replies return to the right one.
type udpRelay struct {
	g      *relayGroup
	pc     *net.UDPConn
	target string

	mu    sync.Mutex
	peers map[string]*udpPeer
}

type udpPeer struct {
	addr     net.Addr
	conn     net.Conn
	lastSeen atomic.Int64
}

func (g *relayGroup) forwardUDP(port int) error {
	g.mu.Lock()
	defer g.mu.Unlock()
	if _, ok := g.udpRelays[port]; ok {
		return nil
	}
	addr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(g.local, strconv.Itoa(port)))
	if err != nil {
		return err
	}
	pc, err := net.ListenUDP("udp", addr)
	if err != nil {
		return err
	}
	pc.SetReadBuffer(udpSocketBuffer)
	pc.SetWriteBuffer(udpSocketBuffer)
	r := &udpRelay{g: g, pc: pc, target: g.target(port), peers: map[string]*udpPeer{}}
	g.udpRelays[port] = r
	go r.readLocal()
	go r.expireIdle()
	return nil
}

func (r *udpRelay) readLocal() {
	buf := make([]byte, 65535)
	for {
		n, addr, err := r.pc.ReadFrom(buf)
		if err != nil {
			return
		}
		if p := r.peer(addr); p != nil {
			p.lastSeen.Store(time.Now().UnixNano())
			p.conn.Write(buf[:n])
		}
	}
}

// peer returns the tailnet socket for a local client, dialing it on first use.
// Only readLocal calls it, so two dials for one client can't race.
func (r *udpRelay) peer(addr net.Addr) *udpPeer {
	key := addr.String()
	r.mu.Lock()
	p := r.peers[key]
	r.mu.Unlock()
	if p != nil {
		return p
	}

	ctx, cancel := context.WithTimeout(r.g.ctx, dialTimeout)
	conn, err := r.g.srv.Dial(ctx, "udp", r.target)
	cancel()
	if err != nil {
		log.Printf("tailnet: dial udp %s: %v", r.target, err)
		return nil
	}
	p = &udpPeer{addr: addr, conn: conn}
	p.lastSeen.Store(time.Now().UnixNano())
	r.mu.Lock()
	r.peers[key] = p
	r.mu.Unlock()
	go r.readRemote(p)
	return p
}

func (r *udpRelay) readRemote(p *udpPeer) {
	buf := make([]byte, 65535)
	for {
		n, err := p.conn.Read(buf)
		if err != nil {
			return
		}
		p.lastSeen.Store(time.Now().UnixNano())
		r.pc.WriteTo(buf[:n], p.addr)
	}
}

func (r *udpRelay) expireIdle() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-r.g.ctx.Done():
			return
		case <-ticker.C:
		}
		cutoff := time.Now().Add(-udpIdleTimeout).UnixNano()
		r.mu.Lock()
		for key, p := range r.peers {
			if p.lastSeen.Load() < cutoff {
				p.conn.Close()
				delete(r.peers, key)
			}
		}
		r.mu.Unlock()
	}
}

func (r *udpRelay) close() {
	r.pc.Close()
	r.mu.Lock()
	for key, p := range r.peers {
		p.conn.Close()
		delete(r.peers, key)
	}
	r.mu.Unlock()
}

func (g *relayGroup) close() {
	g.cancel()
	g.mu.Lock()
	defer g.mu.Unlock()
	for port, ln := range g.tcpListeners {
		ln.Close()
		delete(g.tcpListeners, port)
	}
	for port, r := range g.udpRelays {
		r.close()
		delete(g.udpRelays, port)
	}
}
