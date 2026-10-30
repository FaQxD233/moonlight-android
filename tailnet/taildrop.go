package tailnet

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"strings"
	"sync"
	"time"

	// Taildrop isn't linked into tsnet by default. This registers its extension,
	// the PeerAPI handler peers send files to (/v0/put/) and the LocalAPI file
	// endpoints used below. Received files wait in the state directory until
	// SaveWaitingFile moves them.
	"limelight/tailnet/internal/meter"
	_ "tailscale.com/feature/taildrop"
	"tailscale.com/tailcfg"
)

type waitingFile struct {
	Name string `json:"name"`
	Size int64  `json:"size"`
}

// AwaitWaitingFiles blocks until Taildrop has received files or timeoutSeconds
// pass, and returns them as JSON. It returns [] right away when the node isn't
// running or Taildrop isn't ready (for example before logging in).
func AwaitWaitingFiles(timeoutSeconds int) string {
	files := []waitingFile{}
	if s := currentServer(); s != nil {
		if lc, err := s.LocalClient(); err == nil {
			wait := time.Duration(timeoutSeconds) * time.Second
			ctx, cancel := context.WithTimeout(context.Background(), wait+10*time.Second)
			got, err := lc.AwaitWaitingFiles(ctx, wait)
			cancel()
			if err == nil {
				for _, f := range got {
					files = append(files, waitingFile{Name: f.Name, Size: f.Size})
				}
			}
		}
	}
	b, _ := json.Marshal(files)
	return string(b)
}

// SaveWaitingFile copies the received file name into fd, a writable descriptor
// the app opened for its destination, then removes it from Taildrop. fd is
// always closed.
func SaveWaitingFile(name string, fd int) error {
	out := os.NewFile(uintptr(fd), name)
	defer out.Close()

	s := currentServer()
	if s == nil {
		return errors.New("tailnet is not started")
	}
	lc, err := s.LocalClient()
	if err != nil {
		return err
	}
	ctx := context.Background()
	in, _, err := lc.GetWaitingFile(ctx, name)
	if err != nil {
		return err
	}
	defer in.Close()
	if _, err := io.Copy(out, in); err != nil {
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	return lc.DeleteWaitingFile(ctx, name)
}

type fileTarget struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// FileTargets lists the devices this node can send files to with Taildrop as
// JSON [{"id","name"}]: [] when there are none or the node isn't ready.
func FileTargets() string {
	targets := []fileTarget{}
	if s := currentServer(); s != nil {
		if lc, err := s.LocalClient(); err == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			got, err := lc.FileTargets(ctx)
			cancel()
			if err == nil {
				for _, t := range got {
					targets = append(targets, fileTarget{ID: string(t.Node.StableID), Name: deviceName(t.Node)})
				}
			}
		}
	}
	b, _ := json.Marshal(targets)
	return string(b)
}

// deviceName is the name Tailscale shows for n elsewhere, such as on the computer.
func deviceName(n *tailcfg.Node) string {
	if n.ComputedName != "" {
		return n.ComputedName
	}
	if n.Hostinfo.Valid() && n.Hostinfo.Hostname() != "" {
		return n.Hostinfo.Hostname()
	}
	name, _, _ := strings.Cut(n.Name, ".")
	return name
}

// Progress is told how many bytes of the file being sent have gone so far.
type Progress interface {
	Sent(bytes int64)
}

var (
	sendMu     sync.Mutex
	sendCtx    context.Context
	sendCancel context.CancelFunc
)

// SendFile sends size bytes (-1 if unknown) read from fd, a readable descriptor
// the app opened, to the device id as name with Taildrop. It reports progress
// every meter.Interval and when done. fd is always closed.
func SendFile(id, name string, fd int, size int64, progress Progress) error {
	in := os.NewFile(uintptr(fd), name)
	defer in.Close()

	s := currentServer()
	if s == nil {
		return errors.New("tailnet is not started")
	}
	lc, err := s.LocalClient()
	if err != nil {
		return err
	}
	r := &meter.Reader{R: in, Report: progress.Sent}
	err = lc.PushFile(sendContext(), tailcfg.StableNodeID(id), size, name, r)
	progress.Sent(r.Sent)
	return err
}

// sendContext is what CancelSends cancels.
func sendContext() context.Context {
	sendMu.Lock()
	defer sendMu.Unlock()
	if sendCtx == nil {
		sendCtx, sendCancel = context.WithCancel(context.Background())
	}
	return sendCtx
}

// CancelSends stops the files being sent; files sent after it go as usual.
func CancelSends() {
	sendMu.Lock()
	defer sendMu.Unlock()
	if sendCancel != nil {
		sendCancel()
		sendCtx, sendCancel = nil, nil
	}
}
