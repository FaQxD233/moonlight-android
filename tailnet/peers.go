package tailnet

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
)

type peerInfo struct {
	Name string `json:"name"`
	OS   string `json:"os"`
	IPv4 string `json:"ipv4,omitempty"`
	// Owned by this node's user, rather than shared with it or tagged
	Mine bool `json:"mine"`
}

// PeersJSON lists the other devices on the tailnet that are online, from
// tailscale status, as JSON [{"name","os","ipv4","mine"}]; [] when the node
// isn't running.
func PeersJSON() string {
	peers := []peerInfo{}
	if s := currentServer(); s != nil {
		if lc, err := s.LocalClient(); err == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			st, err := lc.Status(ctx)
			cancel()
			if err == nil {
				peers = onlinePeers(st)
			}
		}
	}
	b, _ := json.Marshal(peers)
	return string(b)
}

func onlinePeers(st *ipnstate.Status) []peerInfo {
	peers := []peerInfo{}
	for _, ps := range st.Peer {
		if !ps.Online {
			continue
		}
		p := peerInfo{Name: peerName(ps), OS: ps.OS, Mine: st.Self != nil && ps.UserID == st.Self.UserID}
		for _, ip := range ps.TailscaleIPs {
			if ip.Is4() {
				p.IPv4 = ip.String()
				break
			}
		}
		peers = append(peers, p)
	}
	return peers
}

// peerName is the first label of the MagicDNS name, as tailscale status shows it.
func peerName(ps *ipnstate.PeerStatus) string {
	if name, _, _ := strings.Cut(ps.DNSName, "."); name != "" {
		return name
	}
	return ps.HostName
}

// ProbeTCP reports whether something accepts TCP connections on host:port
// over the tailnet within timeoutMillis. It connects directly, without a relay.
func ProbeTCP(host string, port int, timeoutMillis int) bool {
	s := currentServer()
	if s == nil {
		return false
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMillis)*time.Millisecond)
	defer cancel()
	c, err := s.Dial(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return false
	}
	c.Close()
	return true
}

type pingInfo struct {
	// direct, relay (over DERP) or peer-relay, the terms tailscale status uses
	Path string `json:"path,omitempty"`
	// The direct ip:port, the DERP region code or the peer relay's ip:port:vni
	Endpoint  string  `json:"endpoint,omitempty"`
	LatencyMs float64 `json:"latencyMs,omitempty"`
	Error     string  `json:"error,omitempty"`
}

// PingJSON sends one disco ping to host (a Tailscale address or MagicDNS name),
// like tailscale ping, and reports the path the pong took and its latency as
// JSON {"path","endpoint","latencyMs","error"}.
func PingJSON(host string, timeoutMillis int) string {
	var info pingInfo
	if err := ping(host, timeoutMillis, &info); err != nil {
		info.Error = err.Error()
	}
	b, _ := json.Marshal(info)
	return string(b)
}

func ping(host string, timeoutMillis int, info *pingInfo) error {
	s := currentServer()
	if s == nil {
		return errors.New("tailnet is not started")
	}
	lc, err := s.LocalClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMillis)*time.Millisecond)
	defer cancel()
	addr, parseErr := netip.ParseAddr(host)
	if parseErr != nil {
		// A MagicDNS name: ping the peer it names
		st, err := lc.Status(ctx)
		if err != nil {
			return err
		}
		if addr, err = peerAddr(st, host); err != nil {
			return err
		}
	}
	pr, err := lc.Ping(ctx, addr, tailcfg.PingDisco)
	if err != nil {
		return err
	}
	if pr.Err != "" {
		return errors.New(pr.Err)
	}
	info.Path, info.Endpoint = pingPath(pr)
	info.LatencyMs = pr.LatencySeconds * 1000
	return nil
}

// peerAddr is the Tailscale address (IPv4 if it has one) of the peer whose
// MagicDNS name is host.
func peerAddr(st *ipnstate.Status, host string) (netip.Addr, error) {
	host = strings.TrimSuffix(host, ".")
	for _, ps := range st.Peer {
		if !strings.EqualFold(strings.TrimSuffix(ps.DNSName, "."), host) || len(ps.TailscaleIPs) == 0 {
			continue
		}
		for _, ip := range ps.TailscaleIPs {
			if ip.Is4() {
				return ip, nil
			}
		}
		return ps.TailscaleIPs[0], nil
	}
	return netip.Addr{}, fmt.Errorf("no tailnet device is called %s", host)
}

// pingPath names the path a pong took the way tailscale ping decides it.
func pingPath(pr *ipnstate.PingResult) (path, endpoint string) {
	switch {
	case pr.PeerRelay != "":
		return "peer-relay", pr.PeerRelay
	case pr.DERPRegionID != 0:
		return "relay", pr.DERPRegionCode
	case pr.Endpoint != "":
		return "direct", pr.Endpoint
	}
	return "", ""
}
