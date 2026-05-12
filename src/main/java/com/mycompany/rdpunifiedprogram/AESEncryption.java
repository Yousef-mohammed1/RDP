package com.mycompany.rdpunifiedprogram;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;


public class AESEncryption {

    private static final String ALGORITHM   = "AES/GCM/NoPadding";
    private static final int    KEY_SIZE    = 256;
    private static final int    GCM_IV_LEN  = 12;
    private static final int    GCM_TAG_LEN = 128;

    
    private static final ThreadLocal<Cipher> TL_CIPHER = ThreadLocal.withInitial(() -> {
        try { return Cipher.getInstance(ALGORITHM); }
        catch (Exception e) { throw new RuntimeException("AES/GCM not available", e); }
    });

    
    private static final ThreadLocal<SecureRandom> TL_RANDOM =
        ThreadLocal.withInitial(SecureRandom::new);

    
    private static final ThreadLocal<byte[]> TL_IV =
        ThreadLocal.withInitial(() -> new byte[GCM_IV_LEN]);

    private volatile SecretKey secretKey;

    // ── Key Management ────────────────────────────────────────────────────

    public void generateKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(KEY_SIZE, TL_RANDOM.get());
        this.secretKey = kg.generateKey();
    }

    public void setKey(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != 32)
            throw new IllegalArgumentException("Key must be 32 bytes (256 bits)");
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    public void setKeyFromBase64(String base64Key) {
        if (base64Key == null || base64Key.trim().isEmpty())
            throw new IllegalArgumentException("Key cannot be null or empty");
        setKey(Base64.getDecoder().decode(base64Key.trim()));
    }

    public byte[] getKeyBytes()  { return secretKey != null ? secretKey.getEncoded() : null; }
    public String getKeyBase64() { return secretKey != null ? Base64.getEncoder().encodeToString(secretKey.getEncoded()) : null; }
    public boolean hasKey()      { return secretKey != null; }

    // ── Encryption / Decryption ───────────────────────────────────────────

    
    public byte[] encrypt(byte[] plainData) throws Exception {
        if (secretKey == null) throw new IllegalStateException("Encryption key not set");
        if (plainData == null || plainData.length == 0)
            throw new IllegalArgumentException("Data to encrypt cannot be empty");

        byte[] iv = TL_IV.get();
        TL_RANDOM.get().nextBytes(iv);

        Cipher cipher = TL_CIPHER.get();
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LEN, iv));
        byte[] cipherText = cipher.doFinal(plainData);

        
        byte[] result = new byte[GCM_IV_LEN + cipherText.length];
        System.arraycopy(iv,         0, result, 0,           GCM_IV_LEN);
        System.arraycopy(cipherText, 0, result, GCM_IV_LEN, cipherText.length);
        return result;
    }

    
    public byte[] decrypt(byte[] encryptedData) throws Exception {
        if (secretKey == null) throw new IllegalStateException("Decryption key not set");
        if (encryptedData == null || encryptedData.length <= GCM_IV_LEN)
            throw new IllegalArgumentException("Encrypted data too short or null");

        
        Cipher cipher = TL_CIPHER.get();
        cipher.init(Cipher.DECRYPT_MODE, secretKey,
            new GCMParameterSpec(GCM_TAG_LEN, encryptedData, 0, GCM_IV_LEN));
        return cipher.doFinal(encryptedData, GCM_IV_LEN, encryptedData.length - GCM_IV_LEN);
    }

    public byte[] encryptString(String text) throws Exception {
        if (text == null) throw new IllegalArgumentException("Text cannot be null");
        return encrypt(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public String decryptString(byte[] encryptedData) throws Exception {
        return new String(decrypt(encryptedData), java.nio.charset.StandardCharsets.UTF_8);
    }

   
    public static String hashPassword(String password) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    
    public static boolean selfTest() {
        try {
            AESEncryption aes = new AESEncryption();
            aes.generateKey();

            byte[] testData = "Test screen frame data".getBytes();
            byte[] encrypted = aes.encrypt(testData);
            byte[] decrypted = aes.decrypt(encrypted);
            if (!java.util.Arrays.equals(testData, decrypted)) return false;

            String testCmd = "MOUSE_MOVE 100 200 0";
            byte[] encCmd  = aes.encryptString(testCmd);
            String decCmd  = aes.decryptString(encCmd);
            if (!testCmd.equals(decCmd)) return false;

            String keyBase64 = aes.getKeyBase64();
            AESEncryption aes2 = new AESEncryption();
            aes2.setKeyFromBase64(keyBase64);
            byte[] decrypted2 = aes2.decrypt(encrypted);
            return java.util.Arrays.equals(testData, decrypted2);

        } catch (Exception e) {
            System.err.println("Encryption self-test failed: " + e.getMessage());
            return false;
        }
    }
}