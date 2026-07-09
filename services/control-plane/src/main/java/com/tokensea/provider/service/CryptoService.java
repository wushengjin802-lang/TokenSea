package com.tokensea.provider.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class CryptoService {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();
    public CryptoService(@Value("${tokensea.crypto-key}") String raw) {
        byte[] b = raw.getBytes(StandardCharsets.UTF_8);
        key = new byte[32];
        System.arraycopy(b, 0, key, 0, Math.min(32, b.length));
    }
    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + "." + Base64.getEncoder().encodeToString(enc);
        } catch (Exception e) { throw new IllegalStateException("加密失败", e); }
    }
}
