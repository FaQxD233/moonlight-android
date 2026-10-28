package tailnet

import (
	"net"
	"net/netip"
	"time"

	"tailscale.com/net/netmon"
)

// Android denies app UIDs the netlink route socket, so Go's net.Interfaces fails
// ("netlinkrib: permission denied") and tsnet can't start. Tailscale's own
// fallback in feature/androidbin is compiled out of GOOS=android cgo builds like
// this gomobile one, which are expected to register a getter of their own.
func init() {
	netmon.RegisterInterfaceGetter(outboundInterfaces)
}

// outboundInterfaces reports one synthetic interface carrying the source
// addresses the kernel picks for outbound traffic (the same fallback androidbin
// uses), which is enough for magicsock to find this device's local endpoints.
func outboundInterfaces() ([]netmon.Interface, error) {
	var addrs []net.Addr
	if ip, ok := outboundIP("udp4", "8.8.8.8:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(32, 32)})
	}
	if ip, ok := outboundIP("udp6", "[2001:4860:4860::8888]:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(128, 128)})
	}
	if len(addrs) == 0 {
		// Offline: report no interfaces so the node waits for a network instead of failing
		return nil, nil
	}
	return []netmon.Interface{{
		Interface: &net.Interface{MTU: 1500, Name: "android", Flags: net.FlagUp | net.FlagRunning},
		AltAddrs:  addrs,
	}}, nil
}

// outboundIP returns the local address used to reach addr. Dialing UDP sends no packets.
func outboundIP(network, addr string) (netip.Addr, bool) {
	c, err := net.DialTimeout(network, addr, 2*time.Second)
	if err != nil {
		return netip.Addr{}, false
	}
	defer c.Close()
	ua, ok := c.LocalAddr().(*net.UDPAddr)
	if !ok {
		return netip.Addr{}, false
	}
	ip, ok := netip.AddrFromSlice(ua.IP)
	if !ok {
		return netip.Addr{}, false
	}
	ip = ip.Unmap()
	if ip.IsLoopback() || ip.IsUnspecified() {
		return netip.Addr{}, false
	}
	return ip, true
}
