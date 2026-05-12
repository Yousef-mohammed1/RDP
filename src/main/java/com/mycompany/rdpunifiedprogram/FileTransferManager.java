package com.mycompany.rdpunifiedprogram;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;


public class FileTransferManager {

    // ── Protocol Constants ─────────────────────────────────────────────────
    static final long   FILE_MAGIC  = 0xF11EBEEFDEADC0DEL;
    static final long   END_MAGIC   = 0xDEADBEEF00000000L;
    static final int    CHUNK_SIZE  = 64 * 1024;          
    static final int    BUFFER_SIZE = 256 * 1024;         
    static final String SAVE_DIR = System.getProperty("user.home") + File.separator + "Downloads"  + File.separator + "RDPReceivedFiles";
    
    private AESEncryption encryption = null;
    private boolean encryptionEnabled = false;

    
    private final ExecutorService executor = new ThreadPoolExecutor(
        2, 4, 60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(32),
        r -> {
            Thread t = new Thread(r, "file-transfer");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        },
        new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private String sanitize(String name) {
        return sanitizeFilename(name);
    }

    // ── Callbacks ─────────────────────────────────────────────────────────
    public interface ProgressCallback {
        void onProgress(String filename, long transferred, long total);
    }

    public interface CompletionCallback {
        void onComplete(String filename, boolean success, String message);
    }

    

    public void sendFiles(List<File> files,
                          DataOutputStream out,
                          ProgressCallback   onProgress,
                          CompletionCallback onComplete) {
        cancelled.set(false);
        executor.submit(() -> {
            for (File file : files) {
                if (cancelled.get()) break;
                sendSingleFile(file, out, onProgress, onComplete);
            }
            // Write end marker
            try {
                synchronized (out) {
                    out.writeLong(END_MAGIC);
                    out.flush();
                }
            } catch (IOException ignored) {}
        });
    }

    
    public void receiveFiles(DataInputStream in,
                         ProgressCallback   onProgress,
                         CompletionCallback onComplete) {
    cancelled.set(false);
    executor.submit(() -> {
        try {
            Files.createDirectories(Paths.get(SAVE_DIR));
        } catch (IOException e) {
            if (onComplete != null)
                onComplete.onComplete("setup", false, "Cannot create save dir: " + e.getMessage());
            return;
        }

        try {
            while (!cancelled.get()) {
                long magic;
                try { 
                    magic = in.readLong(); 
                } catch (EOFException | SocketException e) { 
                    break; 
                }

                if (magic == END_MAGIC) break;
                if (magic != FILE_MAGIC) break;

                receiveSingleFile(in, onProgress, onComplete);
            }
        } catch (IOException e) {
            if (!cancelled.get())
                System.err.println("[FileTransfer] Receive error: " + e.getMessage());
            }
        });
    }
    

    public void setEncryption(AESEncryption enc, boolean enabled) {
        this.encryption      = enc;
        this.encryptionEnabled = enabled && enc != null && enc.hasKey();
    }

    
    public void cancel() { cancelled.set(true); }

    
    public void shutdown() {
        cancelled.set(true);
        executor.shutdownNow();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void sendSingleFile(File file,
                            DataOutputStream out,
                            ProgressCallback   onProgress,
                            CompletionCallback onComplete) {
    String name = file.getName();
    long   size = file.length();
    java.util.zip.CRC32 crc = new java.util.zip.CRC32();

    try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE)) {
        synchronized (out) {
            out.writeLong(FILE_MAGIC);
            byte[] nb = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(nb.length);
            out.write(nb);
            out.writeLong(size);
            out.writeBoolean(encryptionEnabled);
        }

        byte[] chunk = new byte[CHUNK_SIZE];
        long sent = 0;
        int nRead;

        while (!cancelled.get() && (nRead = bis.read(chunk)) != -1) {
            crc.update(chunk, 0, nRead);
            if (encryptionEnabled) {
                byte[] plain = (nRead == chunk.length) ? chunk : java.util.Arrays.copyOf(chunk, nRead);
                byte[] toSend = encryption.encrypt(plain);
                synchronized (out) {
                    out.writeInt(toSend.length);
                    out.write(toSend);
                }
            } else {
                synchronized (out) {
                    out.write(chunk, 0, nRead);
                }
            }
            sent += nRead;
            if (onProgress != null) onProgress.onProgress(name, sent, size);
        }

        synchronized (out) {
            out.writeInt((int) crc.getValue());
            out.flush();
        }

        if (!cancelled.get() && onComplete != null) {
            onComplete.onComplete(name, true, (encryptionEnabled ? "🔐 " : "") + "Sent " + formatSize(size));
        }

    } catch (Exception e) {
        if (onComplete != null) onComplete.onComplete(name, false, "Error: " + e.getMessage());
        }
    }

