package localsend

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Receiver is implemented by the app to decide on and store incoming files.
type Receiver interface {
	// Accept asks whether to take the files described by offerJSON,
	// {"sender":{"alias","deviceModel","ip"},"files":[{"name","size","type"}]}.
	// The sender waits for the answer.
	Accept(offerJSON string) bool
	// Save stores one received file: it calls WriteUpload(token, fd) with a
	// writable descriptor for the destination and returns once it is stored.
	Save(token, name, fileType string, size int64) error
}

// A session idle for this long no longer blocks new ones
const sessionIdleTimeout = 5 * time.Minute

var (
	mu  sync.Mutex
	srv *server
)

func current() *server {
	mu.Lock()
	defer mu.Unlock()
	return srv
}

type server struct {
	info  deviceInfo
	recv  Receiver
	http  *http.Server
	mcast *net.UDPConn

	devicesMu sync.Mutex
	devices   map[string]*device // by fingerprint

	sessionMu sync.Mutex
	session   *session
	uploads   map[string]*upload // by token, while Receiver.Save runs
}

type session struct {
	id       string
	senderIP string
	files    map[string]fileInfo // by file id
	tokens   map[string]string   // file id -> token, removed once used
	left     int                 // files not received yet
	lastUse  time.Time
}

type upload struct {
	body        io.Reader
	file        fileInfo
	badChecksum bool
}

// Start runs the LocalSend server and discovery as this device, named alias,
// keeping its certificate in dir. recv decides on and stores incoming files.
func Start(alias, deviceModel, dir string, recv Receiver) error {
	mu.Lock()
	defer mu.Unlock()
	if srv != nil {
		return nil
	}
	cert, err := loadOrCreateCert(dir)
	if err != nil {
		return err
	}
	// Another LocalSend app on this phone may have the port; announcements carry whichever is used
	ln, err := net.Listen("tcp", ":"+strconv.Itoa(defaultPort))
	if err != nil {
		if ln, err = net.Listen("tcp", ":0"); err != nil {
			return err
		}
	}
	s := newServer(alias, deviceModel, cert, ln.Addr().(*net.TCPAddr).Port, recv)
	go s.http.Serve(tls.NewListener(ln, &tls.Config{Certificates: []tls.Certificate{cert}}))
	if err := s.startDiscovery(); err != nil {
		// Devices can still be found by scanning or added by address
		log.Printf("localsend: no multicast discovery: %v", err)
	}
	srv = s
	return nil
}

func newServer(alias, deviceModel string, cert tls.Certificate, port int, recv Receiver) *server {
	s := &server{
		info: deviceInfo{
			Alias:       alias,
			Version:     protocolVersion,
			DeviceModel: deviceModel,
			DeviceType:  "mobile",
			Fingerprint: fingerprint(cert.Certificate[0]),
			Port:        port,
			Protocol:    "https",
		},
		recv:    recv,
		devices: map[string]*device{},
		uploads: map[string]*upload{},
	}
	mux := http.NewServeMux()
	mux.HandleFunc("POST "+apiPrefix+"register", s.serveRegister)
	mux.HandleFunc("GET "+apiPrefix+"info", s.serveInfo)
	mux.HandleFunc("POST "+apiPrefix+"prepare-upload", s.servePrepareUpload)
	mux.HandleFunc("POST "+apiPrefix+"upload", s.serveUpload)
	mux.HandleFunc("POST "+apiPrefix+"cancel", s.serveCancel)
	s.http = &http.Server{Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	return s
}

// Stop ends the server and discovery.
func Stop() {
	mu.Lock()
	s := srv
	srv = nil
	mu.Unlock()
	if s != nil {
		s.http.Close()
		if s.mcast != nil {
			s.mcast.Close()
		}
	}
}

func (s *server) serveRegister(w http.ResponseWriter, r *http.Request) {
	var info deviceInfo
	if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&info); err != nil {
		http.Error(w, "Invalid body", http.StatusBadRequest)
		return
	}
	s.addDevice(info, remoteIP(r))
	writeJSON(w, s.info)
}

func (s *server) serveInfo(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, s.info)
}

