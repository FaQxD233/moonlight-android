package tailnet

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	_ "unsafe" // for go:linkname

	"tailscale.com/feature/taildrop"
)

// Taildrop's file store (feature/taildrop/fileops_fs.go) is built for every
// platform except Android, where the Tailscale app supplies its own, so in this
// build taildrop calls a nil newFileOps and crashes the app as soon as a
// logged-in node starts. Received files only wait in the node's state directory
// until SaveWaitingFile moves them, so plain files there are all it needs.
//
//go:linkname taildropNewFileOps tailscale.com/feature/taildrop.newFileOps
var taildropNewFileOps func(dir string) (taildrop.FileOps, error)

func init() {
	// Keep Taildrop's own store if it has one
	if taildropNewFileOps == nil {
		taildropNewFileOps = newInbox
	}
}

// inbox implements taildrop.FileOps with the files of one directory.
type inbox struct {
	dir string
	// Held while Rename picks a free name and renames to it
	mu sync.Mutex
}

func newInbox(dir string) (taildrop.FileOps, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	return &inbox{dir: dir}, nil
}

// path returns where the file name is kept. Peers choose the names, which
// taildrop validates; this only makes sure a name can't leave the directory.
func (b *inbox) path(name string) (string, error) {
	if name == "." || !filepath.IsLocal(name) || filepath.Base(name) != name {
		return "", taildrop.ErrInvalidFileName
	}
	return filepath.Join(b.dir, name), nil
}

// OpenWriter opens name to write from offset on: what was already received
// before offset is kept (to resume a transfer), anything after it dropped.
func (b *inbox) OpenWriter(name string, offset int64, perm os.FileMode) (io.WriteCloser, string, error) {
	path, err := b.path(name)
	if err != nil {
		return nil, "", err
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY, perm)
	if err != nil {
		return nil, "", err
	}
	if err := seekTruncate(f, offset); err != nil {
		f.Close()
		return nil, "", err
	}
	return f, path, nil
}

func seekTruncate(f *os.File, offset int64) error {
	fi, err := f.Stat()
	if err != nil {
		return err
	}
	if offset < 0 || offset > fi.Size() {
		return fmt.Errorf("offset %d out of range", offset)
	}
	if err := f.Truncate(offset); err != nil {
		return err
	}
	_, err = f.Seek(offset, io.SeekStart)
	return err
}

func (b *inbox) Remove(name string) error {
	path, err := b.path(name)
	if err != nil {
		return err
	}
	return os.Remove(path)
}

// Rename moves a received file to newName, or to "newName (2)" and so on while
// a file of that name is still waiting.
func (b *inbox) Rename(oldPath, newName string) (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	for i := 1; i <= 100; i++ {
		path, err := b.path(numberedName(newName, i))
		if err != nil {
			return "", err
		}
		if _, err := os.Lstat(path); err == nil {
			continue
		} else if !errors.Is(err, fs.ErrNotExist) {
			return "", err
		}
		if err := os.Rename(oldPath, path); err != nil {
			return "", err
		}
		return path, nil
	}
	return "", fmt.Errorf("too many waiting files named %q", newName)
}

// numberedName returns name for n == 1, otherwise name with " (n)" before its extension.
func numberedName(name string, n int) string {
	if n == 1 {
		return name
	}
	ext := filepath.Ext(name)
	return strings.TrimSuffix(name, ext) + " (" + strconv.Itoa(n) + ")" + ext
}

func (b *inbox) ListFiles() ([]string, error) {
	entries, err := os.ReadDir(b.dir)
	if err != nil {
		return nil, err
	}
	var names []string
	for _, e := range entries {
		if e.Type().IsRegular() {
			names = append(names, e.Name())
		}
	}
	return names, nil
}

func (b *inbox) Stat(name string) (fs.FileInfo, error) {
	path, err := b.path(name)
	if err != nil {
		return nil, err
	}
	return os.Stat(path)
}

func (b *inbox) OpenReader(name string) (io.ReadCloser, error) {
	path, err := b.path(name)
	if err != nil {
		return nil, err
	}
	return os.Open(path)
}
