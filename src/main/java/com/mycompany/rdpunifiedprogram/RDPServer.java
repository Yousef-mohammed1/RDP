package com.mycompany.rdpunifiedprogram;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.swing.SwingUtilities;


public class RDPServer {

    // ── Configuration ──────────────────────────────────────────────────────
    private int     port           = 5900;
    private int     audioPort      = port + 1;
    private String  password       = "";
    private int     maxClients     = 5;
    private boolean allowInput     = true;
    private int     frameDelayMs   = 16;
    private String  serverRole     = "SHARE";

    // ── Security Configuration ─────────────────────────────────────────────
    private static final int  MAX_FAILED_ATTEMPTS  = 3;
    private static final long BLOCK_DURATION_MS    = 5 * 60 * 1000;
    private static final long IDLE_TIMEOUT_MS      = 10 * 60 * 1000;
    private static final long HANDSHAKE_TIMEOUT_MS = 30 * 1000;
    private static final int  MAX_FRAME_SIZE       = 20 * 1024 * 1024;
    
    private static final int  MAX_SKIPPED_PAYLOAD  = 200000;
    private static final int  MAX_CMD_LEN          = 512;
    private static final int  MAX_NAME_LEN         = 50;
    private static final int  IFRAME_INTERVAL      = 90;

    // ── Encryption ─────────────────────────────────────────────────────────
    AESEncryption encryption;
    private boolean encryptionEnabled = true;

    // ── Security State ─────────────────────────────────────────────────────
    private final Map<String, Integer> failedAttempts     = new ConcurrentHashMap<>();
    private final Map<String, Long>    blockedIPs         = new ConcurrentHashMap<>();
    private final Map<String, Long>    connectionAttempts = new ConcurrentHashMap<>();

    // ── Network ────────────────────────────────────────────────────────────
    private ServerSocket   serverSocket;
    private DatagramSocket discoverySocket;
    private AudioServer    audioServer;
    
    private RemoteAudioPlaybackServer remoteAudioPlaybackServer;
    private FileTransferServer fileTransferServer;

    private volatile boolean running = false;
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private ThreadPoolExecutor pool;

    public final ServerFrame ui;
    private Robot robot;

    // ── High-Performance Image Compression Pipeline ────────────────────────
    private final ThreadLocal<ImageWriter>    tlWriter = ThreadLocal.withInitial(() ->
        ImageIO.getImageWritersByFormatName("jpg").next());
    private final ThreadLocal<ImageWriteParam> tlParams = ThreadLocal.withInitial(() -> {
        ImageWriteParam p = tlWriter.get().getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        return p;
    });
    private final ThreadLocal<ByteArrayOutputStream> tlBaos =
        ThreadLocal.withInitial(() -> new ByteArrayOutputStream(512 * 1024));

    private final ByteArrayOutputStream payloadBaos = new ByteArrayOutputStream(256 * 1024);

    // Frame broadcast: AtomicReference for lock-free latest-frame publish
    private final AtomicReference<byte[]> latestFrame    = new AtomicReference<>();


    public RDPServer(ServerFrame ui) {
        this.ui = ui;
        try {
            robot = new Robot();
            robot.setAutoDelay(0);
        } catch (AWTException e) {
            ui.log("WARNING: Robot not available.");
        }
        try {
            encryption = new AESEncryption();
            encryption.generateKey();
            if (AESEncryption.selfTest()) {
                ui.log("🔐 AES-256-GCM Encryption initialized and verified");
            } else {
                ui.log("⚠️ Encryption self-test failed! Disabling encryption.");
                encryptionEnabled = false;
            }
        } catch (Exception e) {
            ui.log("⚠️ Encryption initialization failed: " + e.getMessage());
            encryptionEnabled = false;
        }
    }

    public void configure(int port, String password, int maxClients,
                          boolean allowInput, boolean allowClipboard) {
        this.port           = port;
        this.audioPort      = port + 1;
        this.password       = password;
        this.maxClients     = maxClients;
        this.allowInput     = allowInput;
    }

    public int  getAudioPort()               { return audioPort; }
    public FileTransferServer getFileTransferServer() { return fileTransferServer; }
    public void setServerRole(String role)   { this.serverRole = role; }
    public String getServerRole()            { return serverRole; }
    public boolean isEncryptionEnabled()     { return encryptionEnabled && encryption != null; }
    public String getEncryptionKey()         { return encryption != null ? encryption.getKeyBase64() : null; }

