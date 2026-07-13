package com.tokensea.provider.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Service
public class CryptoService {
    private final byte[] primaryKey;
    private final byte[] legacyKey;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(@Value("${tokensea.crypto-key}") String encodedKey) {
        this.primaryKey = decodeKey(encodedKey);
        // V1 used the first 32 UTF-8 bytes. Keep read compatibility only; all new
        // ciphertext uses the decoded 256-bit key and an explicit v2 prefix.
        byte[] raw = encodedKey.getBytes(StandardCharsets.UTF_8);
        this.legacyKey = Arrays.copyOf(raw, 32);
    }

    public String encrypt(String plain) {
        byte[] iv = new byte[12]; random.nextBytes(iv);
        return "v2." + Base64.getEncoder().encodeToString(iv) + "." +
                Base64.getEncoder().encodeToString(crypt(Cipher.ENCRYPT_MODE, primaryKey, iv, plain.getBytes(StandardCharsets.UTF_8)));
    }

    public String decrypt(String encoded) {
        try {
            String[] parts = encoded.split("\\.");
            if (parts.length == 3 && "v2".equals(parts[0])) {
                return decryptWith(primaryKey, parts[1], parts[2]);
            }
            if (parts.length != 2) throw new IllegalArgumentException("密文格式无效");
            try { return decryptWith(legacyKey, parts[0], parts[1]); }
            catch (Exception legacyFailure) { return decryptWith(primaryKey, parts[0], parts[1]); }
        } catch (Exception e) { throw new IllegalStateException("密钥解密失败", e); }
    }

    private static String decryptWith(byte[] key, String iv, String cipher) {
        return new String(crypt(Cipher.DECRYPT_MODE, key, Base64.getDecoder().decode(iv), Base64.getDecoder().decode(cipher)), StandardCharsets.UTF_8);
    }
    private static byte[] crypt(int mode, byte[] key, byte[] iv, byte[] value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return cipher.doFinal(value);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static byte[] decodeKey(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("TOKENSEA_CRYPTO_KEY is required");
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(pad(value)); }
        catch (IllegalArgumentException e) { decoded = Base64.getUrlDecoder().decode(pad(value)); }
        if (decoded.length != 32) throw new IllegalArgumentException("TOKENSEA_CRYPTO_KEY must be a base64 encoded 32-byte key");
        return decoded;
    }
    private static String pad(String value) {
        int missing = (4 - value.length() % 4) % 4;
        return value + "=".repeat(missing);
    }
}