func (s *server) servePrepareUpload(w http.ResponseWriter, r *http.Request) {
	var req prepareUploadRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 4<<20)).Decode(&req); err != nil || len(req.Files) == 0 {
		http.Error(w, "Invalid body", http.StatusBadRequest)
		return
	}
	ip := remoteIP(r)

	// One transfer at a time; held while the user decides
	s.sessionMu.Lock()
	if s.session != nil && time.Since(s.session.lastUse) < sessionIdleTimeout {
		s.sessionMu.Unlock()
		http.Error(w, "Blocked by another session", http.StatusConflict)
		return
	}
	sess := &session{id: randomID(), senderIP: ip, files: map[string]fileInfo{}, tokens: map[string]string{}, lastUse: time.Now()}
	s.session = sess
	s.sessionMu.Unlock()

	s.addDevice(req.Info, ip)
	if !s.recv.Accept(offer(req, ip)) {
		s.endSession(sess)
		http.Error(w, "Rejected", http.StatusForbidden)
		return
	}

	resp := prepareUploadResponse{SessionID: sess.id, Files: map[string]string{}}
	s.sessionMu.Lock()
	for id, f := range req.Files {
		f.ID = id
		token := randomID()
		sess.files[id] = f
		sess.tokens[id] = token
		resp.Files[id] = token
	}
	sess.left = len(sess.files)
	sess.lastUse = time.Now()
	s.sessionMu.Unlock()
	writeJSON(w, resp)
}

// offer describes a prepare-upload request for Receiver.Accept.
func offer(req prepareUploadRequest, ip string) string {
	type offerFile struct {
		Name string `json:"name"`
		Size int64  `json:"size"`
		Type string `json:"type"`
	}
	var o struct {
		Sender struct {
			Alias       string `json:"alias"`
			DeviceModel string `json:"deviceModel"`
			IP          string `json:"ip"`
		} `json:"sender"`
		Files []offerFile `json:"files"`
	}
	o.Sender.Alias, o.Sender.DeviceModel, o.Sender.IP = req.Info.Alias, req.Info.DeviceModel, ip
	for _, f := range req.Files {
		o.Files = append(o.Files, offerFile{f.FileName, f.Size, f.FileType})
	}
	b, _ := json.Marshal(o)
	return string(b)
}

func (s *server) serveUpload(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	sessionID, fileID, token := q.Get("sessionId"), q.Get("fileId"), q.Get("token")
	if sessionID == "" || fileID == "" || token == "" {
		http.Error(w, "Missing parameters", http.StatusBadRequest)
		return
	}
	s.sessionMu.Lock()
	sess := s.session
	ok := sess != nil && sess.id == sessionID && sess.tokens[fileID] == token && sess.senderIP == remoteIP(r)
	var up *upload
	if ok {
		// Each token works once
		delete(sess.tokens, fileID)
		sess.lastUse = time.Now()
		up = &upload{body: r.Body, file: sess.files[fileID]}
		s.uploads[token] = up
	}
	s.sessionMu.Unlock()
	if !ok {
		http.Error(w, "Invalid token or IP address", http.StatusForbidden)
		return
	}

	err := s.recv.Save(token, up.file.FileName, up.file.FileType, up.file.Size)
	s.sessionMu.Lock()
	delete(s.uploads, token)
	sess.left--
	sess.lastUse = time.Now()
	if sess.left <= 0 && s.session == sess {
		s.session = nil
	}
	s.sessionMu.Unlock()
	switch {
	case up.badChecksum:
		http.Error(w, "Checksum mismatch", http.StatusUnprocessableEntity)
	case err != nil:
		http.Error(w, err.Error(), http.StatusInternalServerError)
	}
}

func (s *server) serveCancel(w http.ResponseWriter, r *http.Request) {
	s.sessionMu.Lock()
	if sess := s.session; sess != nil && sess.id == r.URL.Query().Get("sessionId") && sess.senderIP == remoteIP(r) {
		s.session = nil
	}
	s.sessionMu.Unlock()
}

func (s *server) endSession(sess *session) {
	s.sessionMu.Lock()
	if s.session == sess {
		s.session = nil
	}
	s.sessionMu.Unlock()
}

// WriteUpload copies the file being received for token, while Receiver.Save
// runs, into fd, a writable descriptor, which it always closes.
func WriteUpload(token string, fd int) error {
	out := os.NewFile(uintptr(fd), token)
	defer out.Close()
	s := current()
	if s == nil {
		return errors.New("localsend is not running")
	}
	s.sessionMu.Lock()
	up := s.uploads[token]
	s.sessionMu.Unlock()
	if up == nil {
		return errors.New("no upload for this token")
	}
	sum := sha256.New()
	n, err := io.Copy(io.MultiWriter(out, sum), up.body)
	if err != nil {
		return err
	}
	if n != up.file.Size {
		return fmt.Errorf("received %d of %d bytes", n, up.file.Size)
	}
	if up.file.SHA256 != "" && !strings.EqualFold(hex.EncodeToString(sum.Sum(nil)), up.file.SHA256) {
		up.badChecksum = true
		return errors.New("checksum mismatch")
	}
	return out.Close()
}

func remoteIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func randomID() string {
	b := make([]byte, 16)
	rand.Read(b)
	return hex.EncodeToString(b)
}