    private boolean isIPBlocked(String ip) {
        Long until = blockedIPs.get(ip);
        if (until != null) {
            if (System.currentTimeMillis() < until) return true;
            blockedIPs.remove(ip); failedAttempts.remove(ip);
        }
        return false;
    }

    private void recordFailedAttempt(String ip) {
        int attempts = failedAttempts.getOrDefault(ip, 0) + 1;
        failedAttempts.put(ip, attempts);
        ui.logSecurity("AUTH_FAIL", ip, "Attempt " + attempts + "/" + MAX_FAILED_ATTEMPTS);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            blockedIPs.put(ip, System.currentTimeMillis() + BLOCK_DURATION_MS);
            ui.logSecurity("IP_BLOCKED", ip, "Blocked for 5 minutes");
            ui.log("🚫 IP " + ip + " blocked for 5 minutes");
        }
    }

    private void clearFailedAttempts(String ip) { failedAttempts.remove(ip); }

    private boolean isRateLimited(String ip) {
        Long last = connectionAttempts.get(ip);
        long now  = System.currentTimeMillis();
        if (last != null && (now - last) < 1000) {
            ui.logSecurity("RATE_LIMIT", ip, "Too frequent"); return true;
        }
        connectionAttempts.put(ip, now);
        return false;
    }

    
    private boolean isValidMouseInput(int x, int y) {
        Dimension s = Toolkit.getDefaultToolkit().getScreenSize();
        return x >= 0 && x < s.width && y >= 0 && y < s.height;
    }

    
    private boolean isValidRemoteClientMouseTarget(int x, int y) {
        return x >= 0 && y >= 0 && x <= 32767 && y <= 32767;
    }
    private boolean isValidMouseButton(int btn)  { return btn >= 0 && btn <= 3; }
    private boolean isValidKeyCode(int keyCode)  { return keyCode >= 0 && keyCode <= 65535; }

    private String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\p{Cntrl}]", "")
                .substring(0, Math.min(s.length(), MAX_NAME_LEN));
    }

    public boolean start() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            running = true;

            if (pool == null || pool.isShutdown()) {
                pool = new ThreadPoolExecutor(
                    2, 20, 60L, TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    r -> { Thread t = new Thread(r, "rdp-worker");
                           t.setDaemon(true);
                           t.setPriority(Thread.NORM_PRIORITY + 1); return t; },
                    new ThreadPoolExecutor.CallerRunsPolicy());
            }

            pool.submit(this::discoveryLoop);

            if ("SHARE".equals(serverRole)) {
                pool.submit(this::captureLoop);
                audioServer = new AudioServer(this, audioPort);
                if (audioServer.start())
                    ui.log("✓ Audio Server started on port " + audioPort);
            } else if ("RECEIVE".equals(serverRole)) {
                remoteAudioPlaybackServer = new RemoteAudioPlaybackServer(this, audioPort);
                if (remoteAudioPlaybackServer.start())
                    ui.log("✓ Remote audio playback on port " + audioPort);
            }

            fileTransferServer = new FileTransferServer(this, port);
            if (fileTransferServer.start())
                ui.log("📁 File Transfer Server started on port " + (port + 2));

            pool.submit(this::acceptLoop);
            ui.logSecurity("SERVER_START", "localhost",
                "Server started port=" + port + " role=" + serverRole);
            return true;

        } catch (IOException e) {
            ui.log("Failed to bind port " + port + ": " + e.getMessage());
            return false;
        }
    }

    public void stop() {
        running = false;
        disconnectAllClients();
        if (audioServer != null) { audioServer.stop(); audioServer = null; }
        if (remoteAudioPlaybackServer != null) {
            remoteAudioPlaybackServer.stop();
            remoteAudioPlaybackServer = null;
        }
        if (fileTransferServer != null) { fileTransferServer.stop(); fileTransferServer = null; }
        try { if (discoverySocket != null && !discoverySocket.isClosed()) discoverySocket.close(); }
        catch (Exception ignored) {}
        try { if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close(); }
        catch (IOException ignored) {}
        if (pool != null && !pool.isShutdown()) pool.shutdownNow();
        failedAttempts.clear();
        blockedIPs.clear();
        connectionAttempts.clear();
        ui.log("Server stopped completely");
        ui.logSecurity("SERVER_STOP", "localhost", "Server stopped");
    }

    private void discoveryLoop() {
        try {
            discoverySocket = new DatagramSocket(port + 100);
            discoverySocket.setBroadcast(true);
            byte[] buf = new byte[256];
            while (running) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                discoverySocket.receive(pkt);
                String msg      = new String(pkt.getData(), 0, pkt.getLength());
                String senderIp = pkt.getAddress().getHostAddress();
                if ("DISCOVER_RDP".equals(msg.trim()) && !isIPBlocked(senderIp)) {
                    byte[] reply = "RDP_SERVER".getBytes();
                    discoverySocket.send(new DatagramPacket(
                        reply, reply.length, pkt.getAddress(), pkt.getPort()));
                }
            }
        } catch (Exception ignored) {}
    }

    // ── OPTIMIZED CAPTURE LOOP ─────────────────────────────────────────────
    private BufferedImage captureCanvas = null;

    private void captureLoop() {
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        int[] px = new int[7]; int[] py = new int[7];
        Dimension screen     = Toolkit.getDefaultToolkit().getScreenSize();
        Rectangle screenRect = new Rectangle(screen);
        int w = screen.width, h = screen.height;

        captureCanvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D canvasG = captureCanvas.createGraphics();

        int[] prevPixels    = null;
        int   iFrameCounter = 0;

        while (running) {
            long t0 = System.currentTimeMillis();
            try {
                BufferedImage raw = robot != null ? robot.createScreenCapture(screenRect) : null;
                if (raw != null) {
                    canvasG.drawImage(raw, 0, 0, null);

                    try {
                        Point m = MouseInfo.getPointerInfo().getLocation();
                        int mx = m.x, my = m.y;
                        px[0]=mx;    py[0]=my;
                        px[1]=mx;    py[1]=my+15;
                        px[2]=mx+4;  py[2]=my+11;
                        px[3]=mx+7;  py[3]=my+18;
                        px[4]=mx+9;  py[4]=my+17;
                        px[5]=mx+6;  py[5]=my+10;
                        px[6]=mx+11; py[6]=my+10;
                        canvasG.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                        canvasG.setColor(Color.WHITE); canvasG.fillPolygon(px, py, 7);
                        canvasG.setColor(Color.BLACK); canvasG.drawPolygon(px, py, 7);
                    } catch (Exception ignored) {}

                    int[] currentPixels = ((DataBufferInt) captureCanvas.getRaster().getDataBuffer()).getData();
                    if (prevPixels == null || prevPixels.length != currentPixels.length) {
                        prevPixels    = new int[currentPixels.length];
                        iFrameCounter = IFRAME_INTERVAL;
                    }

                    boolean isIFrame = (++iFrameCounter > IFRAME_INTERVAL);
                    int minX = w, minY = h, maxX = -1, maxY = -1;
                    boolean changed = false;

                    if (isIFrame) {
                        minX = 0; minY = 0; maxX = w - 1; maxY = h - 1;
                        changed = true;
                        System.arraycopy(currentPixels, 0, prevPixels, 0, currentPixels.length);
                        iFrameCounter = 0;
                    } else {
                        for (int y = 0, idx = 0; y < h; y++) {
                            boolean rowChanged = false;
                            int rowMinX = w, rowMaxX = -1;
                            int rowBase = y * w;
                            for (int x = 0; x < w; x++, idx++) {
                                if (currentPixels[idx] != prevPixels[idx]) {
                                    if (x < rowMinX) rowMinX = x;
                                    if (x > rowMaxX) rowMaxX = x;
                                    rowChanged = true;
                                    changed    = true;
                                }
                            }
                            if (rowChanged) {
                                System.arraycopy(currentPixels, rowBase, prevPixels, rowBase, w);
                                if (y < minY) minY = y;
                                if (y > maxY) maxY = y;
                                if (rowMinX < minX) minX = rowMinX;
                                if (rowMaxX > maxX) maxX = rowMaxX;
                            }
                        }
                    }

                    if (!changed) {
                        long remaining = frameDelayMs - (System.currentTimeMillis() - t0);
                        if (remaining > 0)
                            java.util.concurrent.locks.LockSupport.parkNanos(remaining * 1_000_000L);
                        continue;
                    }

                    int subW = maxX - minX + 1;
                    int subH = maxY - minY + 1;
                    
                    BufferedImage deltaImg = captureCanvas.getSubimage(minX, minY, subW, subH);
                    byte[] jpeg = toJpegBytes(deltaImg, 0.85f);

                    payloadBaos.reset();
                    DataOutputStream dout = new DataOutputStream(payloadBaos);
                    dout.writeByte(isIFrame ? 0 : 1);
                    dout.writeInt(minX);
                    dout.writeInt(minY);
                    dout.write(jpeg);
                    dout.flush();

                    byte[] finalFrame = payloadBaos.toByteArray();
                    latestFrame.set(finalFrame);
                    
                }
            } catch (Exception e) {
                ui.log("⚠️ Capture error: " + e.getMessage());
            }

            long remaining = frameDelayMs - (System.currentTimeMillis() - t0);
            if (remaining > 0)
                java.util.concurrent.locks.LockSupport.parkNanos(remaining * 1_000_000L);
        }
        canvasG.dispose();
    }

    private byte[] toJpegBytes(BufferedImage img, float quality) throws IOException {
        ByteArrayOutputStream baos = tlBaos.get();
        baos.reset();
        ImageWriter     writer = tlWriter.get();
        ImageWriteParam param  = tlParams.get();
        param.setCompressionQuality(quality);
        writer.reset();
        
        try (MemoryCacheImageOutputStream mcios = new MemoryCacheImageOutputStream(baos)) {
            writer.setOutput(mcios);
            writer.write(null, new IIOImage(img, null, null), param);
        }
        return baos.toByteArray();
    }

    private void acceptLoop() {
        ui.log("Listening on port " + port + " [" + serverRole + "]" +
               (encryptionEnabled ? " 🔐 Encrypted" : ""));
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                String ip     = socket.getInetAddress().getHostAddress();

                if (isRateLimited(ip)) { socket.close(); continue; }
                if (isIPBlocked(ip)) {
                    ui.logSecurity("BLOCKED_ATTEMPT", ip, "Blocked IP tried to connect");
                    socket.close(); continue;
                }
                if (clients.size() >= maxClients) {
                    ui.logSecurity("MAX_CLIENTS", ip, "Rejected (max clients)");
                    socket.close(); continue;
                }

                ui.log("📡 Incoming: " + ip);
                pool.submit(new ClientHandler(socket, ip));

            } catch (IOException e) {
                if (running) ui.log("⚠️ Accept error: " + e.getMessage());
            }
        }
    }

    public void disconnectClient(String ip) {
        ClientHandler h = clients.get(ip);
        if (h != null) { ui.logSecurity("DISCONNECT", ip, "Admin disconnect"); h.close(); }
    }

    public void disconnectAllClients() {
        ui.logSecurity("DISCONNECT_ALL", "server", "Disconnecting all");
        clients.values().forEach(ClientHandler::close);
        clients.clear();
    }

    public void setClientControlPermission(String ip, boolean can) {
        ClientHandler h = clients.get(ip);
        if (h != null) {
            h.canControl = can;
            ui.logSecurity("PERMISSION", ip, "Control " + (can ? "granted" : "revoked"));
        }
    }

    
    public boolean isClientControlAllowed(String ip) {
        if (ip == null) return false;
        ClientHandler h = clients.get(ip);
        return h != null && h.active && h.canControl;
    }

    public void setClientTimeLimit(String ip, int mins) {
        ClientHandler h = clients.get(ip);
        if (h != null) h.timeLimitMins = mins;
    }

    public long getClientDuration(String ip) {
        ClientHandler h = clients.get(ip);
        return h != null ? (System.currentTimeMillis() - h.connectTime) / 1000 : -1;
    }

    public void sendMouseToClient(String ip, String action, int x, int y, int btn) {
        if (!allowInput || ip == null) return;
        if ("WHEEL".equals(action)) {
            if (btn < -127 || btn > 127) return;
        } else if (!isValidMouseButton(btn)) return;
        if ("SHARE".equals(serverRole)) {
            if (!isValidMouseInput(x, y)) return;
        } else if ("RECEIVE".equals(serverRole)) {
            if (!isValidRemoteClientMouseTarget(x, y)) return;
        } else {
            return;
        }
        ClientHandler h = clients.get(ip);
        if (h != null && h.active && h.canControl) {
            h.sendEncryptedCommand("MOUSE_" + action + " " + x + " " + y + " " + btn);
        }
    }

    public void sendKeyToClient(String ip, String action, int keyCode) {
        if (!allowInput || ip == null) return;
        if (!isValidKeyCode(keyCode)) return;
        ClientHandler h = clients.get(ip);
        if (h != null && h.active && h.canControl) {
            h.sendEncryptedCommand("KEY_" + action + " " + keyCode);
        }
    }

    void notifyClientsOfFiles() {
        if (clients.isEmpty()) {
            ui.log("⚠️ No clients connected — files are queued and will be"
                 + " sent automatically when a client connects.");
            return;
        }
        ui.log("🔔 Notifying " + clients.size() + " client(s) to pull queued files...");
        for (ClientHandler h : clients.values()) {
            ui.log("   → Notifying " + h.ip + " (" + h.clientName + ")");
            h.sendEncryptedCommand("START_FILE_PULL");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ── ClientHandler ──────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════

    private class ClientHandler implements Runnable {
        private final Socket socket;
        private final String ip;
        // AtomicBoolean for true CAS-based idempotent close
        private final AtomicBoolean activeFlag = new AtomicBoolean(true);
        volatile boolean active    = true;
        volatile boolean canControl    = false;
        volatile int     timeLimitMins = 0;
        final long       connectTime   = System.currentTimeMillis();
        private long     lastActivity  = System.currentTimeMillis();

        private DataOutputStream out;
        private DataInputStream  in;
        private volatile byte[]  lastSentFrame = null;
        private String           clientName    = "Unknown";

        private int  clientFrameCount = 0;
        private long clientLastFps    = System.currentTimeMillis();
        private int  clientFpsCtr     = 0;
        private int  clientFps        = 0;

        // Per-client coalescing for displayReceivedFrame
        private final AtomicReference<BufferedImage> pendingFrame = new AtomicReference<>();
        private final AtomicReference<int[]>         pendingStats = new AtomicReference<>();
        private final AtomicBoolean                  uiPending   = new AtomicBoolean(false);

        private byte[] sendStageBuf = new byte[4 + 64 * 1024];
        private final AESEncryption clientEnc;

        
        private final BufferedImage[] uiPresentPing = new BufferedImage[2];
        private int uiPresentWriteIdx = 0;

        ClientHandler(Socket socket, String ip) {
            this.socket    = socket;
            this.ip        = ip;
            this.clientEnc = encryption;
        }

        @Override
        public void run() {
            try {
                socket.setSoTimeout((int)HANDSHAKE_TIMEOUT_MS);
                socket.setSendBufferSize(1024 * 1024);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.setPerformancePreferences(0, 1, 2);

                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 1024 * 1024));
                in  = new DataInputStream(new BufferedInputStream(socket.getInputStream(),  1024 * 1024));

                String hello = readPlainLine();
                if (!"RDP_HELLO".equals(hello)) {
                    ui.logSecurity("INVALID_HELLO", ip, hello);
                    close(); return;
                }

                if (clients.containsKey(ip)) {
                    sendPlainLine("RDP_REJECT_DUPLICATE_IP");
                    ui.logSecurity("DUPLICATE_IP", ip, "Already connected");
                    close(); return;
                }

                if (password != null && !password.isEmpty()) {
                    sendPlainLine("RDP_AUTH");
                    String clientPasswordHash = readPlainLine();
                    String serverPasswordHash = null;
                    try { serverPasswordHash = AESEncryption.hashPassword(password); } catch (Exception ex) {}
                    if (!serverPasswordHash.equals(clientPasswordHash)) {
                        recordFailedAttempt(ip);
                        sendPlainLine("RDP_REJECT_AUTH");
                        ui.log("❌ Auth failed for " + ip + " (" +
                               failedAttempts.getOrDefault(ip, 0) + "/" + MAX_FAILED_ATTEMPTS + ")");
                        close(); return;
                    }
                    clearFailedAttempts(ip);
                } else {
                    sendPlainLine("RDP_OK");
                }

                sendPlainLine("RDP_ACCEPT");
                sendPlainLine("SERVER_ROLE " + serverRole);
                sendPlainLine("AUDIO_PORT "  + audioPort);

                if (isEncryptionEnabled()) {
                    sendPlainLine("ENCRYPTION_KEY " + getEncryptionKey());
                    ui.log("🔐 Encryption key sent to " + ip);
                } else {
                    sendPlainLine("ENCRYPTION_DISABLED");
                }

                String clientRoleLine = readEncryptedLine();
                String clientRole = (clientRoleLine != null && clientRoleLine.startsWith("CLIENT_ROLE "))
                    ? clientRoleLine.substring(12).trim() : "";

                String clientNameLine = readEncryptedLine();
                clientName = (clientNameLine != null && clientNameLine.startsWith("CLIENT_NAME "))
                    ? sanitize(clientNameLine.substring(12).trim()) : "Unknown";

                boolean rolesOk = ("SHARE".equals(serverRole) && "RECEIVE".equals(clientRole))
                               || ("RECEIVE".equals(serverRole) && "SHARE".equals(clientRole));

                if (!rolesOk) {
                    sendEncryptedLine("ROLE_CONFLICT");
                    ui.logSecurity("ROLE_CONFLICT", ip, "server=" + serverRole + " client=" + clientRole);
                    close(); return;
                }
                sendEncryptedLine("ROLE_OK");

                clients.put(ip, this);
                Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                ui.addClient(clientName, ip, screen.width + "x" + screen.height);
                ui.log("✓ " + clientName + " (" + ip + ") connected 🔐 [View Only]");
                ui.logSecurity("CLIENT_CONNECTED", ip, "Name=" + clientName + " Role=" + clientRole);

                socket.setSoTimeout(0);

                if ("SHARE".equals(serverRole)) {
                    Thread push = new Thread(() -> {
                        try { streamToClient(); }
                        catch (Exception e) { close(); }
                    }, "push-" + ip);
                    push.setDaemon(true);
                    push.setPriority(Thread.MAX_PRIORITY - 1);
                    push.start();
                }

                readLoop();

            } catch (java.net.SocketTimeoutException e) {
                ui.logSecurity("TIMEOUT", ip, "Handshake timeout");
            } catch (IOException e) {
                if (active) ui.log("Connection error[" + ip + "]: " + e.getMessage());
            } finally {
                close();
            }
        }

        // ── OPTIMIZED RECEIVE LOOP ──────────────────────────────────────────
        private void readLoop() throws IOException {
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY - 1);
            byte[] recvBuf = new byte[1024 * 1024];

            // Persistent ImageReader — fixes the original ImageIO.read() slow path
            ImageReader jpegReader = ImageIO.getImageReadersByFormatName("jpg").next();
            BufferedImage receiveCanvas = null;

            try {
                while (active && running) {
                    checkTimeLimit();
                    checkIdleTimeout();

                    int token = in.readInt();
                    lastActivity = System.currentTimeMillis();

                    if (token == -1) {
                        int cmdLen = in.readInt();
                        if (cmdLen <= 0 || cmdLen > MAX_CMD_LEN * 4) {
                            ui.logSecurity("ATTACK", ip, "Invalid cmd size: " + cmdLen);
                            close(); return;
                        }
                        byte[] cmdBytes = new byte[cmdLen];
                        in.readFully(cmdBytes);

                        String line;
                        try { line = clientEnc.decryptString(cmdBytes); }
                        catch (Exception e) { continue; }

                        if (line.length() > MAX_CMD_LEN) {
                            ui.logSecurity("ATTACK", ip, "Oversized cmd: " + line.length());
                            close(); return;
                        }

                        if ("PING".equals(line)) continue;
                        if (!allowInput || !canControl) continue;

                        if (line.startsWith("MOUSE_") && robot != null) handleMouse(line);
                        else if (line.startsWith("KEY_") && robot != null) handleKey(line);

                    } else if (token == -2) {
                        int len = in.readInt();
                        if (len <= 0 || len > MAX_SKIPPED_PAYLOAD) {
                            ui.logSecurity("ATTACK", ip, "Invalid skipped payload size: " + len);
                            close(); return;
                        }
                        
                        in.skipBytes(len);

                    } else if (token > 0 && token <= MAX_FRAME_SIZE) {
                        if ("SHARE".equals(serverRole)) { in.skipBytes(token); continue; }
                        if (recvBuf.length < token) recvBuf = new byte[token + 65536];
                        in.readFully(recvBuf, 0, token);

                        byte[] decryptedData;
                        try {
                            byte[] slice = (recvBuf.length == token) ? recvBuf : Arrays.copyOf(recvBuf, token);
                            decryptedData = isEncryptionEnabled() ? clientEnc.decrypt(slice) : slice;
                        } catch (Exception e) { continue; }

                        long t0 = System.currentTimeMillis();
                        DataInputStream din = new DataInputStream(new ByteArrayInputStream(decryptedData));
                        byte frameType = din.readByte();
                        int rx = din.readInt();
                        int ry = din.readInt();

                        // Use persistent ImageReader — fixes original slow ImageIO.read() path
                        javax.imageio.stream.MemoryCacheImageInputStream mis =
                            new javax.imageio.stream.MemoryCacheImageInputStream(din);
                        jpegReader.setInput(mis, true, true);
                        BufferedImage imgChunk = jpegReader.read(0);
                        jpegReader.reset();
                        mis.close();
                        if (imgChunk == null) continue;

                        if (frameType == 0 || receiveCanvas == null) {
                            if (receiveCanvas == null
                                    || receiveCanvas.getWidth()  != imgChunk.getWidth()
                                    || receiveCanvas.getHeight() != imgChunk.getHeight()) {
                                receiveCanvas = new BufferedImage(
                                    imgChunk.getWidth(), imgChunk.getHeight(), BufferedImage.TYPE_INT_RGB);
                            }
                            Graphics2D g = receiveCanvas.createGraphics();
                            g.drawImage(imgChunk, 0, 0, null); g.dispose();
                        } else {
                            Graphics2D g = receiveCanvas.createGraphics();
                            g.drawImage(imgChunk, rx, ry, null); g.dispose();
                        }

                        
                        int rw = receiveCanvas.getWidth(), rh = receiveCanvas.getHeight();
                        BufferedImage uiFrame = uiPresentPing[uiPresentWriteIdx];
                        if (uiFrame == null || uiFrame.getWidth() != rw || uiFrame.getHeight() != rh) {
                            uiFrame = new BufferedImage(rw, rh, BufferedImage.TYPE_INT_RGB);
                            uiPresentPing[uiPresentWriteIdx] = uiFrame;
                        }
                        int[] srcPixels = ((DataBufferInt) receiveCanvas.getRaster().getDataBuffer()).getData();
                        int[] dstPixels = ((DataBufferInt) uiFrame.getRaster().getDataBuffer()).getData();
                        System.arraycopy(srcPixels, 0, dstPixels, 0, srcPixels.length);
                        uiPresentWriteIdx ^= 1;

                        long latency = System.currentTimeMillis() - t0;
                        clientFrameCount++; clientFpsCtr++;
                        long now = System.currentTimeMillis(), elapsed = now - clientLastFps;
                        if (elapsed >= 1000) {
                            clientFps    = (int)(clientFpsCtr * 1000 / elapsed);
                            clientFpsCtr = 0; clientLastFps = now;
                        }

                        
                        scheduleUIUpdate(uiFrame, clientFps, clientFrameCount, latency);

                    } else {
                        ui.logSecurity("ATTACK", ip, "Invalid token: " + token);
                        break;
                    }
                }
            } finally {
                jpegReader.dispose();
            }
        }

        
        private void scheduleUIUpdate(BufferedImage frame, int fps, int fc, long latency) {
            pendingFrame.set(frame);
            pendingStats.set(new int[]{fps, fc, (int)latency});
            if (uiPending.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(() -> {
                    uiPending.set(false);
                    BufferedImage f = pendingFrame.get();
                    int[] stats = pendingStats.get();
                    if (f != null && stats != null && active) {
                        ui.displayReceivedFrame(ip, f, stats[0], stats[1], stats[2]);
                    }
                });
            }
        }

        private void streamToClient() throws IOException {
            while (active && running) {
                checkTimeLimit();

                byte[] payloadData;
                
                for (;;) {
                    if (!active || !running) return;
                    payloadData = latestFrame.get();
                    if (payloadData != null && payloadData != lastSentFrame && payloadData.length > 0)
                        break;
                    LockSupport.parkNanos(16_000_000L);
                }

                lastSentFrame = payloadData;
                try {
                    byte[] encrypted = isEncryptionEnabled() ? clientEnc.encrypt(payloadData) : payloadData;
                        int totalLen = 4 + encrypted.length;
                        if (sendStageBuf.length < totalLen) sendStageBuf = new byte[totalLen + 65536];
                        sendStageBuf[0] = (byte)(encrypted.length >>> 24);
                        sendStageBuf[1] = (byte)(encrypted.length >>> 16);
                        sendStageBuf[2] = (byte)(encrypted.length >>>  8);
                        sendStageBuf[3] = (byte)(encrypted.length);
                        System.arraycopy(encrypted, 0, sendStageBuf, 4, encrypted.length);
                        synchronized (out) {
                            out.write(sendStageBuf, 0, totalLen);
                            out.flush();
                        }
                } catch (Exception e) {
                    throw new IOException("Frame send failed: " + e.getMessage());
                }
            }
        }

        private void checkTimeLimit() throws IOException {
            if (timeLimitMins > 0 &&
                System.currentTimeMillis() - connectTime > timeLimitMins * 60_000L) {
                ui.logSecurity("TIME_LIMIT", ip, timeLimitMins + " min limit reached");
                close(); throw new IOException("Time limit reached");
            }
        }

        private void checkIdleTimeout() throws IOException {
            if (System.currentTimeMillis() - lastActivity > IDLE_TIMEOUT_MS) {
                ui.logSecurity("IDLE_TIMEOUT", ip, "Idle for 10 minutes");
                close(); throw new IOException("Idle timeout");
            }
        }

        private void handleMouse(String line) {
            try {
                int s1=line.indexOf(' '), s2=line.indexOf(' ',s1+1), s3=line.indexOf(' ',s2+1);
                if (s1<0||s2<0||s3<0) return;
                String cmd = line.substring(0, s1);
                int x   = Integer.parseInt(line, s1+1, s2,           10);
                int y   = Integer.parseInt(line, s2+1, s3,           10);
                int btn = Integer.parseInt(line, s3+1, line.length(), 10);
                if ("MOUSE_WHEEL".equals(cmd)) {
                    if (!isValidMouseInput(x, y)) return;
                    if (btn < -127 || btn > 127) return;
                    robot.mouseMove(x, y);
                    robot.mouseWheel(btn);
                    return;
                }
                if (!isValidMouseInput(x, y) || !isValidMouseButton(btn)) return;
                robot.mouseMove(x, y);
                if (btn != 0) {
                    int mask = btn==1 ? java.awt.event.InputEvent.BUTTON1_DOWN_MASK
                             : btn==2 ? java.awt.event.InputEvent.BUTTON2_DOWN_MASK
                             :          java.awt.event.InputEvent.BUTTON3_DOWN_MASK;
                    if ("MOUSE_PRESS".equals(cmd))        robot.mousePress(mask);
                    else if ("MOUSE_RELEASE".equals(cmd)) robot.mouseRelease(mask);
                }
            } catch (Exception ignored) {}
        }

        private void handleKey(String line) {
            try {
                int sp = line.indexOf(' '); if (sp<0) return;
                String cmd = line.substring(0, sp);
                int kc = Integer.parseInt(line, sp+1, line.length(), 10);
                if (!isValidKeyCode(kc)) return;
                if ("KEY_PRESS".equals(cmd))        robot.keyPress(kc);
                else if ("KEY_RELEASE".equals(cmd)) robot.keyRelease(kc);
            } catch (Exception ignored) {}
        }

        public void sendEncryptedCommand(String cmd) {
            if (out == null || !active) return;
            try {
                byte[] encrypted = isEncryptionEnabled() ? clientEnc.encryptString(cmd) : cmd.getBytes();
                synchronized (out) {
                    out.writeInt(-1);
                    out.writeInt(encrypted.length);
                    out.write(encrypted);
                    out.flush();
                }
            } catch (Exception ignored) {}
        }

        private void sendEncryptedLine(String msg) throws IOException {
            try {
                byte[] enc = isEncryptionEnabled() ? clientEnc.encryptString(msg) : msg.getBytes();
                out.writeInt(enc.length);
                out.write(enc);
                out.flush();
            } catch (Exception e) { throw new IOException("Encrypt failed: " + e.getMessage()); }
        }

        private String readEncryptedLine() throws IOException {
            try {
                int len = in.readInt();
                if (len <= 0 || len > MAX_CMD_LEN * 4) throw new IOException("Invalid length: " + len);
                byte[] data = new byte[len];
                in.readFully(data);
                return isEncryptionEnabled() ? clientEnc.decryptString(data) : new String(data);
            } catch (Exception e) { throw new IOException("Decrypt failed"); }
        }

        private void sendPlainLine(String msg) throws IOException {
            out.writeBytes(msg + "\n"); out.flush();
        }

        private String readPlainLine() throws IOException {
            StringBuilder sb = new StringBuilder(64);
            int b; int count = 0;
            while ((b = in.read()) != -1 && count < 512) {
                char c = (char) b;
                if (c == '\n') break;
                if (c != '\r') { sb.append(c); count++; }
            }
            return sb.toString().trim();
        }
        
        public void notifyClientsOfFiles() {
            if (clients.isEmpty()) {
                ui.log("⚠️ No clients connected to receive files.");
                return;
            }
            for (ClientHandler h : clients.values()) {
                ui.log("🔔 Notifying " + h.ip + " to pull files...");
                h.sendEncryptedCommand("START_FILE_PULL");
            }
        }

        void close() {
            
            if (!activeFlag.compareAndSet(true, false)) return;
            active = false;
            clients.remove(ip);
            SwingUtilities.invokeLater(() -> ui.removeClient(ip));
            try { if (!socket.isClosed()) socket.close(); } catch (IOException ignored) {}
            ui.log("Disconnected: " + clientName + " (" + ip + ")");
            ui.logSecurity("CLIENT_DISCONNECTED", ip, "Name=" + clientName);
        }
    }
}