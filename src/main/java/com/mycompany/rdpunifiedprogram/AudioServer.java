package com.mycompany.rdpunifiedprogram;

import javax.sound.sampled.*;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;


public class AudioServer {

    private volatile boolean  running = false;
    private ServerSocket      audioServerSocket;
    private TargetDataLine    systemAudioLine;
    private final RDPServer   server;
    private final int         audioPort;

    
    private Thread captureThread;

    private static final float   SAMPLE_RATE   = 44100.0f;
    private static final int     SAMPLE_SIZE   = 16;
    private static final int     CHANNELS      = 2;
    private static final boolean SIGNED        = true;
    private static final boolean BIG_ENDIAN    = false;

    
    public static final int PCM_PACKET_BYTES = 8192;

    
    public static final float PLAYBACK_BUFFER_SECONDS = 0.12f;

    private static final int FRAME_BYTES = (SAMPLE_SIZE / 8) * CHANNELS;
    
    private static final int CAPTURE_CHUNK = PCM_PACKET_BYTES - (PCM_PACKET_BYTES % FRAME_BYTES);

    private final AudioFormat audioFormat = new AudioFormat(
        SAMPLE_RATE, SAMPLE_SIZE, CHANNELS, SIGNED, BIG_ENDIAN);

    
    public static void writeAllToSourceLine(SourceDataLine line, byte[] data, int offset, int length) {
        int off = offset;
        int end = offset + length;
        while (off < end) {
            int w = line.write(data, off, end - off);
            if (w > 0) {
                off += w;
            } else {
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    
    private static final class ClientStream {
        final DataOutputStream dos;
        final Object           lock = new Object();
        final byte[]           pktBuf;

        ClientStream(OutputStream os, int maxPayloadBytes) {
            this.dos    = new DataOutputStream(new BufferedOutputStream(os, 262144));
            this.pktBuf = new byte[4 + maxPayloadBytes];
        }

        
        void write(byte[] data, int len) throws IOException {
            pktBuf[0] = (byte)(len >>> 24);
            pktBuf[1] = (byte)(len >>> 16);
            pktBuf[2] = (byte)(len >>>  8);
            pktBuf[3] = (byte)(len);
            System.arraycopy(data, 0, pktBuf, 4, len);
            synchronized (lock) {
                dos.write(pktBuf, 0, 4 + len);
                dos.flush();
            }
        }

        
        void closeQuietly() {
            synchronized (lock) {
                try { dos.close(); } catch (IOException ignored) {}
            }
        }
    }

    
    private final CopyOnWriteArrayList<ClientStream> clientStreams =
        new CopyOnWriteArrayList<>();

    public AudioServer(RDPServer server, int audioPort) {
        this.server    = server;
        this.audioPort = audioPort;
    }

    public boolean start() {
        try {
            systemAudioLine = findSystemAudioDevice();
            if (systemAudioLine == null) {
                server.ui.log("ERROR: No system audio device found!");
                server.ui.log("Please enable 'Stereo Mix' (Windows) or install virtual audio cable");
                return false;
            }

            
            systemAudioLine.open(audioFormat, CAPTURE_CHUNK * 6);
            systemAudioLine.start();

            audioServerSocket = new ServerSocket(audioPort);
            audioServerSocket.setReuseAddress(true);
            running = true;

            
            captureThread = new Thread(this::captureAndBroadcast, "audio-capture");
            captureThread.setDaemon(true);
            captureThread.setPriority(Thread.MAX_PRIORITY - 1);
            captureThread.start();

            Thread acceptThread = new Thread(this::acceptAudioConnections, "audio-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();

            server.ui.log("System audio streaming enabled on port " + audioPort);
            return true;

        } catch (Exception e) {
            server.ui.log("Failed to start audio server: " + e.getMessage());
            return false;
        }
    }

    
    private void captureAndBroadcast() {
        byte[] buffer = new byte[CAPTURE_CHUNK];
        
        final int frameBytes = (SAMPLE_SIZE / 8) * CHANNELS;
        while (running && !Thread.currentThread().isInterrupted()) {
            int bytesRead = systemAudioLine.read(buffer, 0, buffer.length);
            if (bytesRead <= 0) continue;
            int n = bytesRead - (bytesRead % frameBytes);
            if (n <= 0) continue;

            for (ClientStream cs : clientStreams) {
                try {
                    cs.write(buffer, n);
                } catch (IOException e) {
                    clientStreams.remove(cs);
                }
            }
        }
    }

    private void acceptAudioConnections() {
        while (running) {
            try {
                Socket audioSocket = audioServerSocket.accept();
                String ip = audioSocket.getInetAddress().getHostAddress();
                server.ui.log("Audio connection from " + ip);

                audioSocket.setTcpNoDelay(true);
                audioSocket.setSendBufferSize(256 * 1024);
                

                ClientStream cs = new ClientStream(audioSocket.getOutputStream(), CAPTURE_CHUNK);
                clientStreams.add(cs);

                
                final ClientStream ref   = cs;
                final String       ipRef = ip;
                Thread watchdog = new Thread(() -> {
                    try {
                        byte[] probe = new byte[1];
                        int result;
                        do {
                            try {
                                result = audioSocket.getInputStream().read(probe);
                            } catch (java.net.SocketTimeoutException ste) {
                                
                                result = 0; // keep looping
                            }
                        } while (result == 0 && !audioSocket.isClosed() && running);
                        
                    } catch (IOException ignored) {
                        
                    } finally {
                        clientStreams.remove(ref);
                        try { audioSocket.close(); } catch (IOException ignored) {}
                        server.ui.log("Audio stream to " + ipRef + " ended");
                    }
                }, "audio-watchdog-" + ip);
                watchdog.setDaemon(true);
                watchdog.start();

            } catch (IOException e) {
                if (running) server.ui.log("Audio accept error: " + e.getMessage());
            }
        }
    }

    
    public static TargetDataLine openLoopbackCaptureLine(AudioFormat audioFormat,
            java.util.function.Consumer<String> log) {
        Mixer.Info[] mixers   = AudioSystem.getMixerInfo();
        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, audioFormat);
        String[] keywords = {"Stereo Mix", "Wave Out Mix", "What U Hear", "Monitor", "Loopback"};

        for (Mixer.Info mixerInfo : mixers) {
            String name = mixerInfo.getName(), desc = mixerInfo.getDescription();
            if (log != null) log.accept("  Found device: " + name);
            for (String kw : keywords) {
                if (name.toLowerCase().contains(kw.toLowerCase()) ||
                    desc.toLowerCase().contains(kw.toLowerCase())) {
                    try {
                        Mixer mixer = AudioSystem.getMixer(mixerInfo);
                        if (mixer.isLineSupported(lineInfo)) {
                            TargetDataLine line = (TargetDataLine) mixer.getLine(lineInfo);
                            if (log != null) log.accept("Selected loopback capture: " + name);
                            return line;
                        }
                    } catch (LineUnavailableException ignored) {}
                }
            }
        }
        if (log != null) log.accept("No dedicated loopback device found (Stereo Mix / monitor).");
        return null;
    }

    private TargetDataLine findSystemAudioDevice() {
        server.ui.log("Searching for system audio devices...");
        TargetDataLine line = openLoopbackCaptureLine(audioFormat, server.ui::log);
        if (line == null) {
            server.ui.log("ERROR: No system audio device found!");
        }
        return line;
    }

    public void stop() {
        running = false;
        
        if (captureThread != null) captureThread.interrupt();
        
        for (ClientStream cs : new ArrayList<>(clientStreams)) {
            cs.closeQuietly();
        }
        clientStreams.clear();
        if (systemAudioLine != null && systemAudioLine.isOpen()) {
            systemAudioLine.stop();
            systemAudioLine.close();
        }
        try {
            if (audioServerSocket != null && !audioServerSocket.isClosed())
                audioServerSocket.close();
        } catch (IOException ignored) {}
        server.ui.log("Audio server stopped");
    }

    public int getAudioPort() { return audioPort; }
}