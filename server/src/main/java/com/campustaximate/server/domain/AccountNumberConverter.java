package com.campustaximate.server.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 계좌번호를 AES-GCM으로 암호화해 저장한다. 키는 환경 변수로 받는다. */
@Component
@Converter
public class AccountNumberConverter implements AttributeConverter<String, String> {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final String secret;

    public AccountNumberConverter(@Value("${app.account-key}") String secret) {
        this.secret = secret;
    }

    @Override
    public String convertToDatabaseColumn(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv);
            byte[] body = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + body.length).put(iv).put(body).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("account encryption failed", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            byte[] iv = java.util.Arrays.copyOf(all, IV_BYTES);
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, iv);
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("account decryption failed", e);
        }
    }

    private Cipher cipher(int mode, byte[] iv) throws GeneralSecurityException {
        if (secret.isBlank()) {
            throw new IllegalStateException("ACCOUNT_KEY is not set");
        }
        byte[] key = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        return cipher;
    }
}
