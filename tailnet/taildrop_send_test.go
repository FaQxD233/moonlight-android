package tailnet

import (
	"io"
	"strings"
	"testing"

	"tailscale.com/tailcfg"
)

type recordedProgress []int64

func (r *recordedProgress) Sent(bytes int64) { *r = append(*r, bytes) }

func TestProgressReader(t *testing.T) {
	var reported recordedProgress
	r := &progressReader{r: strings.NewReader(strings.Repeat("x", 10000)), progress: &reported}
	// Wrapped so that the copy reads through the 100 byte buffer
	n, err := io.CopyBuffer(struct{ io.Writer }{io.Discard}, struct{ io.Reader }{r}, make([]byte, 100))
	if err != nil || n != 10000 || r.sent != 10000 {
		t.Fatalf("copied %d bytes, counted %d: %v", n, r.sent, err)
	}
	// The first read is reported at once, the other 100 or so fall within progressInterval
	if len(reported) == 0 || reported[0] != 100 || len(reported) > 3 {
		t.Fatalf("reported %v", reported)
	}
}

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
