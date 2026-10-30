package tailnet

import (
	"net/netip"
	"testing"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

func TestPingPath(t *testing.T) {
	tests := []struct {
		pr             ipnstate.PingResult
		path, endpoint string
	}{
		{ipnstate.PingResult{Endpoint: "192.168.1.10:41641"}, "direct", "192.168.1.10:41641"},
		{ipnstate.PingResult{DERPRegionID: 9, DERPRegionCode: "hkg"}, "relay", "hkg"},
		{ipnstate.PingResult{PeerRelay: "203.0.113.5:7777:vni:3", DERPRegionID: 9}, "peer-relay", "203.0.113.5:7777:vni:3"},
		{ipnstate.PingResult{}, "", ""},
	}
	for i, tt := range tests {
		if path, endpoint := pingPath(&tt.pr); path != tt.path || endpoint != tt.endpoint {
			t.Errorf("%d: pingPath = %q, %q; want %q, %q", i, path, endpoint, tt.path, tt.endpoint)
		}
	}
}

func TestPeerAddr(t *testing.T) {
	st := &ipnstate.Status{Peer: map[key.NodePublic]*ipnstate.PeerStatus{
		key.NewNode().Public(): {
			DNSName:      "desktop-s131gt6.example.ts.net.",
			TailscaleIPs: []netip.Addr{netip.MustParseAddr("fd7a:115c:a1e0::1"), netip.MustParseAddr("100.64.0.1")},
		},
	}}
	if addr, err := peerAddr(st, "Desktop-S131GT6.example.ts.net"); err != nil || addr.String() != "100.64.0.1" {
		t.Errorf("peerAddr = %v, %v; want 100.64.0.1", addr, err)
	}
	if _, err := peerAddr(st, "laptop.example.ts.net"); err == nil {
		t.Error("peerAddr found a device that isn't there")
	}
}

func TestOnlinePeers(t *testing.T) {
	const me, friend = tailcfg.UserID(1), tailcfg.UserID(2)
	st := &ipnstate.Status{
		Self: &ipnstate.PeerStatus{UserID: me},
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{
			key.NewNode().Public(): {
				DNSName: "desktop-s131gt6.example.ts.net.", HostName: "DESKTOP-S131GT6", OS: "windows", UserID: me, Online: true,
				TailscaleIPs: []netip.Addr{netip.MustParseAddr("fd7a:115c:a1e0::1"), netip.MustParseAddr("100.64.0.1")},
			},
			key.NewNode().Public(): {HostName: "friend-mac", OS: "macOS", UserID: friend, Online: true},
			key.NewNode().Public(): {HostName: "old-laptop", OS: "windows", UserID: me, Online: false},
		},
	}
	peers := onlinePeers(st)
	if len(peers) != 2 {
		t.Fatalf("got %+v, want the two online peers", peers)
	}
	for _, p := range peers {
		switch p.Name {
		case "desktop-s131gt6":
			if p.IPv4 != "100.64.0.1" || !p.Mine || p.OS != "windows" {
				t.Errorf("desktop: %+v", p)
			}
		case "friend-mac":
			if p.IPv4 != "" || p.Mine {
				t.Errorf("friend: %+v", p)
			}
		default:
			t.Errorf("unexpected peer %+v", p)
		}
	}
}
