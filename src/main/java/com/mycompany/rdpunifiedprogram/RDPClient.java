package com.mycompany.rdpunifiedprogram;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;


public class RDPClient {

    private static final int  MAX_FRAME_SIZE       = 20 * 1024 * 1024;
    private static final int  MAX_CMD_LEN          = 512;
    private static final long CONNECTION_TIMEOUT   = 10000;
    private static final int  DISCOVERY_TIMEOUT    = 3000;
    private static final int  MAX_DISCOVERY_TRIES  = 2;
    private static final int  FRAME_DELAY_MS       = 16;
    private static final int  IFRAME_INTERVAL      = 90;

    
    AESEncryption encryption = new AESEncryption();
    boolean encryptionEnabled = false;

    final ClientFrame ui;

    private Socket           socket;
    private DataInputStream  dataIn;
    private DataOutputStream dataOut;
    private volatile boolean connected = false;
    
    private final AtomicBoolean sessionClosed = new AtomicBoolean(false);

    private AudioClient      audioClient;
    
    private AudioShareUploader audioShareUploader;
    private FileTransferClient fileTransferClient;
    private int              audioPort    = 0;

    private int  frameCount  = 0;
    private long lastFpsTime = System.currentTimeMillis();
    private int  fpsCounter  = 0;
    private int  currentFps  = 0;

    private static final long PING_INTERVAL_MS  = 2000;
    private static final long MOUSE_THROTTLE_MS = 16;
    private long lastEventSentAt = 0;
    private long lastMouseSentAt = 0;

    private byte[] recvBuf    = new byte[1024 * 1024];
    private String clientRole = "RECEIVE";
    private String connectedHost = null;

    private Robot robot;
    private final AtomicReference<byte[]> latestFrame   = new AtomicReference<>();
    private volatile boolean              capturing     = false;

    
    private final AtomicReference<BufferedImage> pendingFrame    = new AtomicReference<>();
    private final AtomicReference<int[]>         pendingStats    = new AtomicReference<>();
    private final AtomicBoolean                  frameUpdatePending = new AtomicBoolean(false);

    
    private volatile ThreadPoolExecutor robotExecutor;

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
    private byte[] sendStageBuf = new byte[4 + 64 * 1024];

    
    private final BufferedImage[] recvUiPresentPing = new BufferedImage[2];
    private int recvUiPresentWriteIdx = 0;

    public RDPClient(ClientFrame ui) {
        this.ui = ui;
        try {
            robot = new Robot();
            robot.setAutoDelay(0);
        } catch (AWTException e) {
            System.err.println("Robot init failed: " + e.getMessage());
        }
    }

