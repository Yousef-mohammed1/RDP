package com.mycompany.rdpunifiedprogram;

import javax.sound.sampled.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;


public class RemoteAudioPlaybackServer {

    private final RDPServer server;
    private final int       audioPort;
    private volatile ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Socket activeSocket;

    private static final float   SAMPLE_RATE = 44100.0f;
    private static final int     SAMPLE_SIZE = 16;
    private static final int     CHANNELS    = 2;
    private static final boolean SIGNED      = true;
    private static final boolean BIG_ENDIAN  = false;

    private final AudioFormat audioFormat = new AudioFormat(
        SAMPLE_RATE, SAMPLE_SIZE, CHANNELS, SIGNED, BIG_ENDIAN);

    public RemoteAudioPlaybackServer(RDPServer server, int audioPort) {
        this.server   = server;
        this.audioPort = audioPort;
    }

    public boolean start() {
        try {
            serverSocket = new ServerSocket(audioPort);
            serverSocket.setReuseAddress(true);
            running.set(true);
            Thread t = new Thread(this::acceptLoop, "remote-audio-playback-accept");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY + 1);
            t.start();
            server.ui.log("Remote system audio playback listening on port " + audioPort);
            return true;
        } catch (Exception e) {
            server.ui.log("Failed to start remote audio playback: " + e.getMessage());
            return false;
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                s.setReceiveBufferSize(256 * 1024);
                String ip = s.getInetAddress().getHostAddress();
                server.ui.log("Incoming remote audio stream from " + ip);
                synchronized (this) {
                    if (activeSocket != null && !activeSocket.isClosed()) {
                        try { activeSocket.close(); } catch (IOException ignored) {}
                    }
                    activeSocket = s;
                }
                final Socket sock = s;
                Thread play = new Thread(() -> playSession(sock, ip), "remote-audio-play-" + ip);
                play.setDaemon(true);
                play.setPriority(Thread.MAX_PRIORITY - 1);
                play.start();
            } catch (IOException e) {
                if (running.get())
                    server.ui.log("Remote audio accept error: " + e.getMessage());
            }
        }
    }

    private void playSession(Socket socket, String ip) {
        final int frameBytes = (SAMPLE_SIZE / 8) * CHANNELS;
        final int maxPacket = 65536;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(socket.getInputStream(), 256 * 1024))) {

            DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
            if (!AudioSystem.isLineSupported(info)) {
                server.ui.log("Stereo playback not supported — cannot play remote audio");
                return;
            }
            SourceDataLine speakers = (SourceDataLine) AudioSystem.getLine(info);
            int lineBufBytes = (int)(SAMPLE_RATE * (SAMPLE_SIZE / 8f) * CHANNELS
                * AudioServer.PLAYBACK_BUFFER_SECONDS);
            lineBufBytes = Math.max(16384, (lineBufBytes + 3) & ~3);
            speakers.open(audioFormat, lineBufBytes);
            speakers.start();

            byte[] buf = new byte[maxPacket];
            while (running.get() && !socket.isClosed()) {
                int length = in.readInt();
                if (length < frameBytes || length > maxPacket || (length % frameBytes) != 0)
                    break;
                in.readFully(buf, 0, length);
                AudioServer.writeAllToSourceLine(speakers, buf, 0, length);
            }
            speakers.drain();
            speakers.stop();
            speakers.close();
        } catch (Exception e) {
            if (running.get())
                server.ui.log("Remote audio session ended (" + ip + "): " + e.getMessage());
        } finally {
            synchronized (this) {
                if (socket == activeSocket) activeSocket = null;
            }
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    public void stop() {
        running.set(false);
        synchronized (this) {
            if (activeSocket != null) {
                try { activeSocket.close(); } catch (IOException ignored) {}
                activeSocket = null;
            }
        }
        try {
            if (serverSocket != null && !serverSocket.isClosed())
                serverSocket.close();
        } catch (IOException ignored) {}
        server.ui.log("Remote audio playback stopped");
    }
}
