package com.hify.mcp.application;

import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class McpCredentialCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    public McpCredentialCipher(@Value("${hify.mcp.credentials.master-key:}") String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) { key = null; return; }
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encodedKey.trim()); }
        catch (IllegalArgumentException exception) { throw invalidKey(); }
        try {
            if (bytes.length != 32) throw invalidKey();
            key = new SecretKeySpec(bytes, "AES");
        } finally { Arrays.fill(bytes, (byte) 0); }
    }
    public String encrypt(String serverId, String credentialId, String token) {
        requireKey();
        byte[] plain = token.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            return "v1:" + Base64.getEncoder().encodeToString(nonce) + ":"
                    + Base64.getEncoder().encodeToString(cipher(Cipher.ENCRYPT_MODE, serverId, credentialId, nonce).doFinal(plain));
        } catch (Exception exception) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "MCP credential encryption failed");
        } finally { Arrays.fill(plain, (byte) 0); }
    }
    public String decrypt(String serverId, String credentialId, String encrypted) {
        requireKey();
        byte[] plain = null;
        try {
            String[] parts = encrypted.split(":", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException();
            byte[] nonce = Base64.getDecoder().decode(parts[1]);
            if (nonce.length != 12) throw new IllegalArgumentException();
            plain = cipher(Cipher.DECRYPT_MODE, serverId, credentialId, nonce).doFinal(Base64.getDecoder().decode(parts[2]));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new BizException(ErrorCode.CONFLICT, "MCP stored credential is unavailable; check the server encryption key");
        } finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }
    private Cipher cipher(int mode, String serverId, String id, byte[] nonce) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("hify:mcp:v1:" + serverId + ":" + id).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
    private void requireKey() {
        if (key == null) throw new BizException(ErrorCode.CONFLICT,
                "直接 Token 存储尚未配置，请管理员设置 HIFY_MCP_MASTER_KEY");
    }
    private static IllegalStateException invalidKey() {
        return new IllegalStateException("HIFY_MCP_MASTER_KEY must be a Base64-encoded 32-byte key");
    }
}
