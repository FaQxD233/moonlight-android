package com.limelight.tailnet;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Collections;

import javax.net.SocketFactory;

import okhttp3.Dns;

/** Plain sockets whose connections to tailnet hosts go through the embedded Tailscale relays. */
public final class TailnetSocketFactory extends SocketFactory {
    public static final TailnetSocketFactory INSTANCE = new TailnetSocketFactory();

    /** Resolves MagicDNS names to their relay address; everything else uses the system resolver. */
    public static final Dns DNS = hostname -> {
        InetAddress relay = TailnetManager.relayAddressForName(hostname);
        return relay != null ? Collections.singletonList(relay) : Dns.SYSTEM.lookup(hostname);
    };

    private TailnetSocketFactory() {
    }

    @Override
    public Socket createSocket() {
        return new TailnetSocket();
    }

    // OkHttp only uses createSocket(); the connected variants route the same way.
    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return connect(new InetSocketAddress(host, port), null);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return connect(new InetSocketAddress(host, port), new InetSocketAddress(localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return connect(new InetSocketAddress(host, port), null);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return connect(new InetSocketAddress(address, port), new InetSocketAddress(localAddress, localPort));
    }

    private static Socket connect(SocketAddress endpoint, SocketAddress localEndpoint) throws IOException {
        Socket socket = new TailnetSocket();
        if (localEndpoint != null) {
            socket.bind(localEndpoint);
        }
        socket.connect(endpoint);
        return socket;
    }

    private static final class TailnetSocket extends Socket {
        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            super.connect(TailnetManager.route(endpoint), timeout);
        }
    }
}
