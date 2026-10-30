// Package meter reports the progress of a transfer as it is read.
package meter

import (
	"io"
	"time"
)

// Interval is how often Reader reports at most.
const Interval = 500 * time.Millisecond

// Reader passes reads through to R and reports the bytes read so far to
// Report, at most every Interval.
type Reader struct {
	R      io.Reader
	Report func(bytes int64)
	// Sent is the number of bytes read so far
	Sent int64
	last time.Time
}

func (m *Reader) Read(b []byte) (int, error) {
	n, err := m.R.Read(b)
	m.Sent += int64(n)
	if now := time.Now(); now.Sub(m.last) >= Interval {
		m.last = now
		m.Report(m.Sent)
	}
	return n, err
}
