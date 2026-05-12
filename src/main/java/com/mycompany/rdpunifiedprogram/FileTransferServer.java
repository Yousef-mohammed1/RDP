package com.mycompany.rdpunifiedprogram;

import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;


public class FileTransferServer {

    private final int          filePort;
    private final RDPServer    server;
    private final FileTransferManager manager;

    private ServerSocket       serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread             acceptThread;

    
    private volatile List<File> pendingFiles = null;

    public FileTransferServer(RDPServer server, int mainPort) {
        this.server   = server;
        this.filePort = mainPort + 2;
        this.manager  = new FileTransferManager();
        this.manager.setEncryption(server.encryption, server.isEncryptionEnabled());
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    public boolean start() {
        try {
            serverSocket = new ServerSocket(filePort);
            serverSocket.setReuseAddress(true);
            running.set(true);

            acceptThread = new Thread(this::acceptLoop, "file-server-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();

            server.ui.log("📁 File Transfer Server started on port " + filePort);
            return true;
        } catch (IOException e) {
            server.ui.log("⚠️ File Transfer Server failed: " + e.getMessage());
            return false;
        }
    }

    public void stop() {
        running.set(false);
        manager.shutdown();
        try {
            if (serverSocket != null && !serverSocket.isClosed())
                serverSocket.close();
        } catch (IOException ignored) {}
    }

    public int getFilePort() { return filePort; }

    public void queueFilesToSend(List<File> files) {
        this.pendingFiles = files;
        server.ui.log("📁 " + files.size() + " file(s) queued for all clients.");
    }

    // ── Accept Loop ───────────────────────────────────────────────────────

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                configureSocket(client);
                handleConnection(client);
            } catch (IOException e) {
                if (running.get())
                    server.ui.log("⚠️ File accept error: " + e.getMessage());
            }
        }
    }

    private void handleConnection(Socket socket) {
        final String ip = socket.getInetAddress().getHostAddress();
        Thread handler = new Thread(() -> {
            try (DataInputStream  in  = new DataInputStream(
                     new BufferedInputStream(socket.getInputStream(),
                         FileTransferManager.BUFFER_SIZE));
                 DataOutputStream out = new DataOutputStream(
                     new BufferedOutputStream(socket.getOutputStream(),
                         FileTransferManager.BUFFER_SIZE))) {

                
                byte mode = in.readByte();

                if (mode == 'R') {
                    
                    List<File> toSend = pendingFiles;

                    if (toSend == null || toSend.isEmpty()) {
                        server.ui.log("⚠️ Client " + ip
                            + " requested files but nothing is queued.");
                        out.writeLong(FileTransferManager.END_MAGIC);
                        out.flush();
                        return;
                    }

                    server.ui.log("📤 Sending " + toSend.size()
                        + " file(s) to " + ip + ":");
                    for (File f : toSend)
                        server.ui.log("   • " + f.getName()
                            + " (" + formatBytes(f.length()) + ")");

                    CountDownLatch latch = new CountDownLatch(toSend.size());
                    
                    java.util.concurrent.ConcurrentHashMap<String, Integer> lastPct =
                        new java.util.concurrent.ConcurrentHashMap<>();

                    manager.sendFiles(toSend, out,
                        (name, transferred, total) -> {
                            int pct = (int)(transferred * 100L / total);
                            // Log every 10% step (0 → 10 → 20 … 100)
                            int prev = lastPct.getOrDefault(name, -1);
                            if (pct / 10 > prev / 10) {
                                lastPct.put(name, pct);
                                SwingUtilities.invokeLater(() ->
                                    server.ui.log("   📤 " + name
                                        + " — " + pct + "% ("
                                        + formatBytes(transferred)
                                        + " / " + formatBytes(total) + ")"));
                            }
                        },
                        (name, ok, msg) -> {
                            SwingUtilities.invokeLater(() ->
                                server.ui.log((ok ? "✅" : "❌")
                                    + " " + name + ": " + msg));
                            latch.countDown();
                        });

                    
                    try { latch.await(5, TimeUnit.MINUTES); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    server.ui.log("📤 Send session to " + ip + " complete.");

                } else if (mode == 'S') {
                    // ── Client is pushing files to this server ──────────────────
                    server.ui.log("📥 Receiving file(s) from " + ip + "...");

                    CountDownLatch doneLatch = new CountDownLatch(1);
                    java.util.concurrent.ConcurrentHashMap<String, Integer> lastPct =
                        new java.util.concurrent.ConcurrentHashMap<>();


                    manager.receiveFiles(in,
                        (name, transferred, total) -> {
                            int pct = (int)(transferred * 100L / total);
                            int prev = lastPct.getOrDefault(name, -1);
                            if (pct / 10 > prev / 10) {
                                lastPct.put(name, pct);
                                SwingUtilities.invokeLater(() ->
                                    server.ui.log("   📥 " + name
                                        + " — " + pct + "% ("
                                        + formatBytes(transferred)
                                        + " / " + formatBytes(total) + ")"));
                            }
                        },
                        (name, ok, msg) -> {
                            SwingUtilities.invokeLater(() ->
                                server.ui.log((ok ? "✅" : "❌")
                                    + " " + name + ": " + msg));
                            doneLatch.countDown();
                        });


                    try {
                        doneLatch.await(5, TimeUnit.MINUTES);

                        Thread.sleep(2_000L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    server.ui.log("📥 Receive session from " + ip + " complete.");
                }

            } catch (IOException e) {
                if (running.get())
                    server.ui.log("⚠️ File transfer error [" + ip + "]: "
                        + e.getMessage());
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }, "file-handler-" + ip);
        handler.setDaemon(true);
        handler.start();
    }

    
    private static String formatBytes(long bytes) {
        if (bytes < 1_024L)                  return bytes + " B";
        if (bytes < 1_048_576L)              return String.format("%.1f KB", bytes / 1_024.0);
        if (bytes < 1_073_741_824L)          return String.format("%.2f MB", bytes / 1_048_576.0);
        return                                      String.format("%.2f GB", bytes / 1_073_741_824.0);
    }

    private void configureSocket(Socket s) throws SocketException {
        s.setTcpNoDelay(true);
        s.setSendBufferSize(FileTransferManager.BUFFER_SIZE);
        s.setReceiveBufferSize(FileTransferManager.BUFFER_SIZE);
        s.setKeepAlive(true);
        s.setSoTimeout(300_000); // 5 min timeout
    }
}