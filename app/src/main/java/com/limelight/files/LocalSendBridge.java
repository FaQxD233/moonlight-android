package com.limelight.files;

/** Calls into the LocalSend implementation in the embedded Go library built from tailnet/localsend. */
interface LocalSendBridge {
    void start(String alias, String deviceModel, String dir, Receiver receiver) throws Exception;

    void stop();

    void writeUpload(String token, int fd) throws Exception;

    String devicesJson();

    void announce();

    void scan(String prefix) throws Exception;

    void addDevice(String ip, int port) throws Exception;

    void send(String ip, int port, String protocol, String fingerprint, String filesJson, Files files) throws Exception;

    void cancelSend();

    /** Decides on and stores the files LocalSend devices send. */
    interface Receiver {
        boolean accept(String offerJson);

        void save(String token, String name, String fileType, long size) throws Exception;
    }

    /** The files being sent, and how far each has got. */
    interface Files {
        int open(int index) throws Exception;

        void sent(int index, long bytes);
    }
}
