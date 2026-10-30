package meter

import (
	"io"
	"strings"
	"testing"
)

func TestReader(t *testing.T) {
	var reported []int64
	r := &Reader{R: strings.NewReader(strings.Repeat("x", 10000)), Report: func(n int64) { reported = append(reported, n) }}
	// Wrapped so that the copy reads through the 100 byte buffer
	n, err := io.CopyBuffer(struct{ io.Writer }{io.Discard}, struct{ io.Reader }{r}, make([]byte, 100))
	if err != nil || n != 10000 || r.Sent != 10000 {
		t.Fatalf("copied %d bytes, counted %d: %v", n, r.Sent, err)
	}
	// The first read is reported at once, the other 100 or so fall within Interval
	if len(reported) == 0 || reported[0] != 100 || len(reported) > 3 {
		t.Fatalf("reported %v", reported)
	}
}
