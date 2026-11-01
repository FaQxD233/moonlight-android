//go:build unix

package localsend

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/asn1"
	"encoding/json"
	"errors"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"
)

// testReceiver accepts or declines every offer and keeps what it receives.
type testReceiver struct {
	accept bool
	dir    string
	mu     sync.Mutex
	got    map[string]string // file name -> content
	offers []string
}

func (r *testReceiver) Accept(offerJSON string) bool {
	r.mu.Lock()
	r.offers = append(r.offers, offerJSON)
	r.mu.Unlock()
	return r.accept
}

func (r *testReceiver) Save(token, name, fileType string, size int64) error {
	path := filepath.Join(r.dir, name)
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	// WriteUpload closes the descriptor it gets
	fd, err := syscall.Dup(int(f.Fd()))
	if err != nil {
		return err
	}
	if err := WriteUpload(token, fd); err != nil {
		return err
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	r.mu.Lock()
	r.got[name] = string(b)
	r.mu.Unlock()
	return nil
}

// testFiles sends the files at paths.
type testFiles struct {
	paths []string
	sent  map[int]int64
}

func (f *testFiles) Open(index int) (int, error) {
	file, err := os.Open(f.paths[index])
	if err != nil {
		return 0, err
	}
	defer file.Close()
	return syscall.Dup(int(file.Fd()))
}

func (f *testFiles) Sent(index int, bytes int64) { f.sent[index] = bytes }

// testServer is a LocalSend server on a loopback port, as this device, that takes
// connections the way LocalSend's own server does; see localSendTLS.
type testServer struct {
	*server
	mu        sync.Mutex
	presented []string // fingerprints of the client certificates presented
}

func startTestServer(t *testing.T, recv Receiver) *testServer {
	t.Helper()
	cert, err := loadOrCreateCert(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := &testServer{server: newServer("Test phone", "Go", cert, ln.Addr().(*net.TCPAddr).Port, recv)}
	go s.http.Serve(tls.NewListener(ln, localSendTLS(t, cert, func(fp string) {
		s.mu.Lock()
		s.presented = append(s.presented, fp)
		s.mu.Unlock()
	})))
	mu.Lock()
	srv = s.server
	mu.Unlock()
	t.Cleanup(func() {
		mu.Lock()
		srv = nil
		mu.Unlock()
		s.http.Close()
	})
	return s
}

// localSendTLS is how LocalSend's own server (rustls, from its Rust core on) takes
// connections: senders must present a certificate, and the certificate request names
// LocalSend's own certificate, "LocalSend User" as a UTF8String, as the issuer to use.
// presented hears the fingerprint of each certificate presented.
func localSendTLS(t *testing.T, cert tls.Certificate, presented func(fp string)) *tls.Config {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	name, err := asn1.Marshal(pkix.RDNSequence{{{Type: asn1.ObjectIdentifier{2, 5, 4, 3},
		Value: asn1.RawValue{Tag: asn1.TagUTF8String, Bytes: []byte("LocalSend User")}}}})
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), RawSubject: name,
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour)}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	issuer, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	issuers := x509.NewCertPool()
	issuers.AddCert(issuer)
	return &tls.Config{
		Certificates: []tls.Certificate{cert},
		ClientAuth:   tls.RequireAnyClientCert,
		ClientCAs:    issuers,
		VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
			presented(fingerprint(raw[0]))
			return nil
		},
	}
}

func writeTestFiles(t *testing.T, contents map[string]string) (paths []string, filesJSON string) {
	t.Helper()
	dir := t.TempDir()
	var list []sendFile
	for name, content := range contents {
		path := filepath.Join(dir, name)
		if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
		paths = append(paths, path)
		list = append(list, sendFile{Name: name, Size: int64(len(content)), Type: "text/plain"})
	}
	b, _ := json.Marshal(list)
	return paths, string(b)
}

