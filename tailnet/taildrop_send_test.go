package tailnet

import (
	"testing"

	"tailscale.com/tailcfg"
)

func TestDeviceName(t *testing.T) {
	tests := []struct {
		node tailcfg.Node
		want string
	}{
		{tailcfg.Node{ComputedName: "desktop-s131gt6", Name: "desktop-s131gt6.example.ts.net."}, "desktop-s131gt6"},
		{tailcfg.Node{Hostinfo: (&tailcfg.Hostinfo{Hostname: "DESKTOP-S131GT6"}).View()}, "DESKTOP-S131GT6"},
		{tailcfg.Node{Name: "laptop.example.ts.net."}, "laptop"},
	}
	for i, tt := range tests {
		if got := deviceName(&tt.node); got != tt.want {
			t.Errorf("%d: deviceName = %q, want %q", i, got, tt.want)
		}
	}
}