    private void receiveSingleFile(DataInputStream    in,
                               ProgressCallback   onProgress,
                               CompletionCallback onComplete)
        throws IOException {

    // ── Read header ────────────────────────────────────────────────────
    int nameLen = in.readInt();
    if (nameLen <= 0 || nameLen > 4096)
        throw new IOException("Invalid filename length: " + nameLen);
    byte[] nameBytes = new byte[nameLen];
    in.readFully(nameBytes);
    String name = sanitize(
        new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8));

    long fileSize = in.readLong();
    if (fileSize < 0 || fileSize > 10L * 1024 * 1024 * 1024)
        throw new IOException("Invalid file size: " + fileSize);

    
    boolean isEncrypted = in.readBoolean();

    
    File dest = resolveDestination(name);
    java.util.zip.CRC32 crc = new java.util.zip.CRC32();

    
    try (BufferedOutputStream bos = new BufferedOutputStream(
            new FileOutputStream(dest), BUFFER_SIZE)) {

        long received = 0;

        if (isEncrypted) {
            
            while (received < fileSize && !cancelled.get()) {
                int encLen = in.readInt();
                if (encLen <= 0 || encLen > CHUNK_SIZE * 2 + 64) 
                    throw new IOException("Invalid encrypted chunk length: " + encLen);

                byte[] encChunk = new byte[encLen];
                in.readFully(encChunk);

                byte[] plain;
                try {
                    plain = (encryptionEnabled && encryption != null)
                        ? encryption.decrypt(encChunk)
                        : encChunk; // fallback: write raw if no key available
                } catch (Exception e) {
                    throw new IOException("Decryption failed: " + e.getMessage());
                }

                crc.update(plain, 0, plain.length);
                bos.write(plain, 0, plain.length);
                received += plain.length;

                if (onProgress != null)
                    onProgress.onProgress(name, received, fileSize);
            }
        } else {
            byte[] chunk = new byte[CHUNK_SIZE];
            while (received < fileSize && !cancelled.get()) {
                int toRead = (int) Math.min(CHUNK_SIZE, fileSize - received);
                in.readFully(chunk, 0, toRead);
                crc.update(chunk, 0, toRead);
                bos.write(chunk, 0, toRead);
                received += toRead;
                if (onProgress != null)
                    onProgress.onProgress(name, received, fileSize);
            }
        }
    }

    // ── Verify CRC32 over original (decrypted) data ───────────────────
    int remoteCrc = in.readInt();
    int localCrc  = (int) crc.getValue();
    boolean ok    = remoteCrc == localCrc && !cancelled.get();

    if (!ok && dest.exists()) dest.delete();

    if (onComplete != null)
            onComplete.onComplete(name, ok,
                ok ? (isEncrypted ? "🔐 " : "") + "Saved → " + dest.getAbsolutePath()
                   : "❌ CRC mismatch — file discarded");
    }

    
    private static String sanitizeFilename(String name) {
        
        name = name.replaceAll("[/\\\\:*?\"<>|\\x00]", "_");
        if (name.isEmpty()) name = "received_file";
        return name;
    }

    
    private static File resolveDestination(String name) {
        File dir  = new File(SAVE_DIR);
        File dest = new File(dir, name);
        if (!dest.exists()) return dest;

        
        int dot  = name.lastIndexOf('.');
        String base = (dot >= 0) ? name.substring(0, dot) : name;
        String ext  = (dot >= 0) ? name.substring(dot)    : "";
        int counter = 1;
        do {
            dest = new File(dir, base + "_" + counter + ext);
            counter++;
        } while (dest.exists());
        return dest;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024)
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    
    private void onComplete(CompletionCallback cb,
                            String name, boolean ok, String msg) {
        if (cb != null) cb.onComplete(name, ok, msg);
    }
}