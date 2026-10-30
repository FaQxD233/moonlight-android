package localsend

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"sync"
	"time"

	"limelight/tailnet/internal/meter"
)

// Files gives Send the files to send and hears how far each has got.
type Files interface {
	// Open returns a readable descriptor for file index, which Send closes.
	Open(index int) (int, error)
	// Sent reports how many bytes of file index have been sent so far.
	Sent(index int, bytes int64)
}

type sendFile struct {
	Name string `json:"name"`
	Size int64  `json:"size"`
	Type string `json:"type"`
}

// errRejected is what Send returns when the device declines the files.
var errRejected = errors.New("the device declined the files")

var (
	sendMu     sync.Mutex
	sendCancel context.CancelFunc
)

// Send offers the files listed in filesJSON ([{"name","size","type"}]) to the
// LocalSend device at ip:port, which speaks protocol (https or http) and whose
// certificate has the fingerprint fp ("" to accept any), and uploads them once
// accepted; the device's user may take a while to answer. CancelSend stops it.
func Send(ip string, port int, protocol, fp, filesJSON string, files Files) error {
	s := current()
	if s == nil {
		return errors.New("localsend is not running")
	}
	var list []sendFile
	if err := json.Unmarshal([]byte(filesJSON), &list); err != nil {
		return err
	}
	ctx, cancel := context.WithCancel(context.Background())
	sendMu.Lock()
	sendCancel = cancel
	sendMu.Unlock()
	defer cancel()

	req := prepareUploadRequest{Info: s.info, Files: map[string]fileInfo{}}
	for i, f := range list {
		id := strconv.Itoa(i)
		req.Files[id] = fileInfo{ID: id, FileName: f.Name, Size: f.Size, FileType: f.Type}
	}
	base := baseURL(protocol, ip, port)
	client := httpClient(fp)
	var prepared prepareUploadResponse
	switch status, err := postJSON(ctx, client, base+"prepare-upload", req, &prepared); {
	case err != nil:
		return err
	case status == http.StatusNoContent:
		return nil
	case status == http.StatusForbidden:
		return errRejected
	case status == http.StatusConflict:
		return errors.New("the device is busy with another transfer")
	case status == http.StatusUnauthorized:
		return errors.New("the device asks for a PIN, which isn't supported")
	case status != http.StatusOK:
		return fmt.Errorf("prepare-upload: HTTP %d", status)
	}

	for i, f := range list {
		id := strconv.Itoa(i)
		token, ok := prepared.Files[id]
		if !ok {
			// Not accepted
			continue
		}
		if err := uploadFile(ctx, client, base, prepared.SessionID, id, token, i, f.Size, files); err != nil {
			cancelSession(client, base, prepared.SessionID)
			return err
		}
	}
	return nil
}

// CancelSend stops the files being sent.
func CancelSend() {
	sendMu.Lock()
	defer sendMu.Unlock()
	if sendCancel != nil {
		sendCancel()
	}
}

func uploadFile(ctx context.Context, client *http.Client, base, sessionID, id, token string, index int, size int64, files Files) error {
	fd, err := files.Open(index)
	if err != nil {
		return err
	}
	in := os.NewFile(uintptr(fd), id)
	defer in.Close()
	body := &meter.Reader{R: in, Report: func(n int64) { files.Sent(index, n) }}
	query := url.Values{"sessionId": {sessionID}, "fileId": {id}, "token": {token}}
	req, err := http.NewRequestWithContext(ctx, "POST", base+"upload?"+query.Encode(), body)
	if err != nil {
		return err
	}
	req.ContentLength = size
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	files.Sent(index, body.Sent)
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("upload: HTTP %d", resp.StatusCode)
	}
	return nil
}

// cancelSession tells the device to stop waiting for the rest, as a courtesy.
func cancelSession(client *http.Client, base, sessionID string) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, "POST", base+"cancel?"+url.Values{"sessionId": {sessionID}}.Encode(), nil)
	if err != nil {
		return
	}
	if resp, err := client.Do(req); err == nil {
		resp.Body.Close()
	}
}

func postJSON(ctx context.Context, client *http.Client, url string, body, out any) (int, error) {
	b, err := json.Marshal(body)
	if err != nil {
		return 0, err
	}
	req, err := http.NewRequestWithContext(ctx, "POST", url, bytes.NewReader(b))
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusOK {
		if err := json.NewDecoder(io.LimitReader(resp.Body, 4<<20)).Decode(out); err != nil {
			return 0, err
		}
	}
	return resp.StatusCode, nil
}
