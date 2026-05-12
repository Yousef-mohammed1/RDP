package com.mycompany.rdpunifiedprogram;

import javax.sound.sampled.*;
import java.io.*;
import java.net.*;

public class AudioShareUploader {

    private volatile boolean running = false;
    private Socket           socket;
    private TargetDataLine   captureLine;
    private final RDPClient  client;

    private static final float   SAMPLE_RATE = 44100.0f;
    private static final int     SAMPLE_SIZE = 16;
    private static final int     CHANNELS    = 2;
    private static final boolean SIGNED      = true;
    private static final boolean BIG_ENDIAN  = false;
    private final AudioFormat audioFormat = new AudioFormat(
        SAMPLE_RATE, SAMPLE_SIZE, CHANNELS, SIGNED, BIG_ENDIAN);

    public AudioShareUploader(RDPClient client) {
        this.client = client;
    }

    public void start(String host, int audioPort) {
        running = true;
        try {
            captureLine = AudioServer.openLoopbackCaptureLine(audioFormat, null);
            if (captureLine == null) {
                client.ui.setStatus("Remote audio: enable Stereo Mix / loopback on this PC to stream system sound", true);
                return;
            }
            final int pcmPacket = AudioServer.PCM_PACKET_BYTES;
            captureLine.open(audioFormat, pcmPacket * 6);
            captureLine.start();

            socket = new Socket();
            socket.connect(new InetSocketAddress(host, audioPort), 8000);
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(256 * 1024);
            DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(socket.getOutputStream(), 262144));

            byte[] buffer = new byte[pcmPacket];
            final int frameBytes = (SAMPLE_SIZE / 8) * CHANNELS;
            while (running && !Thread.currentThread().isInterrupted()) {
                int n = captureLine.read(buffer, 0, buffer.length);
                if (n <= 0) continue;
                int aligned = n - (n % frameBytes);
                if (aligned <= 0) continue;
                out.writeInt(aligned);
                out.write(buffer, 0, aligned);
                out.flush();
            }
        } catch (Exception e) {
            if (running)
                client.ui.setStatus("Remote audio upload stopped: " + e.getMessage(), false);
        } finally {
            stop();
        }
    }

    public void stop() {
        running = false;
        if (captureLine != null) {
            try {
                captureLine.stop();
                captureLine.close();
            } catch (Exception ignored) {}
            captureLine = null;
        }
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
        socket = null;
    }
}
