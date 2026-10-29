package tailnet

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"time"

	// Taildrop isn't linked into tsnet by default. This registers its extension,
	// the PeerAPI handler peers send files to (/v0/put/) and the LocalAPI file
	// endpoints used below. Received files wait in the state directory until
	// SaveWaitingFile moves them.
	_ "tailscale.com/feature/taildrop"
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
