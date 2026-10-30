// Package localsend exchanges files with LocalSend (https://localsend.org) on
// the local network. It implements the LocalSend protocol v2
// (https://github.com/localsend/protocol): multicast discovery, the upload API
// to receive files and its client to send them. It is bound to Java with
// gomobile alongside package tailnet; see com.limelight.files.LocalSendManager.
package localsend

const (
	// The protocol version this implements (major.minor)
	protocolVersion = "2.1"
	defaultPort     = 53317
	multicastGroup  = "224.0.0.167"
	apiPrefix       = "/api/localsend/v2/"
)

// deviceInfo is how LocalSend devices describe themselves, in announcements,
// /register and /info.
type deviceInfo struct {
	Alias       string `json:"alias"`
	Version     string `json:"version"`
	DeviceModel string `json:"deviceModel,omitempty"`
	DeviceType  string `json:"deviceType,omitempty"`
	Fingerprint string `json:"fingerprint"`
	Port        int    `json:"port"`
	Protocol    string `json:"protocol"`
	Download    bool   `json:"download"`
	// Only in multicast messages: true asks the others to answer
	Announce *bool `json:"announce,omitempty"`
}

type fileInfo struct {
	ID       string `json:"id"`
	FileName string `json:"fileName"`
	Size     int64  `json:"size"`
	FileType string `json:"fileType"`
	SHA256   string `json:"sha256,omitempty"`
	Preview  string `json:"preview,omitempty"`
}

type prepareUploadRequest struct {
	Info  deviceInfo          `json:"info"`
	Files map[string]fileInfo `json:"files"`
}

type prepareUploadResponse struct {
	SessionID string            `json:"sessionId"`
	Files     map[string]string `json:"files"`
}
