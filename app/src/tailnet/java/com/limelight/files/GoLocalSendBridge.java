package com.limelight.files;

import com.limelight.gobridge.localsend.Localsend;

/** LocalSendBridge backed by the gomobile bindings in app/libs-tailnet/tailnet.aar. */
final class GoLocalSendBridge implements LocalSendBridge {
    GoLocalSendBridge() {
        // Touching the bindings loads libgojni.so, which only ships for arm64-v8a. On other
        // ABIs this throws a LinkageError and LocalSendManager reports the feature as unavailable.
        Localsend.devicesJSON();
    }

    @Override
    public void start(String alias, String deviceModel, String dir, Receiver receiver) throws Exception {
        Localsend.start(alias, deviceModel, dir, new com.limelight.gobridge.localsend.Receiver() {
            @Override
            public boolean accept(String offerJSON) {
                return receiver.accept(offerJSON);
            }

            @Override
            public void save(String token, String name, String fileType, long size) throws Exception {
                receiver.save(token, name, fileType, size);
            }
        });
    }

    @Override
    public void stop() {
        Localsend.stop();
    }

    @Override
    public void writeUpload(String token, int fd) throws Exception {
        Localsend.writeUpload(token, fd);
    }

    @Override
    public String devicesJson() {
        return Localsend.devicesJSON();
    }

    @Override
    public void announce() {
        Localsend.announce();
    }

    @Override
    public void scan(String prefix) throws Exception {
        Localsend.scan(prefix);
    }

    @Override
    public void addDevice(String ip, int port) throws Exception {
        Localsend.addDevice(ip, port);
    }

    @Override
    public void send(String ip, int port, String protocol, String fingerprint, String filesJson, Files files)
            throws Exception {
        Localsend.send(ip, port, protocol, fingerprint, filesJson, new com.limelight.gobridge.localsend.Files() {
            @Override
            public long open(long index) throws Exception {
                return files.open((int) index);
            }

            @Override
            public void sent(long index, long bytes) {
                files.sent((int) index, bytes);
            }
        });
    }

    @Override
    public void cancelSend() {
        Localsend.cancelSend();
    }
}
