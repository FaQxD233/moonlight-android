package com.limelight.tailnet;

/** Calls into the optional embedded Tailscale library built from tailnet/ (see GoTailnetBridge). */
interface TailnetBridge {
    void start(String stateDir, String hostname, String controlUrl, String authKey) throws Exception;

    void stop();

    void logout(String stateDir) throws Exception;

    String statusJson();

    void forward(String localIp, String remoteHost, String tcpPorts, String udpPorts) throws Exception;
}
