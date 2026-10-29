package tailnet

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"slices"
	"testing"

	"tailscale.com/feature/taildrop"
)

// On the platforms tests run on, taildrop has its own store, which init keeps.
// Getting the inbox here would mean the linkname no longer reaches taildrop's
// variable (after an upgrade, say), which would bring the Android crash back.
func TestTaildropKeepsItsFileStore(t *testing.T) {
	ops, err := taildropNewFileOps(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := ops.(*inbox); ok {
		t.Fatal("tailscale.com/feature/taildrop.newFileOps is not linked; update taildrop_inbox.go")
	}
}

func TestInbox(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "files", "ci-example.com-uid-1")
	ops, err := newInbox(dir)
	if err != nil {
		t.Fatal(err)
	}
	write := func(name string, offset int64, data string) string {
		t.Helper()
		w, path, err := ops.OpenWriter(name, offset, 0o666)
		if err != nil {
			t.Fatalf("OpenWriter(%q, %d): %v", name, offset, err)
		}
		if _, err := io.WriteString(w, data); err != nil {
			t.Fatal(err)
		}
		if err := w.Close(); err != nil {
			t.Fatal(err)
		}
		return path
	}
	read := func(name string) string {
		t.Helper()
		r, err := ops.OpenReader(name)
		if err != nil {
			t.Fatalf("OpenReader(%q): %v", name, err)
		}
		defer r.Close()
		b, err := io.ReadAll(r)
		if err != nil {
			t.Fatal(err)
		}
		return string(b)
	}

	// A resumed transfer keeps what arrived before the offset
	partial := write("a.txt.partial", 0, "hello there")
	write("a.txt.partial", 6, "world")
	if got := read("a.txt.partial"); got != "hello world" {
		t.Fatalf("resumed file = %q", got)
	}
	if _, _, err := ops.OpenWriter("a.txt.partial", 100, 0o666); err == nil {
		t.Fatal("OpenWriter past the end of the file succeeded")
	}

	// A finished file never replaces one that is still waiting
	if path, err := ops.Rename(partial, "a.txt"); err != nil || path != filepath.Join(dir, "a.txt") {
		t.Fatalf("Rename = %q, %v", path, err)
	}
	again := write("a.txt.partial", 0, "again")
	if path, err := ops.Rename(again, "a.txt"); err != nil || path != filepath.Join(dir, "a (2).txt") {
		t.Fatalf("second Rename = %q, %v", path, err)
	}
	names, err := ops.ListFiles()
	if err != nil {
		t.Fatal(err)
	}
	slices.Sort(names)
	if want := []string{"a (2).txt", "a.txt"}; !slices.Equal(names, want) {
		t.Fatalf("ListFiles = %q, want %q", names, want)
	}
	if got := read("a.txt"); got != "hello world" {
		t.Fatalf("a.txt = %q", got)
	}
	if fi, err := ops.Stat("a (2).txt"); err != nil || fi.Size() != int64(len("again")) {
		t.Fatalf("Stat = %v, %v", fi, err)
	}

	if err := ops.Remove("a.txt"); err != nil {
		t.Fatal(err)
	}
	// taildrop treats a missing file as already deleted
	if err := ops.Remove("a.txt"); !os.IsNotExist(err) {
		t.Fatalf("Remove of a missing file = %v", err)
	}

	for _, name := range []string{"", ".", "..", "../a.txt", "sub/a.txt", "/etc/hosts"} {
		if _, err := ops.OpenReader(name); !errors.Is(err, taildrop.ErrInvalidFileName) {
			t.Errorf("OpenReader(%q) = %v, want ErrInvalidFileName", name, err)
		}
	}
}
