package localsend

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	// Devices not heard from for this long are no longer listed
	deviceExpiry    = 10 * time.Minute
	registerTimeout = 3 * time.Second
	// Devices on the LAN answer fast, and a scan tries a few hundred addresses
	scanTimeout = time.Second
)

type device struct {
	info     deviceInfo
	ip       string
	lastSeen time.Time
}

func (s *server) startDiscovery() error {
	group := &net.UDPAddr{IP: net.ParseIP(multicastGroup), Port: defaultPort}
	conn, err := net.ListenMulticastUDP("udp4", nil, group)
	if err != nil {
		return err
	}
	s.mcast = conn
	go s.readAnnouncements(conn)
	s.announce(true)
	return nil
}

func (s *server) readAnnouncements(conn *net.UDPConn) {
	buf := make([]byte, 64<<10)
	for {
		n, from, err := conn.ReadFromUDP(buf)
		if err != nil {
			// Closed by Stop
			return
		}
		var info deviceInfo
		if json.Unmarshal(buf[:n], &info) != nil || info.Fingerprint == "" || info.Fingerprint == s.info.Fingerprint {
			continue
		}
		ip := from.IP.String()
		s.addDevice(info, ip)
		if info.Announce != nil && *info.Announce {
			go s.answer(info, ip)
		}
	}
}

// answer replies to an announcement the way LocalSend does: with /register,
// or a multicast message when that fails.
func (s *server) answer(info deviceInfo, ip string) {
	if _, err := s.register(ip, info.Port, info.Protocol, info.Fingerprint, registerTimeout); err != nil {
		s.announce(false)
	}
}

// announce sends this device's info to the multicast group; ask makes the
// others answer.
func (s *server) announce(ask bool) {
	msg := s.info
	msg.Announce = &ask
	b, _ := json.Marshal(msg)
	conn, err := net.DialUDP("udp4", nil, &net.UDPAddr{IP: net.ParseIP(multicastGroup), Port: defaultPort})
	if err != nil {
		return
	}
	defer conn.Close()
	conn.Write(b)
}

func (s *server) addDevice(info deviceInfo, ip string) {
	if info.Fingerprint == "" || info.Fingerprint == s.info.Fingerprint {
		return
	}
	s.devicesMu.Lock()
	s.devices[info.Fingerprint] = &device{info: info, ip: ip, lastSeen: time.Now()}
	s.devicesMu.Unlock()
}

// register introduces this device to the one at ip:port and lists it.
func (s *server) register(ip string, port int, protocol, fp string, timeout time.Duration) (deviceInfo, error) {
	var peer deviceInfo
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	body, _ := json.Marshal(s.info)
	req, err := http.NewRequestWithContext(ctx, "POST", baseURL(protocol, ip, port)+"register", bytes.NewReader(body))
	if err != nil {
		return peer, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := httpClient(fp).Do(req)
	if err != nil {
		return peer, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return peer, fmt.Errorf("register: %s", resp.Status)
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(&peer); err != nil {
		return peer, err
	}
	// The answer leaves out how to reach the device, which the request already knew
	peer.Port, peer.Protocol = port, protocol
	if resp.TLS != nil && len(resp.TLS.PeerCertificates) > 0 {
		peer.Fingerprint = fingerprint(resp.TLS.PeerCertificates[0].Raw)
	}
	s.addDevice(peer, ip)
	return peer, nil
}

// httpClient talks to LocalSend devices, whose certificates are self-signed:
// with fp set, the certificate must have that fingerprint instead.
func httpClient(fp string) *http.Client {
	return &http.Client{Transport: &http.Transport{
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true,
			VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
				if fp != "" && (len(raw) == 0 || !strings.EqualFold(fingerprint(raw[0]), fp)) {
					return errors.New("the device's certificate doesn't match its fingerprint")
				}
				return nil
			},
		},
		DialContext:         (&net.Dialer{Timeout: 5 * time.Second}).DialContext,
		TLSHandshakeTimeout: 5 * time.Second,
		// Requests to a device are few and far between
		DisableKeepAlives: true,
	}}
}

func baseURL(protocol, ip string, port int) string {
	if protocol != "http" {
		protocol = "https"
	}
	return protocol + "://" + net.JoinHostPort(ip, strconv.Itoa(port)) + apiPrefix
}

type deviceJSON struct {
	Alias       string `json:"alias"`
	DeviceModel string `json:"deviceModel,omitempty"`
	DeviceType  string `json:"deviceType,omitempty"`
	IP          string `json:"ip"`
	Port        int    `json:"port"`
	Protocol    string `json:"protocol"`
	Fingerprint string `json:"fingerprint"`
}

// DevicesJSON lists the LocalSend devices heard from in the last ten minutes,
// by name, as JSON [{"alias","deviceModel","deviceType","ip","port","protocol","fingerprint"}].
func DevicesJSON() string {
	devices := []deviceJSON{}
	if s := current(); s != nil {
		s.devicesMu.Lock()
		for _, d := range s.devices {
			if time.Since(d.lastSeen) < deviceExpiry {
				devices = append(devices, deviceJSON{d.info.Alias, d.info.DeviceModel, d.info.DeviceType,
					d.ip, d.info.Port, d.info.Protocol, d.info.Fingerprint})
			}
		}
		s.devicesMu.Unlock()
	}
	sort.Slice(devices, func(i, j int) bool { return devices[i].Alias < devices[j].Alias })
	b, _ := json.Marshal(devices)
	return string(b)
}

// Announce asks the LocalSend devices on the network to make themselves known.
func Announce() {
	if s := current(); s != nil {
		s.announce(true)
	}
}

// Scan finds LocalSend devices without multicast, as LocalSend does when it
// is blocked: it registers with every address of the IPv4 network prefix
// (such as 192.168.1.23/24, at most a /22), on the default port.
func Scan(prefix string) error {
	s := current()
	if s == nil {
		return errors.New("localsend is not running")
	}
	p, err := netip.ParsePrefix(prefix)
	if err != nil {
		return err
	}
	p = p.Masked()
	if !p.Addr().Is4() || p.Bits() < 22 {
		return fmt.Errorf("won't scan %s", p)
	}
	var wg sync.WaitGroup
	sem := make(chan struct{}, 64)
	for addr := p.Addr().Next(); p.Contains(addr); addr = addr.Next() {
		ip := addr.String()
		wg.Add(1)
		sem <- struct{}{}
		go func() {
			defer func() { <-sem; wg.Done() }()
			s.registerAnyProtocol(ip, defaultPort, scanTimeout)
		}()
	}
	wg.Wait()
	return nil
}

// AddDevice registers with the LocalSend device at ip:port (0 for the default
// port), for when discovery can't find it.
func AddDevice(ip string, port int) error {
	s := current()
	if s == nil {
		return errors.New("localsend is not running")
	}
	if port == 0 {
		port = defaultPort
	}
	return s.registerAnyProtocol(ip, port, registerTimeout)
}

// LocalSend uses HTTPS unless its user turned encryption off.
func (s *server) registerAnyProtocol(ip string, port int, timeout time.Duration) error {
	if _, err := s.register(ip, port, "https", "", timeout); err == nil {
		return nil
	}
	_, err := s.register(ip, port, "http", "", timeout)
	return err
}
