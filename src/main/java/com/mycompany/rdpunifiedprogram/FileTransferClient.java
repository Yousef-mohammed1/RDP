package com.mycompany.rdpunifiedprogram;

import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;


public class FileTransferClient {

    private final String  serverHost;
    private final int     filePort;
    private final RDPClient client;
    private final FileTransferManager manager;
    private final AtomicBoolean active = new AtomicBoolean(false);

    public FileTransferClient(RDPClient client, String host, int mainPort) {
        this.client     = client;
        this.serverHost = host;
        this.filePort   = mainPort + 2;
        this.manager    = new FileTransferManager();
        this.manager.setEncryption(client.encryption, client.encryptionEnabled);
    }

    // ── Public API ─────────────────────────────────────────────────────────

    
    public void sendFiles(List<File> files, Runnable onAllDone) {
        if (active.getAndSet(true)) {
            client.ui.setStatus("⚠️ Transfer already in progress", true);
            return;
        }
        Thread t = new Thread(() -> {
            client.ui.setStatus("📤 Connecting for file send...", false);
            try (Socket socket = openSocket();
                 DataOutputStream out = new DataOutputStream(
                     new BufferedOutputStream(socket.getOutputStream(),
                         FileTransferManager.BUFFER_SIZE));
                 DataInputStream in = new DataInputStream(
                     new BufferedInputStream(socket.getInputStream(),
                         FileTransferManager.BUFFER_SIZE))) {

                // Mode: Send
                out.writeByte('S');
                out.flush();

                CountDownLatch latch = new CountDownLatch(files.size());
                manager.sendFiles(files, out,
                    (name, transferred, total) -> {
                        int pct = (int)(transferred * 100 / total);
                        SwingUtilities.invokeLater(() ->
                            client.ui.setStatus(
                                "📤 " + name + " " + pct + "%", false));
                    },
                    (name, ok, msg) -> {
                        SwingUtilities.invokeLater(() ->
                            client.ui.setStatus(
                                (ok ? "✅ " : "❌ ") + name + ": " + msg,
                                !ok));
                        latch.countDown();
                    });

                latch.await(5, TimeUnit.MINUTES);

            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                    client.ui.setStatus(
                        "❌ File send failed: " + e.getMessage(), true));
            } finally {
                active.set(false);
                if (onAllDone != null) {
                    SwingUtilities.invokeLater(onAllDone);
                }
            }
        }, "file-client-send");
        t.setDaemon(true);
        t.start();
    }

    public void receiveFiles() {
        if (active.getAndSet(true)) {
            client.ui.setStatus("⚠️ Transfer already in progress", true);
            return;
        }
        Thread t = new Thread(() -> {
            client.ui.setStatus("📥 Connecting to receive files...", false);

            CountDownLatch firstDone = new CountDownLatch(1);

            try (Socket socket = openSocket();
                 DataOutputStream out = new DataOutputStream(
                     new BufferedOutputStream(socket.getOutputStream(),
                         FileTransferManager.BUFFER_SIZE));
                 DataInputStream in = new DataInputStream(
                     new BufferedInputStream(socket.getInputStream(),
                         FileTransferManager.BUFFER_SIZE))) {

                // Mode: Receive
                out.writeByte('R');
                out.flush();

                manager.receiveFiles(in,
                    (name, transferred, total) -> {
                        int pct = (int)(transferred * 100 / total);
                        SwingUtilities.invokeLater(() ->
                            client.ui.setStatus(
                                "📥 " + name + " " + pct + "%", false));
                    },
                    (name, ok, msg) -> {
                        SwingUtilities.invokeLater(() ->
                            client.ui.setStatus(
                                (ok ? "✅ " : "❌ ") + name + ": " + msg,
                                !ok));

                        firstDone.countDown();
                    });

                boolean anyCompleted = firstDone.await(5, TimeUnit.MINUTES);
                if (anyCompleted) {

                    Thread.sleep(30_000L);
                }

            } catch (Exception e) {
                if (!(e instanceof InterruptedException))
                    SwingUtilities.invokeLater(() ->
                        client.ui.setStatus(
                            "❌ File receive failed: " + e.getMessage(), true));
            } finally {
                active.set(false);
            }
        }, "file-client-recv");
        t.setDaemon(true);
        t.start();
    }

    public void shutdown() { manager.shutdown(); }

    // ── Private helpers ────────────────────────────────────────────────────

    private Socket openSocket() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(serverHost, filePort), 10_000);
        s.setTcpNoDelay(true);
        s.setSendBufferSize(FileTransferManager.BUFFER_SIZE);
        s.setReceiveBufferSize(FileTransferManager.BUFFER_SIZE);
        s.setKeepAlive(true);
        s.setSoTimeout(300_000);
        return s;
    }
}