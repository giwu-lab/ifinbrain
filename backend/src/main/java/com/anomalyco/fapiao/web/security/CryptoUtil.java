package com.anomalyco.fapiao.web.security;

import com.anomalyco.fapiao.web.common.BizException;
import com.anomalyco.fapiao.web.config.FapiaoWebProperties;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 对称加解密，用于在 sys_setting 中加密保存 API Key / Secret 等敏感字段。
 *
 * 输出格式：v1:{base64(nonce 12B || ciphertext+tag)}，nonce 每次随机。
 * 主密钥来自 fapiao-web.security.master-key（>= 32 字节，启动期校验）。
 */
@Component
@RequiredArgsConstructor
public class CryptoUtil {

    private static final String VERSION = "v1";
    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int NONCE_LEN = 12;
    private static final int TAG_LEN_BITS = 128;

    private final FapiaoWebProperties props;
    private SecretKey key;
    private final SecureRandom rng = new SecureRandom();

    /** 启动期强校验：若 master-key 未配置或长度不足，立即失败，避免运行时才暴露。 */
    @PostConstruct
    void init() {
        // 触发 key() 完成校验 + 初始化；失败时抛 IllegalStateException 阻断启动
        key();
    }

    private SecretKey key() {
        if (key != null) return key;
        String mk = props.getSecurity().getMasterKey();
        if (mk == null || mk.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                "fapiao-web.security.master-key must be set and >= 32 bytes (env FAPIAO_WEB_MASTER_KEY). " +
                "Generate one with: openssl rand -base64 48");
        }
        byte[] raw = sha256(mk.getBytes(StandardCharsets.UTF_8));
        key = new SecretKeySpec(raw, "AES");
        return key;
    }

    private static byte[] sha256(byte[] in) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return md.digest(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null) return null;
        try {
            byte[] nonce = new byte[NONCE_LEN];
            rng.nextBytes(nonce);
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_LEN_BITS, nonce));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ct, 0, out, nonce.length, ct.length);
            return VERSION + ":" + Base64.getEncoder().encodeToString(out);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(500, "加密失败: " + e.getMessage());
        }
    }

    public String decrypt(String token) {
        if (token == null || token.isEmpty()) return null;
        if (!token.startsWith(VERSION + ":")) {
            // 兼容未加密的旧值（明文），按原文返回（用于一次性迁移期）
            return token;
        }
        try {
            byte[] all = Base64.getDecoder().decode(token.substring(VERSION.length() + 1));
            if (all.length < NONCE_LEN + 16) throw new BizException(400, "ciphertext too short");
            byte[] nonce = new byte[NONCE_LEN];
            byte[] ct = new byte[all.length - NONCE_LEN];
            System.arraycopy(all, 0, nonce, 0, NONCE_LEN);
            System.arraycopy(all, NONCE_LEN, ct, 0, ct.length);
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_LEN_BITS, nonce));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(500, "解密失败: " + e.getMessage());
        }
    }
}