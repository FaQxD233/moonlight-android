package com.limelight.tailnet;

/** Calls into the optional embedded Tailscale library built from tailnet/ (see GoTailnetBridge). */
interface TailnetBridge {
    void setCrashLog(String path) throws Exception;

    void start(String stateDir, String hostname, String controlUrl, String authKey) throws Exception;

    void stop();

    void logout(String stateDir) throws Exception;

    String statusJson();

    void forward(String localIp, String remoteHost, String tcpPorts, String udpPorts) throws Exception;

    String awaitWaitingFiles(int timeoutSeconds);

    void saveWaitingFile(String name, int fd) throws Exception;
}
