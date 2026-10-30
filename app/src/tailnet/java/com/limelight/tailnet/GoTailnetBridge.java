package com.limelight.tailnet;

import com.limelight.gobridge.tailnet.Tailnet;

/** TailnetBridge backed by the gomobile bindings in app/libs-tailnet/tailnet.aar. */
final class GoTailnetBridge implements TailnetBridge {
    GoTailnetBridge() {
        // Touching the bindings loads libgojni.so, which only ships for arm64-v8a. On other
        // ABIs this throws a LinkageError and TailnetManager reports the feature as unavailable.
        Tailnet.statusJSON();
    }

    @Override
    public void setCrashLog(String path) throws Exception {
        Tailnet.setCrashLog(path);
    }

    @Override
    public void start(String stateDir, String hostname, String controlUrl, String authKey) throws Exception {
        Tailnet.start(stateDir, hostname, controlUrl, authKey);
    }

    @Override
    public void stop() {
        Tailnet.stop();
    }

    @Override
    public void logout(String stateDir) throws Exception {
        Tailnet.logout(stateDir);
    }

    @Override
    public String statusJson() {
        return Tailnet.statusJSON();
    }

    @Override
    public void forward(String localIp, String remoteHost, String tcpPorts, String udpPorts) throws Exception {
        Tailnet.forward(localIp, remoteHost, tcpPorts, udpPorts);
    }

    @Override
    public String awaitWaitingFiles(int timeoutSeconds) {
        return Tailnet.awaitWaitingFiles(timeoutSeconds);
    }

    @Override
    public void saveWaitingFile(String name, int fd) throws Exception {
        Tailnet.saveWaitingFile(name, fd);
    }

    @Override
    public String fileTargets() {
        return Tailnet.fileTargets();
    }

    @Override
    public void sendFile(String targetId, String name, int fd, long size, SendProgress progress) throws Exception {
        Tailnet.sendFile(targetId, name, fd, size, progress::sent);
    }

    @Override
    public void cancelSends() {
        Tailnet.cancelSends();
    }

    @Override
    public String peersJson() {
        return Tailnet.peersJSON();
    }

    @Override
    public boolean probeTcp(String host, int port, int timeoutMillis) {
        return Tailnet.probeTCP(host, port, timeoutMillis);
    }

    @Override
    public String pingJson(String host, int timeoutMillis) {
        return Tailnet.pingJSON(host, timeoutMillis);
    }
}
