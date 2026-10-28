// Package tailnet embeds a Tailscale node (tsnet) in the Android app and relays
// loopback ports to hosts on the tailnet, so streams can reach a Tailscale host
// without the Tailscale app or a VPN. It is bound to Java with gomobile; see
// build.sh and com.limelight.tailnet.TailnetManager.
package tailnet

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/logtail"
	"tailscale.com/tsnet"
)

var (
	mu     sync.Mutex
	server *tsnet.Server
	groups = map[string]*relayGroup{}
)

// Start creates the node and connects it in the background. controlURL and
// authKey may be empty; without a stored login or an auth key, StatusJSON
// reports an AuthURL to open in a browser.
func Start(dir, hostname, controlURL, authKey string) error {
	mu.Lock()
	defer mu.Unlock()
	if server != nil {
		return nil
	}
	// Keep this node's logs on the device instead of uploading them to log.tailscale.com.
	logtail.Disable()
	s := &tsnet.Server{
		Dir:        dir,
		Hostname:   hostname,
		ControlURL: controlURL,
		AuthKey:    authKey,
		UserLogf:   log.Printf,
	}
	if err := s.Start(); err != nil {
		return err
	}
	server = s
	return nil
}

// Stop disconnects the node and closes every relay; the login is kept in dir.
func Stop() {
	mu.Lock()
	s := server
	closing := groups
	server = nil
	groups = map[string]*relayGroup{}
	mu.Unlock()

	for _, g := range closing {
		g.close()
	}
	if s != nil {
		s.Close()
	}
}

// Logout removes the node from the tailnet if it is connected, then deletes
// its state so the next Start logs in from scratch.
func Logout(dir string) error {
	mu.Lock()
	s := server
	mu.Unlock()

	if s != nil {
		if lc, err := s.LocalClient(); err == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			// Best effort: the local state is deleted either way.
			_ = lc.Logout(ctx)
			cancel()
		}
	}
	Stop()
	return os.RemoveAll(dir)
}

type status struct {
	State   string   `json:"state"`
	AuthURL string   `json:"authURL,omitempty"`
	IPs     []string `json:"ips,omitempty"`
	Name    string   `json:"name,omitempty"`
	Error   string   `json:"error,omitempty"`
}

// StatusJSON reports the node as JSON. state is a Tailscale backend state
// (NeedsLogin, Starting, Running, ...) or Stopped when the node isn't started.
func StatusJSON() string {
	mu.Lock()
	s := server
	mu.Unlock()

	st := status{State: "Stopped"}
	if s != nil {
		st.State = "NoState"
		if err := fillStatus(s, &st); err != nil {
			st.Error = err.Error()
		}
	}
	b, _ := json.Marshal(st)
	return string(b)
}

func fillStatus(s *tsnet.Server, st *status) error {
	lc, err := s.LocalClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	ss, err := lc.StatusWithoutPeers(ctx)
	if err != nil {
		return err
	}
	st.State = ss.BackendState
	st.AuthURL = ss.AuthURL
	for _, ip := range ss.TailscaleIPs {
		st.IPs = append(st.IPs, ip.String())
	}
	if ss.Self != nil {
		st.Name = strings.TrimSuffix(ss.Self.DNSName, ".")
	}
	return nil
}

// Forward relays the comma separated TCP and UDP ports on localIP to the same
// ports on remoteHost over the tailnet. Ports that are already relayed are kept.
func Forward(localIP, remoteHost, tcpPorts, udpPorts string) error {
	mu.Lock()
	defer mu.Unlock()
	if server == nil {
		return errors.New("tailnet is not started")
	}
	g := groups[localIP]
	if g == nil {
		g = newRelayGroup(server, localIP, remoteHost)
		groups[localIP] = g
	}
	for _, port := range splitPorts(tcpPorts) {
		if err := g.forwardTCP(port); err != nil {
			return err
		}
	}
	for _, port := range splitPorts(udpPorts) {
		if err := g.forwardUDP(port); err != nil {
			return err
		}
	}
	return nil
}

func splitPorts(list string) []int {
	var ports []int
	for _, field := range strings.Split(list, ",") {
		port, err := strconv.Atoi(strings.TrimSpace(field))
		if err == nil && port > 0 && port < 65536 {
			ports = append(ports, port)
		}
	}
	return ports
}
