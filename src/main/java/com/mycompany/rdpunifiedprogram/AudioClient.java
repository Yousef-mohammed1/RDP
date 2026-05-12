package com.mycompany.rdpunifiedprogram;

import javax.sound.sampled.*;
import java.io.*;
import java.net.*;


public class AudioClient {

    private volatile boolean running = false;
    private Socket           audioSocket;
    private SourceDataLine   speakers;
    public  final RDPClient  client;
    private volatile boolean muted = false;

    
    private volatile Thread audioReceiveThread;

    private static final float   SAMPLE_RATE = 44100.0f;
    private static final int     SAMPLE_SIZE = 16;
    private static final int     CHANNELS    = 2;
    private static final boolean SIGNED      = true;
    private static final boolean BIG_ENDIAN  = false;
    
    private static final int     SILENCE_BYTES = (int)(SAMPLE_RATE * (SAMPLE_SIZE / 8f) * CHANNELS * 0.020f);
    
    private static final int     MAX_RECONNECT_TRIES = 5;
    
    private static final int     SOCKET_READ_TIMEOUT_MS = 0;

    private final AudioFormat audioFormat = new AudioFormat(
        SAMPLE_RATE, SAMPLE_SIZE, CHANNELS, SIGNED, BIG_ENDIAN);

    
    private final byte[] silentBuffer = new byte[Math.max(8192, SILENCE_BYTES)];
    
    private byte[]       recvBuf      = new byte[8192];

    public AudioClient(RDPClient client) {
        this.client = client;
    }

    public boolean connect(String host, int audioPort) {
        try {
            client.ui.setStatus("Connecting to audio stream...", false);

            DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, audioFormat);
            if (!AudioSystem.isLineSupported(speakerInfo)) {
                client.ui.setStatus("Stereo audio playback not supported", true);
                return false;
            }

            speakers = (SourceDataLine) AudioSystem.getLine(speakerInfo);
            int lineBufBytes = (int)(SAMPLE_RATE * (SAMPLE_SIZE / 8f) * CHANNELS
                * AudioServer.PLAYBACK_BUFFER_SECONDS);
            lineBufBytes = Math.max(16384, (lineBufBytes + 3) & ~3);
            speakers.open(audioFormat, lineBufBytes);
            speakers.start();

        } catch (Exception e) {
            client.ui.setStatus("Audio init failed: " + e.getMessage(), true);
            return false;
        }

        
        running = true;
        Thread audioThread = new Thread(() -> receiveWithReconnect(host, audioPort), "audio-receive");
        audioReceiveThread = audioThread;
        audioThread.setDaemon(true);
        audioThread.setPriority(Thread.MAX_PRIORITY - 1);
        audioThread.start();

        client.ui.setStatus("System audio streaming active", false);
        return true;
    }

    
    private void receiveWithReconnect(String host, int audioPort) {
        try {
            int tries = 0;
            while (running && tries < MAX_RECONNECT_TRIES) {
                try {
                    openSocket(host, audioPort);
                    receiveAudio();   // blocks until stream ends or error
                    tries = 0;        // reset on clean exit
                } catch (Exception e) {
                    if (!running) break;
                    tries++;
                    client.ui.setStatus("Audio interrupted, reconnecting (" + tries + "/" + MAX_RECONNECT_TRIES + ")…", false);
                    closeSocket();
                    try {
                        Thread.sleep(1000L * tries);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (running) client.ui.setStatus("Audio stream ended", false);
            closeSpeakers();
        } finally {
            audioReceiveThread = null;
        }
    }

    private void openSocket(String host, int audioPort) throws IOException {
        audioSocket = new Socket();
        audioSocket.connect(new InetSocketAddress(host, audioPort), 5000);
        audioSocket.setTcpNoDelay(true);
        audioSocket.setKeepAlive(true);
        audioSocket.setReceiveBufferSize(256 * 1024);
        audioSocket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
    }

    
    private void receiveAudio() throws IOException {
        final int frameBytes = (SAMPLE_SIZE / 8) * CHANNELS;
        final int maxPacket = 65536;
        DataInputStream in = new DataInputStream(
            new BufferedInputStream(audioSocket.getInputStream(), 256 * 1024));

        while (running && !audioSocket.isClosed()) {
            int length = in.readInt();

            if (length < frameBytes || length > maxPacket || (length % frameBytes) != 0) {
                throw new IOException("Invalid audio packet length: " + length);
            }

            if (recvBuf.length < length) recvBuf = new byte[length];
            in.readFully(recvBuf, 0, length);

            if (speakers != null && speakers.isOpen()) {
                if (muted) {
                    AudioServer.writeAllToSourceLine(speakers, silentBuffer, 0,
                        Math.min(length, silentBuffer.length));
                } else {
                    AudioServer.writeAllToSourceLine(speakers, recvBuf, 0, length);
                }
            }
        }
    }

    public void stop() {
        running = false;
        Thread t = audioReceiveThread;
        if (t != null) t.interrupt();
        closeSocket();
        closeSpeakers();
    }

    private void closeSocket() {
        try {
            if (audioSocket != null && !audioSocket.isClosed()) audioSocket.close();
        } catch (IOException ignored) {}
    }

    private void closeSpeakers() {
        SourceDataLine line = speakers;
        speakers = null;
        if (line != null && line.isOpen()) {
            line.stop();
            line.close();
        }
    }

    // ── Controls ──────────────────────────────────────────────────────────

    public void setMuted(boolean muted) {
        this.muted = muted;
        client.ui.setStatus(muted ? "Audio muted" : "Audio unmuted", false);
    }

    public boolean isMuted() { return muted; }

    public void setVolume(float volume) {
        if (speakers == null || !speakers.isOpen()) return;
        try {
            FloatControl gain = (FloatControl) speakers.getControl(FloatControl.Type.MASTER_GAIN);
            float dB = volume <= 0f ? gain.getMinimum() : (float)(Math.log10(volume) * 20.0);
            gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB)));
        } catch (IllegalArgumentException ignored) {}
    }

    public float getVolume() {
        if (speakers != null && speakers.isOpen()) {
            try {
                FloatControl gain = (FloatControl) speakers.getControl(FloatControl.Type.MASTER_GAIN);
                return (float) Math.pow(10.0, gain.getValue() / 20.0);
            } catch (IllegalArgumentException ignored) {}
        }
        return 0f;
    }
}