    public void setClientRole(String role) { this.clientRole = role; }
    public String getClientRole()          { return clientRole; }
    public boolean canShare()              { return robot != null; }
    public int getAudioPort()              { return audioPort; }
    public String getConnectedHost()       { return connectedHost; }
    public int getFilePort()               { return (audioPort > 0) ? audioPort + 1 : 0; }
    public FileTransferClient getFileTransferClient() { return fileTransferClient; }

    
    private static boolean isUsableDiscoveryPeer(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()) return false;
        return true;
    }

    private DatagramSocket openDatagramForDiscovery(InetAddress bindLocal) throws SocketException {
        if (bindLocal == null) return new DatagramSocket();
        return new DatagramSocket(0, bindLocal);
    }

    
    private InetAddress tryDiscoverTo(InetAddress broadcast, int discoveryPort, InetAddress bindLocal) {
        try (DatagramSocket c = openDatagramForDiscovery(bindLocal)) {
            c.setBroadcast(true);
            c.setSoTimeout(DISCOVERY_TIMEOUT);
            byte[] sendData = "DISCOVER_RDP".getBytes();
            c.send(new DatagramPacket(sendData, sendData.length, broadcast, discoveryPort));
            byte[] buf = new byte[256];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            c.receive(pkt);
            String msg = new String(pkt.getData(), 0, pkt.getLength()).trim();
            if (!"RDP_SERVER".equals(msg)) return null;
            InetAddress peer = pkt.getAddress();
            return isUsableDiscoveryPeer(peer) ? peer : null;
        } catch (SocketTimeoutException e) {
            return null;
        } catch (Exception e) {
            System.err.println("Discovery error: " + e.getMessage());
            return null;
        }
    }

    private InetAddress discoverServerAddress(int mainPort) {
        final int discoveryPort = mainPort + 100;
        try {
            for (int attempt = 1; attempt <= MAX_DISCOVERY_TRIES; attempt++) {
                InetAddress a = null;
                try {
                    a = tryDiscoverTo(InetAddress.getByName("255.255.255.255"), discoveryPort, null);
                } catch (UnknownHostException ex) {
                    System.getLogger(RDPClient.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
                }
                if (a != null) return a;
                System.out.println("Discovery attempt " + attempt + " timed out");
            }
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress bc = ia.getBroadcast();
                    if (bc == null) continue;
                    InetAddress local = ia.getAddress();
                    if (local == null) continue;
                    InetAddress a = tryDiscoverTo(bc, discoveryPort, local);
                    if (a != null) return a;
                }
            }
        } catch (SocketException se) {
            System.err.println("Discovery network enumeration failed: " + se.getMessage());
        }
        return null;
    }

    public boolean connect(int port, String password, String clientName) {
        sessionClosed.set(false);
        try {
            ui.setStatus("Searching for server...", false);
            InetAddress serverAddr = discoverServerAddress(port);
            if (serverAddr == null) {
                ui.setStatus("Server not found.", true);
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(ui,
                    "Could not find the RDP Server on the local network.\n" +
                    "• Make sure the Server is running\n" +
                    "• Check Windows Firewall\n" +
                    "• Ensure you are on the same network",
                    "Server Not Found", JOptionPane.WARNING_MESSAGE));
                return false;
            }

            connectedHost = serverAddr.getHostAddress();
            ui.setStatus("Connecting to " + connectedHost + ":" + port + " ...", false);

            socket = new Socket();
            socket.connect(new InetSocketAddress(serverAddr, port), (int)CONNECTION_TIMEOUT);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setReceiveBufferSize(1024 * 1024);
            socket.setSendBufferSize(1024 * 1024);
            socket.setPerformancePreferences(0, 1, 2);
            socket.setSoTimeout(30000);

            dataIn  = new DataInputStream(new BufferedInputStream(socket.getInputStream(),   1024 * 1024));
            dataOut = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 1024 * 1024));

            sendPlainLine("RDP_HELLO");
            String response = readPlainLine();

            if ("RDP_REJECT_DUPLICATE_IP".equals(response)) {
                ui.setStatus("Rejected: IP already connected.", true);
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(ui,
                    "Your IP is already connected to this server.",
                    "Duplicate IP", JOptionPane.ERROR_MESSAGE));
                closeSession(); return false;
            }

            if ("RDP_AUTH".equals(response)) {
                sendPlainLine(AESEncryption.hashPassword(password));
            } else if (!"RDP_OK".equals(response)) {
                ui.setStatus("Unexpected response: " + response, true);
                closeSession(); return false;
            }

            String accept = readPlainLine();
            if ("RDP_REJECT_AUTH".equals(accept) || !"RDP_ACCEPT".equals(accept)) {
                ui.setStatus("Authentication failed. Wrong password.", true);
                closeSession(); return false;
            }

            String serverRoleLine = readPlainLine();
            String serverRole = (serverRoleLine != null && serverRoleLine.startsWith("SERVER_ROLE "))
                ? serverRoleLine.substring(12).trim() : "SHARE";

            String configLine = readPlainLine();
            if (configLine != null && configLine.startsWith("AUDIO_PORT ")) {
                try {
                    audioPort = Integer.parseInt(configLine.substring(11).trim());
                    if (audioPort < 1 || audioPort > 65535) audioPort = 0;
                } catch (NumberFormatException e) { audioPort = 0; }
            }

            String encLine = readPlainLine();
            if (encLine != null && encLine.startsWith("ENCRYPTION_KEY ")) {
                try {
                    encryption.setKeyFromBase64(encLine.substring(15).trim());
                    encryptionEnabled = true;
                    ui.setStatus("🔐 Encrypted connection established", false);
                } catch (Exception e) { encryptionEnabled = false; }
            } else if ("ENCRYPTION_DISABLED".equals(encLine)) {
                encryptionEnabled = false;
            }

            sendEncryptedLine("CLIENT_ROLE " + clientRole);
            sendEncryptedLine("CLIENT_NAME " + clientName);

            String roleResult = readEncryptedLine();
            if ("ROLE_CONFLICT".equals(roleResult)) {
                ui.setStatus("Role conflict.", true); closeSession(); return false;
            }
            if (!"ROLE_OK".equals(roleResult)) {
                ui.setStatus("Role verification failed.", true); closeSession(); return false;
            }

            connected   = true;
            frameCount  = 0;
            lastFpsTime = System.currentTimeMillis();
            socket.setSoTimeout(0);

            ui.setStatus("Connected 🔐 | role: " + clientRole, false);

            recreateRobotExecutor();

            startDaemonThread(this::pingLoop,  "rdp-ping",  Thread.NORM_PRIORITY);
            startDaemonThread(this::readLoop,  "rdp-read",  Thread.MAX_PRIORITY - 1);

            if ("SHARE".equals(clientRole)) {
                capturing = true;
                startDaemonThread(this::captureLoop,       "rdp-capture",   Thread.MAX_PRIORITY);
                startDaemonThread(this::sendFrameLoop,     "rdp-send",      Thread.MAX_PRIORITY - 1);
                if (audioPort > 0 && "RECEIVE".equals(serverRole)) {
                    audioShareUploader = new AudioShareUploader(this);
                    final String host = connectedHost;
                    startDaemonThread(() -> audioShareUploader.start(host, audioPort),
                        "audio-upload", Thread.NORM_PRIORITY);
                }
            } else if ("RECEIVE".equals(clientRole) && audioPort > 0 && "SHARE".equals(serverRole)) {
                // Server is sharing host audio from AudioServer on port+1.
                audioClient = new AudioClient(this);
                startDaemonThread(() -> audioClient.connect(connectedHost, audioPort),
                    "audio-connect", Thread.NORM_PRIORITY);
            }
            
            
            if (connectedHost != null) {
                fileTransferClient = new FileTransferClient(this, connectedHost, port);
            }

            return true;

        } catch (SocketTimeoutException e) {
            ui.setStatus("Connection timeout.", true); closeSession(); return false;
        } catch (ConnectException e) {
            ui.setStatus("Connection refused.", true); closeSession(); return false;
        } catch (IOException e) {
            ui.setStatus("Connection failed: " + e.getMessage(), true); closeSession(); return false;
        } catch (Exception e) {
            ui.setStatus("Error: " + e.getMessage(), true); closeSession(); return false;
        }
    }

    private void startDaemonThread(Runnable r, String name, int priority) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(priority);
        t.setUncaughtExceptionHandler((th, e) -> {
            System.err.println("[" + name + "] Uncaught: " + e.getMessage());
            e.printStackTrace();
        });
        t.start();
    }

    public void sendMouse(String action, int x, int y, int button) {
        if (!connected || dataOut == null) return;
        if ("MOVE".equals(action)) {
            long now = System.currentTimeMillis();
            if (now - lastMouseSentAt < MOUSE_THROTTLE_MS) return;
            lastMouseSentAt = now;
        }
        if (x < 0 || y < 0 || x > 10000 || y > 10000) return;
        if ("WHEEL".equals(action)) {
            if (button < -127 || button > 127) return;
        } else if (button < 0 || button > 3) return;
        try { sendEncryptedCommand("MOUSE_" + action + " " + x + " " + y + " " + button); }
        catch (IOException ignored) {}
        lastEventSentAt = System.currentTimeMillis();
    }

    public void sendKey(String action, int keyCode) {
        if (!connected || dataOut == null) return;
        if (keyCode < 0 || keyCode > 65535) return;
        try { sendEncryptedCommand("KEY_" + action + " " + keyCode); }
        catch (IOException ignored) {}
        lastEventSentAt = System.currentTimeMillis();
    }

    // ── OPTIMIZED RECEIVE LOOP ─────────────────────────────────────────────
    private void readLoop() {
        
        ImageReader jpegReader = ImageIO.getImageReadersByFormatName("jpg").next();
        BufferedImage receiveCanvas = null;

        try {
            while (connected) {
                int token = dataIn.readInt();

                if (token == -1) {
                    int cmdLen = dataIn.readInt();
                    if (cmdLen <= 0 || cmdLen > MAX_CMD_LEN * 4) {
                        System.err.println("Invalid cmd length: " + cmdLen);
                        return;
                    }
                    byte[] cmdBytes = new byte[cmdLen];
                    dataIn.readFully(cmdBytes);

                    String line;
                    try {
                        line = encryptionEnabled ? encryption.decryptString(cmdBytes) : new String(cmdBytes);
                    } catch (Exception e) { continue; }

                    if (line.startsWith("MOUSE_") && robot != null) {
                        final String cmdLine = line;
                        ThreadPoolExecutor ex = robotExecutor;
                        if (ex != null) {
                            try {
                                ex.submit(() -> handleMouseEvent(cmdLine));
                            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                                
                            }
                        }
                    } else if (line.startsWith("KEY_") && robot != null) {
                        final String cmdLine = line;
                        ThreadPoolExecutor ex = robotExecutor;
                        if (ex != null) {
                            try {
                                ex.submit(() -> handleKeyEvent(cmdLine));
                            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                            }
                        }
                    }else if ("START_FILE_PULL".equals(line)) {
                        ui.setStatus("📁 Receiving file transfer notification...", false); // Log to client UI
                        if (fileTransferClient != null) {
                            fileTransferClient.receiveFiles(); 
                        } else {
                            ui.setStatus("❌ File client not initialized!", true);
                        }
                    }

                } else if (token > 0 && token <= MAX_FRAME_SIZE) {
                    if (recvBuf.length < token) recvBuf = new byte[token + 65536];
                    dataIn.readFully(recvBuf, 0, token);

                    byte[] decryptedData;
                    try {
                        byte[] slice = (recvBuf.length == token) ? recvBuf : Arrays.copyOf(recvBuf, token);
                        decryptedData = encryptionEnabled ? encryption.decrypt(slice) : slice;
                    } catch (Exception e) { continue; }

                    long t0 = System.currentTimeMillis();

                    // Reuse ByteArrayInputStream wrapper
                    DataInputStream din = new DataInputStream(new ByteArrayInputStream(decryptedData));
                    byte frameType = din.readByte();
                    int rx = din.readInt();
                    int ry = din.readInt();

                    // Persistent ImageReader with reset between frames
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
                    BufferedImage uiFrame = recvUiPresentPing[recvUiPresentWriteIdx];
                    if (uiFrame == null || uiFrame.getWidth() != rw || uiFrame.getHeight() != rh) {
                        uiFrame = new BufferedImage(rw, rh, BufferedImage.TYPE_INT_RGB);
                        recvUiPresentPing[recvUiPresentWriteIdx] = uiFrame;
                    }
                    int[] srcPixels = ((DataBufferInt) receiveCanvas.getRaster().getDataBuffer()).getData();
                    int[] dstPixels = ((DataBufferInt) uiFrame.getRaster().getDataBuffer()).getData();
                    System.arraycopy(srcPixels, 0, dstPixels, 0, srcPixels.length);
                    recvUiPresentWriteIdx ^= 1;

                    long latency = System.currentTimeMillis() - t0;
                    frameCount++; fpsCounter++;
                    long now = System.currentTimeMillis(), elapsed = now - lastFpsTime;
                    if (elapsed >= 1000) {
                        currentFps = (int)(fpsCounter * 1000 / elapsed);
                        fpsCounter = 0; lastFpsTime = now;
                    }

                    
                    scheduleFrameUpdate(uiFrame, currentFps, frameCount, latency);

                } else {
                    System.err.println("Invalid token: " + token);
                    return;
                }
            }
        } catch (EOFException e) {
            if (connected) ui.onConnectionLost("Server disconnected.");
        } catch (SocketTimeoutException e) {
            if (connected) ui.onConnectionLost("Connection timeout.");
        } catch (IOException e) {
            if (connected) ui.onConnectionLost("Error: " + e.getMessage());
        } finally {
            jpegReader.dispose();
            closeSession();
        }
    }

    
    private void scheduleFrameUpdate(BufferedImage frame, int fps, int fc, long latency) {
        pendingFrame.set(frame);
        pendingStats.set(new int[]{fps, fc, (int)latency});
        
        if (frameUpdatePending.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(() -> {
                frameUpdatePending.set(false);
                BufferedImage f = pendingFrame.get();
                int[] stats = pendingStats.get();
                if (f != null && stats != null) {
                    ui.updateFrame(f, stats[0], stats[1], stats[2]);
                }
            });
        }
    }

    // ── OPTIMIZED CAPTURE LOOP ─────────────────────────────────────────────
    private BufferedImage captureCanvas = null;

    private void captureLoop() {
        int[] px = new int[7]; int[] py = new int[7];
        Dimension screenDim = Toolkit.getDefaultToolkit().getScreenSize();
        Rectangle screen    = new Rectangle(screenDim);
        int w = screenDim.width, h = screenDim.height;

        captureCanvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D canvasG = captureCanvas.createGraphics();

        int[] prevPixels    = null;
        int   iFrameCounter = 0;

        while (connected && capturing) {
            long t0 = System.currentTimeMillis();
            try {
                BufferedImage raw = robot.createScreenCapture(screen);
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
                        long remaining = FRAME_DELAY_MS - (System.currentTimeMillis() - t0);
                        if (remaining > 0)
                            java.util.concurrent.locks.LockSupport.parkNanos(remaining * 1_000_000L);
                        continue;
                    }

                    int subW = maxX - minX + 1;
                    int subH = maxY - minY + 1;
                    BufferedImage deltaImg = captureCanvas.getSubimage(minX, minY, subW, subH);
                    byte[] jpegBytes = toJpegBytes(deltaImg, 0.85f);

                    payloadBaos.reset();
                    DataOutputStream dout = new DataOutputStream(payloadBaos);
                    dout.writeByte(isIFrame ? 0 : 1);
                    dout.writeInt(minX);
                    dout.writeInt(minY);
                    dout.write(jpegBytes);
                    dout.flush();

                    byte[] finalFrame = payloadBaos.toByteArray();
                    latestFrame.set(finalFrame);
                    
                }
            } catch (Exception e) {
                System.err.println("Capture error: " + e.getMessage());
            }

            long remaining = FRAME_DELAY_MS - (System.currentTimeMillis() - t0);
            if (remaining > 0)
                java.util.concurrent.locks.LockSupport.parkNanos(remaining * 1_000_000L);
        }
        canvasG.dispose();
    }

    private void sendFrameLoop() {
        byte[] lastSent = null;
        while (connected && capturing) {
            byte[] payloadData;
            for (;;) {
                if (!connected || !capturing) return;
                payloadData = latestFrame.get();
                if (payloadData != null && payloadData != lastSent && payloadData.length > 0)
                    break;
                LockSupport.parkNanos(16_000_000L);
            }

            lastSent = payloadData;
            try {
                byte[] encrypted = encryptionEnabled ? encryption.encrypt(payloadData) : payloadData;
                int totalLen = 4 + encrypted.length;
                if (sendStageBuf.length < totalLen) sendStageBuf = new byte[totalLen + 65536];
                sendStageBuf[0] = (byte)(encrypted.length >>> 24);
                sendStageBuf[1] = (byte)(encrypted.length >>> 16);
                sendStageBuf[2] = (byte)(encrypted.length >>>  8);
                sendStageBuf[3] = (byte)(encrypted.length);
                System.arraycopy(encrypted, 0, sendStageBuf, 4, encrypted.length);
                synchronized (dataOut) {
                    dataOut.write(sendStageBuf, 0, totalLen);
                    dataOut.flush();
                }
            } catch (Exception ignored) {}
        }
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

    private void handleMouseEvent(String line) {
        try {
            int s1=line.indexOf(' '), s2=line.indexOf(' ',s1+1), s3=line.indexOf(' ',s2+1);
            if (s1<0||s2<0||s3<0) return;
            String cmd = line.substring(0, s1);
            int x   = Integer.parseInt(line, s1+1, s2,           10);
            int y   = Integer.parseInt(line, s2+1, s3,           10);
            int btn = Integer.parseInt(line, s3+1, line.length(), 10);
            
            if ("MOUSE_WHEEL".equals(cmd)) {
                if (x < 0 || y < 0 || x > 32767 || y > 32767) return;
                if (btn < -127 || btn > 127) return;
                robot.mouseMove(x, y);
                robot.mouseWheel(btn);
                return;
            }
            if (x < 0 || y < 0 || x > 32767 || y > 32767 || btn < 0 || btn > 3) return;
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

    private void handleKeyEvent(String line) {
        try {
            int sp = line.indexOf(' '); if (sp<0) return;
            String cmd = line.substring(0, sp);
            int kc = Integer.parseInt(line, sp+1, line.length(), 10);
            if (kc<0||kc>65535) return;
            if ("KEY_PRESS".equals(cmd))        robot.keyPress(kc);
            else if ("KEY_RELEASE".equals(cmd)) robot.keyRelease(kc);
        } catch (Exception ignored) {}
    }

    private void pingLoop() {
        while (connected) {
            try {
                Thread.sleep(500);
                long idle = System.currentTimeMillis() - lastEventSentAt;
                if (idle >= PING_INTERVAL_MS && dataOut != null) {
                    sendEncryptedCommand("PING");
                    lastEventSentAt = System.currentTimeMillis();
                }
            } catch (Exception e) { break; }
        }
    }

    private void sendEncryptedCommand(String cmd) throws IOException {
        if (dataOut == null) return;
        try {
            byte[] enc = encryptionEnabled ? encryption.encryptString(cmd) : cmd.getBytes();
            synchronized (dataOut) {
                dataOut.writeInt(-1);
                dataOut.writeInt(enc.length);
                dataOut.write(enc);
                dataOut.flush();
            }
        } catch (Exception e) { throw new IOException("Encrypt cmd failed: " + e.getMessage()); }
    }

    private void sendEncryptedLine(String msg) throws IOException {
        try {
            byte[] enc = encryptionEnabled ? encryption.encryptString(msg) : msg.getBytes();
            dataOut.writeInt(enc.length);
            dataOut.write(enc);
            dataOut.flush();
        } catch (Exception e) { throw new IOException("Encrypt line failed: " + e.getMessage()); }
    }

    private String readEncryptedLine() throws IOException {
        try {
            int len = dataIn.readInt();
            if (len <= 0 || len > MAX_CMD_LEN * 4) throw new IOException("Invalid line length: " + len);
            byte[] data = new byte[len];
            dataIn.readFully(data);
            return encryptionEnabled ? encryption.decryptString(data) : new String(data);
        } catch (IOException e) { throw e; }
          catch (Exception e)  { throw new IOException("Decrypt line failed: " + e.getMessage()); }
    }

    private void sendPlainLine(String msg) throws IOException {
        dataOut.writeBytes(msg + "\n"); dataOut.flush();
    }

    private String readPlainLine() throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int b; int count = 0;
        while ((b = dataIn.read()) != -1 && count < 512) {
            char c = (char) b;
            if (c == '\n') break;
            if (c != '\r') { sb.append(c); count++; }
        }
        return sb.toString().trim();
    }

    public void disconnect() {
        closeSession();
    }

    
    private void recreateRobotExecutor() {
        shutdownRobotExecutor();
        robotExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, "rdp-client-robot-executor");
                t.setPriority(Thread.MAX_PRIORITY);
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    }

    private void shutdownRobotExecutor() {
        ThreadPoolExecutor ex = robotExecutor;
        if (ex != null) {
            robotExecutor = null;
            ex.shutdownNow();
            try {
                ex.awaitTermination(2L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    
    private void closeSession() {
        if (!sessionClosed.compareAndSet(false, true)) return;

        connected = false;
        capturing = false;

        if (audioClient != null) {
            audioClient.stop();
            audioClient = null;
        }
        if (audioShareUploader != null) {
            audioShareUploader.stop();
            audioShareUploader = null;
        }
        if (fileTransferClient != null) {
            fileTransferClient.shutdown();
            fileTransferClient = null;
        }

        DataOutputStream dout = dataOut;
        DataInputStream  din  = dataIn;
        dataOut = null;
        dataIn  = null;
        try { if (dout != null) dout.close(); } catch (IOException ignored) {}
        try { if (din  != null) din.close();  } catch (IOException ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); }
        catch (IOException e) { System.err.println("Close error: " + e.getMessage()); }
        connectedHost = null;
        shutdownRobotExecutor();
    }
}