func TestSendAndReceive(t *testing.T) {
	recv := &testReceiver{accept: true, dir: t.TempDir(), got: map[string]string{}}
	s := startTestServer(t, recv)
	want := map[string]string{"a photo.jpg": strings.Repeat("x", 300000), "empty.txt": ""}
	paths, filesJSON := writeTestFiles(t, want)
	files := &testFiles{paths: paths, sent: map[int]int64{}}

	if err := Send("127.0.0.1", s.info.Port, "https", s.info.Fingerprint, filesJSON, files); err != nil {
		t.Fatalf("Send: %v", err)
	}
	for name, content := range want {
		if got, ok := recv.got[name]; !ok || got != content {
			t.Errorf("%s: received %d bytes (present: %v), want %d", name, len(got), ok, len(content))
		}
	}
	for i, path := range paths {
		fi, _ := os.Stat(path)
		if files.sent[i] != fi.Size() {
			t.Errorf("file %d: reported %d bytes sent, want %d", i, files.sent[i], fi.Size())
		}
	}
	if len(recv.offers) != 1 || !strings.Contains(recv.offers[0], `"alias":"Test phone"`) {
		t.Errorf("offers = %q", recv.offers)
	}
	// The sender presented this device's certificate on every connection
	s.mu.Lock()
	presented := s.presented
	s.mu.Unlock()
	if len(presented) == 0 {
		t.Error("no client certificate was presented")
	}
	for _, fp := range presented {
		if fp != s.info.Fingerprint {
			t.Errorf("presented certificate %s, want this device's %s", fp, s.info.Fingerprint)
		}
	}
	// The session is over, so another transfer may start
	if s.session != nil {
		t.Error("session still open after every file arrived")
	}
}

func TestSendDeclined(t *testing.T) {
	s := startTestServer(t, &testReceiver{accept: false})
	paths, filesJSON := writeTestFiles(t, map[string]string{"a.txt": "hello"})
	err := Send("127.0.0.1", s.info.Port, "https", s.info.Fingerprint, filesJSON, &testFiles{paths: paths, sent: map[int]int64{}})
	if !errors.Is(err, errRejected) {
		t.Fatalf("Send = %v, want errRejected", err)
	}
}

func TestSendChecksFingerprint(t *testing.T) {
	recv := &testReceiver{accept: true, dir: t.TempDir(), got: map[string]string{}}
	s := startTestServer(t, recv)
	paths, filesJSON := writeTestFiles(t, map[string]string{"a.txt": "hello"})
	wrong := strings.Repeat("A", 64)
	if err := Send("127.0.0.1", s.info.Port, "https", wrong, filesJSON, &testFiles{paths: paths, sent: map[int]int64{}}); err == nil {
		t.Fatal("Send succeeded to a device with another certificate")
	}
	if len(recv.offers) != 0 {
		t.Error("the offer reached a device with the wrong fingerprint")
	}
}

func TestUploadNeedsToken(t *testing.T) {
	s := startTestServer(t, &testReceiver{accept: true})
	resp, err := s.httpClient("").Post(baseURL("https", "127.0.0.1", s.info.Port)+"upload?sessionId=x&fileId=0&token=y",
		"application/octet-stream", strings.NewReader("data"))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("upload without a session = HTTP %d, want 403", resp.StatusCode)
	}
}

func TestRegister(t *testing.T) {
	s := startTestServer(t, &testReceiver{accept: true})
	peer := deviceInfo{Alias: "Desktop", Version: "2.1", DeviceType: "desktop", Fingerprint: "PEER", Port: 53317, Protocol: "https"}
	body, _ := json.Marshal(peer)
	resp, err := s.httpClient(s.info.Fingerprint).Post(baseURL("https", "127.0.0.1", s.info.Port)+"register",
		"application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var answer deviceInfo
	if err := json.NewDecoder(resp.Body).Decode(&answer); err != nil || answer.Alias != "Test phone" || answer.Fingerprint != s.info.Fingerprint {
		t.Fatalf("register answered %+v, %v", answer, err)
	}
	if devices := DevicesJSON(); !strings.Contains(devices, `"alias":"Desktop"`) || !strings.Contains(devices, `"ip":"127.0.0.1"`) {
		t.Fatalf("DevicesJSON = %s", devices)
	}
}

func TestCertificateIsKept(t *testing.T) {
	dir := t.TempDir()
	first, err := loadOrCreateCert(dir)
	if err != nil {
		t.Fatal(err)
	}
	again, err := loadOrCreateCert(dir)
	if err != nil {
		t.Fatal(err)
	}
	fp := fingerprint(first.Certificate[0])
	if fingerprint(again.Certificate[0]) != fp {
		t.Fatal("a new certificate was made instead of reusing the stored one")
	}
	if !regexp.MustCompile(`^[0-9A-F]{64}$`).MatchString(fp) {
		t.Fatalf("fingerprint %q is not 64 uppercase hex digits", fp)
	}
}
